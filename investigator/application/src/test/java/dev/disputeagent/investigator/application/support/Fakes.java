package dev.disputeagent.investigator.application.support;

import dev.disputeagent.investigator.application.port.audit.AuditEvent;
import dev.disputeagent.investigator.application.port.audit.AuditLog;
import dev.disputeagent.investigator.application.port.json.JsonPort;
import dev.disputeagent.investigator.application.port.json.Parsed;
import dev.disputeagent.investigator.application.port.llm.ContentBlock;
import dev.disputeagent.investigator.application.port.llm.LlmPort;
import dev.disputeagent.investigator.application.port.llm.LlmRequest;
import dev.disputeagent.investigator.application.port.llm.LlmResponse;
import dev.disputeagent.investigator.application.port.llm.StopReason;
import dev.disputeagent.investigator.application.port.llm.Usage;
import dev.disputeagent.investigator.application.port.mcp.McpServer;
import dev.disputeagent.investigator.application.port.mcp.McpTool;
import dev.disputeagent.investigator.application.port.mcp.McpToolCall;
import dev.disputeagent.investigator.application.port.mcp.McpToolPort;
import dev.disputeagent.investigator.application.port.mcp.McpToolResult;
import dev.disputeagent.investigator.application.triage.Triage;
import dev.disputeagent.investigator.domain.decision.Decision;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Test doubles for the application's ports. No JSON library: the fake JSON port works on registered texts. */
public final class Fakes {

    private Fakes() {
    }

    /** The two repository skills, abridged. */
    public static final dev.disputeagent.investigator.application.port.skills.SkillLibrary SKILLS = () -> List.of(
            new dev.disputeagent.investigator.application.port.skills.Skill("unauthorised-transaction-checklist",
                    "The evidence check for an unauthorised transaction.", "1. Get the disputed transaction."),
            new dev.disputeagent.investigator.application.port.skills.Skill("reading-an-authorisation-trail",
                    "How to read an authorisation.", "| entry_mode | CHIP |"));

    /** Settings as configuration would give them: model ids, effort, token caps, budget, policy limits. */
    public static dev.disputeagent.investigator.application.investigation.InvestigationSettings settings(
            dev.disputeagent.investigator.domain.investigation.LoopBudget budget) {
        return new dev.disputeagent.investigator.application.investigation.InvestigationSettings("claude-opus-5-5",
                dev.disputeagent.investigator.application.port.llm.Effort.MEDIUM, 16_000, "claude-haiku-5-5",
                dev.disputeagent.investigator.application.port.llm.Effort.LOW, 1_000, budget,
                dev.disputeagent.investigator.domain.guard.PolicyLimits.DEFAULT, 1);
    }

    /** Answers with scripted responses in order and records every request. */
    public static final class ScriptedLlm implements LlmPort {
        private final Deque<Object> script = new ArrayDeque<>();
        public final List<LlmRequest> requests = new ArrayList<>();
        private int ids;

        public ScriptedLlm then(StopReason stop, ContentBlock... content) {
            script.add(new LlmResponse("msg_" + ++ids, "claude-opus-5-5", stop, List.of(content),
                    new Usage(1_000, 200, 800, 0), stop == StopReason.REFUSAL ? "cyber" : null));
            return this;
        }

        public ScriptedLlm thenThrow(RuntimeException e) {
            script.add(e);
            return this;
        }

        @Override
        public LlmResponse send(LlmRequest request) {
            requests.add(request);
            Object next = script.poll();
            if (next == null) {
                throw new AssertionError("the script has no response for request " + requests.size());
            }
            if (next instanceof RuntimeException e) {
                throw e;
            }
            return (LlmResponse) next;
        }
    }

    /** Tools per server and a handler per tool name; records every call (thread-safe). */
    public static final class FakeMcp implements McpToolPort {
        private final Map<McpServer, List<McpTool>> tools = new HashMap<>();
        private final Map<String, Function<String, McpToolResult>> handlers = new HashMap<>();
        public final List<McpToolCall> calls = new CopyOnWriteArrayList<>();

        public FakeMcp tool(McpServer server, String name, Function<String, McpToolResult> handler) {
            tools.computeIfAbsent(server, s -> new ArrayList<>())
                    .add(new McpTool(server, name, name + " description", "{\"type\":\"object\"}"));
            handlers.put(name, handler);
            return this;
        }

        @Override
        public List<McpTool> listTools(McpServer server) {
            return tools.getOrDefault(server, List.of());
        }

        @Override
        public McpToolResult call(McpToolCall call) {
            calls.add(call);
            var handler = handlers.get(call.toolName());
            if (handler == null) {
                throw new AssertionError("no handler for " + call.toolName());
            }
            return handler.apply(call.argumentsJson());
        }

        public List<String> calledTools() {
            return calls.stream().map(McpToolCall::toolName).toList();
        }
    }

    /**
     * Decisions and triage answers are registered against the exact text the scripted model returns. {@code
     * valuesOf} reads {@code "field":"value"} pairs with a regular expression, enough for the canned tool results.
     */
    public static final class FakeJson implements JsonPort {
        private final Map<String, Parsed<Decision>> decisions = new HashMap<>();
        private final Map<String, Parsed<Triage>> triages = new HashMap<>();

        public FakeJson decision(String text, Decision decision) {
            decisions.put(text, Parsed.ok(decision));
            return this;
        }

        public FakeJson invalidDecision(String text, String error) {
            decisions.put(text, Parsed.failed(List.of(error)));
            return this;
        }

        public FakeJson triage(String text, Triage triage) {
            triages.put(text, Parsed.ok(triage));
            return this;
        }

        @Override
        public Parsed<Decision> decision(String json) {
            return decisions.getOrDefault(json, Parsed.failed(List.of("unregistered decision text: " + json)));
        }

        @Override
        public Parsed<Triage> triage(String json) {
            return triages.getOrDefault(json, Parsed.failed(List.of("unregistered triage text")));
        }

        @Override
        public List<String> valuesOf(String json, String field) {
            Matcher m = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
            List<String> out = new ArrayList<>();
            while (m.find()) {
                out.add(m.group(1));
            }
            return out;
        }

        @Override
        public String object(Map<String, ?> members) {
            List<String> parts = new ArrayList<>();
            new java.util.TreeMap<>(members).forEach((k, v) -> parts.add("\"" + k + "\":"
                    + (v instanceof String s ? "\"" + s + "\"" : String.valueOf(v))));
            return "{" + String.join(",", parts) + "}";
        }
    }

    public static final class RecordingAudit implements AuditLog {
        public final List<AuditEvent> events = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void record(AuditEvent event) {
            events.add(event);
        }

        public <T extends AuditEvent> List<T> of(Class<T> type) {
            return events.stream().filter(type::isInstance).map(type::cast).toList();
        }
    }

    /** A clock that moves forward one second every time it is read, and can jump. */
    public static final class TickingClock extends Clock {
        private Instant now;

        public TickingClock(Instant start) {
            this.now = start;
        }

        public synchronized void advance(java.time.Duration d) {
            now = now.plus(d);
        }

        @Override
        public synchronized Instant instant() {
            Instant current = now;
            now = now.plusSeconds(1);
            return current;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
