package dev.disputeagent.investigator.application.investigation;

import dev.disputeagent.investigator.application.intake.DisputeOpened;
import dev.disputeagent.investigator.application.port.audit.AuditEvent;
import dev.disputeagent.investigator.application.port.audit.AuditEvent.Caller;
import dev.disputeagent.investigator.application.port.audit.AuditEvent.Stage;
import dev.disputeagent.investigator.application.port.audit.AuditLog;
import dev.disputeagent.investigator.application.port.json.JsonPort;
import dev.disputeagent.investigator.application.port.json.Parsed;
import dev.disputeagent.investigator.application.port.llm.ContentBlock;
import dev.disputeagent.investigator.application.port.llm.LlmMessage;
import dev.disputeagent.investigator.application.port.llm.LlmPort;
import dev.disputeagent.investigator.application.port.llm.LlmRequest;
import dev.disputeagent.investigator.application.port.llm.LlmResponse;
import dev.disputeagent.investigator.application.port.llm.OutputFormat;
import dev.disputeagent.investigator.application.port.llm.Role;
import dev.disputeagent.investigator.application.port.llm.SystemBlock;
import dev.disputeagent.investigator.application.port.llm.ToolDefinition;
import dev.disputeagent.investigator.application.port.mcp.McpServer;
import dev.disputeagent.investigator.application.port.mcp.McpToolCall;
import dev.disputeagent.investigator.application.port.mcp.McpToolPort;
import dev.disputeagent.investigator.application.port.mcp.McpToolResult;
import dev.disputeagent.investigator.application.port.skills.SkillLibrary;
import dev.disputeagent.investigator.domain.decision.Decision;
import dev.disputeagent.investigator.domain.guard.GuardResult;
import dev.disputeagent.investigator.domain.guard.PolicyGuard;
import dev.disputeagent.investigator.domain.investigation.CaseState;
import dev.disputeagent.investigator.domain.investigation.DisputeCase;
import dev.disputeagent.investigator.domain.investigation.EscalationReason;
import dev.disputeagent.investigator.domain.investigation.LoopUsage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * The investigation loop (ADR 0002): hand-written over {@link LlmPort} and {@link McpToolPort}.
 *
 * <ul>
 *   <li>Check the caps before every model call and before every turn of tool calls; a cap escalates.</li>
 *   <li>Check {@code stop_reason} before reading content: {@code refusal} escalates, {@code tool_use} runs the
 *       turn's tool calls concurrently and returns every result in one user message, {@code end_turn} parses the
 *       decision, anything else escalates.</li>
 *   <li>Append each assistant message exactly as returned (thinking blocks and signatures included).</li>
 *   <li>Wrap tool results as untrusted; a failed or unknown tool returns {@code is_error}, never a silent drop.</li>
 *   <li>A decision that fails the schema is sent back once with the errors, then the case escalates.</li>
 *   <li>A valid decision goes through the {@link PolicyGuard}; a failure escalates as an unsafe proposal.</li>
 *   <li>Every model call, tool call, state change and decision is audited, in order, from this thread.</li>
 * </ul>
 */
public final class InvestigateDispute {

    private static final String BASE_PROMPT = Resources.text(InvestigateDispute.class, "system-prompt.md");

    private final LlmPort llm;
    private final McpToolPort mcp;
    private final JsonPort json;
    private final AuditLog audit;
    private final Clock clock;
    private final ExecutorService toolExecutor;
    private final InvestigationSettings settings;
    private final PolicyGuard guard;
    private final ToolInvoker invoker;
    private final Skills skills;
    private final String systemPrompt;

    public InvestigateDispute(LlmPort llm, McpToolPort mcp, JsonPort json, AuditLog audit, Clock clock,
            ExecutorService toolExecutor, InvestigationSettings settings, SkillLibrary skillLibrary) {
        this.llm = llm;
        this.mcp = mcp;
        this.json = json;
        this.audit = audit;
        this.clock = clock;
        this.toolExecutor = toolExecutor;
        this.settings = settings;
        this.guard = new PolicyGuard(settings.policyLimits());
        this.invoker = new ToolInvoker(mcp, clock);
        this.skills = new Skills(skillLibrary);
        this.systemPrompt = BASE_PROMPT + skills.index();
    }

    /** The stable system prompt with the skill index; part of every request and of the fixture key. */
    public String systemPrompt() {
        return systemPrompt;
    }

    /** Runs the investigation of a triaged case to a terminal state. */
    public void investigate(DisputeCase dispute, DisputeOpened event) {
        if (dispute.state() != CaseState.TRIAGED) {
            throw new IllegalStateException("case " + dispute.id() + " is " + dispute.state() + ", expected TRIAGED");
        }
        transition(dispute, () -> dispute.investigationStarted(clock.instant()));

        Toolbox toolbox;
        CaseEvidence evidence;
        try {
            toolbox = Toolbox.discover(mcp, Set.of(Skills.TOOL_NAME));
            evidence = CaseEvidence.collect(event, invoker, json, audit);
        } catch (CaseEvidence.InvalidCaseData e) {
            escalate(dispute, EscalationReason.CASE_DATA_INVALID, e.getMessage());
            return;
        } catch (RuntimeException e) {
            escalate(dispute, EscalationReason.DEPENDENCY_UNAVAILABLE, "before the loop: " + e.getMessage());
            return;
        }

        var usage = new LoopUsage(settings.budget(), clock.instant());
        var messages = new ArrayList<LlmMessage>();
        messages.add(LlmMessage.userText(CaseMessage.of(event, dispute.category())));
        Set<String> sectionsRetrieved = new TreeSet<>();
        int retriesLeft = settings.decisionRetries();
        int attempt = 0;

        while (true) {
            var cap = usage.beforeModelCall(clock.instant());
            if (cap.isPresent()) {
                escalate(dispute, EscalationReason.CAP_REACHED, cap.get().cap() + " " + cap.get().detail());
                return;
            }
            Optional<LlmResponse> sent = send(dispute, messages, toolbox);
            if (sent.isEmpty()) {
                escalate(dispute, EscalationReason.DEPENDENCY_UNAVAILABLE, "the model could not be reached");
                return;
            }
            LlmResponse response = sent.get();
            usage.modelCallCompleted(response.usage().inputTokens(), response.usage().outputTokens());

            switch (response.stopReason()) {
                case REFUSAL -> {
                    escalate(dispute, EscalationReason.MODEL_REFUSED, "refusal category " + response.refusalCategory());
                    return;
                }
                case TOOL_USE -> {
                    List<ContentBlock.ToolUse> calls = response.toolUses();
                    if (calls.isEmpty()) {
                        escalate(dispute, EscalationReason.UNEXPECTED_STOP, "tool_use without tool calls");
                        return;
                    }
                    var toolCap = usage.beforeToolCalls(calls.size(), clock.instant());
                    if (toolCap.isPresent()) {
                        escalate(dispute, EscalationReason.CAP_REACHED,
                                toolCap.get().cap() + " " + toolCap.get().detail());
                        return;
                    }
                    List<ContentBlock> results = runTools(dispute, toolbox, calls, sectionsRetrieved);
                    usage.toolCallsCompleted(calls.size());
                    messages.add(response.asAssistantMessage());
                    messages.add(new LlmMessage(Role.USER, results));
                }
                case END_TURN -> {
                    attempt++;
                    Parsed<Decision> parsed = lastText(response)
                            .map(json::decision)
                            .orElse(Parsed.failed(List.of("the answer has no text block")));
                    if (!parsed.ok()) {
                        audit.record(new AuditEvent.DecisionRejected(dispute.id(), clock.instant(), attempt,
                                parsed.errors()));
                        if (retriesLeft-- > 0) {
                            messages.add(response.asAssistantMessage());
                            messages.add(LlmMessage.userText(CaseMessage.fixDecision(parsed.errors())));
                            continue;
                        }
                        escalate(dispute, EscalationReason.DECISION_INVALID, String.join("; ", parsed.errors()));
                        return;
                    }
                    Decision decision = parsed.value();
                    GuardResult checked = guard.check(decision,
                            evidence.facts(event, dispute.category(), sectionsRetrieved));
                    audit.record(new AuditEvent.DecisionChecked(dispute.id(), clock.instant(), decision, checked));
                    transition(dispute, () -> dispute.decided(decision, checked, clock.instant()));
                    return;
                }
                default -> {
                    escalate(dispute, EscalationReason.UNEXPECTED_STOP, "stop_reason " + response.stopReason());
                    return;
                }
            }
        }
    }

    private Optional<LlmResponse> send(DisputeCase dispute, List<LlmMessage> messages, Toolbox toolbox) {
        List<ToolDefinition> tools = new ArrayList<>(toolbox.definitions());
        if (!skills.isEmpty()) {
            tools.add(skills.tool());
        }
        var request = new LlmRequest(settings.investigatorModel(), settings.investigatorEffort(),
                settings.investigatorMaxTokens(), List.of(new SystemBlock(systemPrompt, true)), messages, tools,
                new OutputFormat(Decision.jsonSchema()));
        Instant start = clock.instant();
        try {
            LlmResponse response = llm.send(request);
            audit.record(new AuditEvent.ModelCalled(dispute.id(), start, Stage.INVESTIGATE,
                    settings.investigatorModel(), response.model(), response.id(), response.stopReason(),
                    response.usage(), response.cost(), Duration.between(start, clock.instant())));
            return Optional.of(response);
        } catch (RuntimeException e) {
            audit.record(new AuditEvent.ModelCallFailed(dispute.id(), start, Stage.INVESTIGATE,
                    settings.investigatorModel(), e.getClass().getSimpleName(), Duration.between(start,
                            clock.instant())));
            return Optional.empty();
        }
    }

    /** Runs one turn's calls concurrently; results come back in the order of the calls. */
    private List<ContentBlock> runTools(DisputeCase dispute, Toolbox toolbox, List<ContentBlock.ToolUse> calls,
            Set<String> sectionsRetrieved) {
        List<CompletableFuture<ToolInvoker.Invocation>> futures = new ArrayList<>();
        for (ContentBlock.ToolUse call : calls) {
            Optional<McpServer> server = toolbox.serverOf(call.name());
            futures.add(server
                    .map(s -> CompletableFuture.supplyAsync(() -> invoker.invoke(dispute.id(), Caller.MODEL,
                            new McpToolCall(s, call.name(), call.inputJson())), toolExecutor))
                    .orElse(null));
        }
        List<ContentBlock> results = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            ContentBlock.ToolUse call = calls.get(i);
            if (call.name().equals(Skills.TOOL_NAME)) {
                results.add(loadSkill(dispute, call));
                continue;
            }
            if (futures.get(i) == null) {
                results.add(new ContentBlock.ToolResult(call.id(),
                        "UNKNOWN_TOOL: " + call.name() + " is not available in this investigation", true));
                continue;
            }
            ToolInvoker.Invocation invocation = futures.get(i).join();
            audit.record(invocation.audit());
            McpToolResult result = invocation.result();
            if (!result.isError() && invocation.audit().server() == McpServer.POLICY) {
                sectionsRetrieved.addAll(json.valuesOf(result.content(), "section_id"));
            }
            String wrapped = Untrusted.wrap("untrusted_tool_result", Map.of(
                    "server", invocation.audit().server().name().toLowerCase(), "tool", call.name()),
                    result.content());
            results.add(new ContentBlock.ToolResult(call.id(), wrapped, result.isError()));
        }
        return results;
    }

    /** Answered locally: skills are this repository's text, not data from outside, so they are not wrapped. */
    private ContentBlock.ToolResult loadSkill(DisputeCase dispute, ContentBlock.ToolUse call) {
        String name = json.valuesOf(call.inputJson(), "name").stream().findFirst().orElse("");
        Optional<String> body = skills.load(name);
        audit.record(new AuditEvent.SkillLoaded(dispute.id(), clock.instant(), name, body.isPresent()));
        return body.map(text -> new ContentBlock.ToolResult(call.id(), text, false))
                .orElseGet(() -> new ContentBlock.ToolResult(call.id(),
                        "UNKNOWN_SKILL: " + name + "; available: " + String.join(", ", skills.names()), true));
    }

    private static Optional<String> lastText(LlmResponse response) {
        String text = null;
        for (ContentBlock block : response.content()) {
            if (block instanceof ContentBlock.Text t) {
                text = t.text();
            }
        }
        return Optional.ofNullable(text);
    }

    private void escalate(DisputeCase dispute, EscalationReason reason, String detail) {
        transition(dispute, () -> dispute.escalate(reason, clock.instant(), detail));
    }

    /** Applies a change to the case and audits every state change it caused. */
    private void transition(DisputeCase dispute, Runnable change) {
        int before = dispute.history().size();
        change.run();
        dispute.history().subList(before, dispute.history().size()).forEach(c -> audit.record(
                new AuditEvent.StateChanged(dispute.id(), c.at(), c.from(), c.to(), c.note())));
    }
}
