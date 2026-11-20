package dev.disputeagent.investigator.adapters.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import dev.disputeagent.investigator.adapters.json.JacksonJsonAdapter;
import dev.disputeagent.investigator.application.intake.DisputeOpened;
import dev.disputeagent.investigator.application.investigation.InvestigateDispute;
import dev.disputeagent.investigator.application.investigation.InvestigationSettings;
import dev.disputeagent.investigator.application.port.audit.AuditEvent;
import dev.disputeagent.investigator.application.port.llm.ContentBlock;
import dev.disputeagent.investigator.application.port.llm.Effort;
import dev.disputeagent.investigator.application.port.llm.LlmCost;
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
import dev.disputeagent.investigator.application.port.skills.Skill;
import dev.disputeagent.investigator.domain.CaseId;
import dev.disputeagent.investigator.domain.DecisionOutcome;
import dev.disputeagent.investigator.domain.DisputeCategory;
import dev.disputeagent.investigator.domain.decision.Money;
import dev.disputeagent.investigator.domain.guard.PolicyLimits;
import dev.disputeagent.investigator.domain.investigation.CaseState;
import dev.disputeagent.investigator.domain.investigation.DisputeCase;
import dev.disputeagent.investigator.domain.investigation.EscalationReason;
import dev.disputeagent.investigator.domain.investigation.LoopBudget;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A whole investigation recorded, then replayed with fresh instances: the same decision, no live call. Then a record
 * the model read changes and the replay misses, so a data change cannot hide behind an old recording. A record only
 * the investigator reads (for the guard's evidence index) does not enter the model's requests, so changing it does
 * not miss; the guard runs on the current data either way.
 */
class InvestigationReplayTest {

    static final DisputeOpened DISPUTE = new DisputeOpened(new CaseId("DSP-000001"), "CH-000042", "CRD-000042",
            "TXN-000100", DisputeCategory.UNAUTHORISED_TRANSACTION, Instant.parse("2026-10-30T10:00:00Z"),
            Money.eur(42_500), "I never made this payment.", List.of());

    static final String DECISION = """
            {"outcome":"APPROVE_PROVISIONAL_CREDIT","confidence":"HIGH","rationale":"No 3-D Secure, fraud flag.",
             "cited_evidence_ids":["TXN-000100","AUTH-000100"],"cited_policy_sections":["unauthorised-card-not-present"],
             "proposed_actions":[{"action":"issue_provisional_credit","amount_minor":42500,"currency":"EUR"}]}""";

    @TempDir
    Path dir;

    /** Two turns: search the policy and read the authorisation in parallel, then decide. */
    static final class ScriptedLive implements LlmPort {
        final Deque<LlmResponse> script = new ArrayDeque<>(List.of(
                response("msg_1", StopReason.TOOL_USE, new ContentBlock.Thinking("", "sig-1"),
                        new ContentBlock.ToolUse("t1", "search_policy", "{\"query\":\"card not present\"}"),
                        new ContentBlock.ToolUse("t2", "get_authorisations", "{\"card_id\":\"CRD-000042\"}")),
                response("msg_2", StopReason.END_TURN, new ContentBlock.Text(DECISION))));
        int calls;

        @Override
        public LlmResponse send(LlmRequest request) {
            calls++;
            return script.removeFirst();
        }

        static LlmResponse response(String id, StopReason stop, ContentBlock... content) {
            return new LlmResponse(id, "claude-opus-5-5", stop, List.of(content), new Usage(900, 120, 4_000, 0),
                    null, new LlmCost(0, "live"));
        }
    }

    /** The ledger and policy servers, with a fraud flag the model reads and a date only the investigator reads. */
    static final class Servers implements McpToolPort {
        String postedOn = "2026-10-01";
        boolean fraudFlag = true;

        @Override
        public List<McpTool> listTools(McpServer server) {
            List<String> names = server == McpServer.LEDGER
                    ? List.of("get_transaction", "search_transactions", "get_authorisations")
                    : server == McpServer.POLICY ? List.of("search_policy") : List.of();
            List<McpTool> tools = new ArrayList<>();
            names.forEach(n -> tools.add(new McpTool(server, n, n, "{\"type\":\"object\"}")));
            return tools;
        }

        @Override
        public McpToolResult call(McpToolCall call) {
            return new McpToolResult(switch (call.toolName()) {
                case "get_transaction" -> "{\"transaction_id\":\"TXN-000100\",\"card_id\":\"CRD-000042\","
                        + "\"auth_id\":\"AUTH-000100\",\"posted_on\":\"" + postedOn + "\"}";
                case "search_transactions" -> "{\"items\":[{\"transaction_id\":\"TXN-000100\",\"auth_id\":\"AUTH-000100\""
                        + ",\"posted_on\":\"" + postedOn + "\"}],\"next_page_token\":null}";
                case "get_authorisations" -> "{\"items\":[{\"auth_id\":\"AUTH-000100\",\"fraud_flag\":" + fraudFlag
                        + "}],\"next_page_token\":null}";
                case "search_policy" -> "{\"results\":[{\"section_id\":\"unauthorised-card-not-present\"}]}";
                default -> throw new AssertionError(call.toolName());
            }, false);
        }
    }

    DisputeCase investigate(LlmPort llm, McpToolPort servers, List<AuditEvent> audit) {
        var settings = new InvestigationSettings("claude-opus-5-5", Effort.MEDIUM, 16_000, "claude-haiku-5-5",
                Effort.LOW, 1_000, new LoopBudget(8, 20, 200_000, Duration.ofMinutes(5)), PolicyLimits.DEFAULT, 1);
        var clock = Clock.fixed(Instant.parse("2026-11-20T09:00:00Z"), ZoneOffset.UTC);
        var c = new DisputeCase(DISPUTE.disputeId(), DISPUTE.category(), clock.instant());
        c.triaged(DisputeCategory.UNAUTHORISED_TRANSACTION, clock.instant());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            new InvestigateDispute(llm, servers, new JacksonJsonAdapter(), audit::add, clock, executor, settings,
                    () -> List.of(new Skill("unauthorised-transaction-checklist", "The checklist.", "1. Look.")))
                    .investigate(c, DISPUTE);
        }
        return c;
    }

    @Test
    void aRecordedInvestigationReplaysToTheSameDecisionWithoutALiveCall() {
        var live = new ScriptedLive();
        DisputeCase recorded = investigate(new RecordingAdapter(live, new Fixtures(dir)), new Servers(),
                new ArrayList<>());

        DisputeCase replayed = investigate(new ReplayAdapter(new Fixtures(dir), RecordReplayTest.PRICES),
                new Servers(), new ArrayList<>());

        assertThat(recorded.state()).isEqualTo(CaseState.DECIDED);
        assertThat(live.calls).isEqualTo(2);
        assertThat(replayed.state()).isEqualTo(CaseState.DECIDED);
        assertThat(replayed.decision()).isEqualTo(recorded.decision());
        assertThat(replayed.decision().orElseThrow().outcome()).isEqualTo(DecisionOutcome.APPROVE_PROVISIONAL_CREDIT);
    }

    @Test
    void aChangedRecordTheModelReadMakesTheReplayMissAndTheCaseEscalate() {
        investigate(new RecordingAdapter(new ScriptedLive(), new Fixtures(dir)), new Servers(), new ArrayList<>());
        var changed = new Servers();
        changed.fraudFlag = false;
        var audit = new ArrayList<AuditEvent>();

        DisputeCase replayed = investigate(new ReplayAdapter(new Fixtures(dir), RecordReplayTest.PRICES), changed,
                audit);

        assertThat(replayed.state()).isEqualTo(CaseState.ESCALATED);
        assertThat(replayed.escalationReason()).contains(EscalationReason.DEPENDENCY_UNAVAILABLE);
        assertThat(audit).filteredOn(AuditEvent.ModelCallFailed.class::isInstance).singleElement()
                .satisfies(e -> assertThat(((AuditEvent.ModelCallFailed) e).error()).isEqualTo("LlmCallException"));
    }

    @Test
    void aRecordOnlyTheInvestigatorReadsDoesNotEnterTheModelsRequests() {
        investigate(new RecordingAdapter(new ScriptedLive(), new Fixtures(dir)), new Servers(), new ArrayList<>());
        var changed = new Servers();
        changed.postedOn = "2026-09-30";

        DisputeCase replayed = investigate(new ReplayAdapter(new Fixtures(dir), RecordReplayTest.PRICES), changed,
                new ArrayList<>());

        assertThat(replayed.state()).isEqualTo(CaseState.DECIDED);
    }
}
