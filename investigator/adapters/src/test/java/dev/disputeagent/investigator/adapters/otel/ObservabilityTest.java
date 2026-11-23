package dev.disputeagent.investigator.adapters.otel;

import static io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.disputeagent.investigator.application.port.audit.AuditEvent;
import dev.disputeagent.investigator.application.port.audit.AuditLog;
import dev.disputeagent.investigator.application.port.llm.Effort;
import dev.disputeagent.investigator.application.port.llm.LlmCallException;
import dev.disputeagent.investigator.application.port.llm.LlmCost;
import dev.disputeagent.investigator.application.port.llm.LlmRequest;
import dev.disputeagent.investigator.application.port.llm.LlmResponse;
import dev.disputeagent.investigator.application.port.llm.StopReason;
import dev.disputeagent.investigator.application.port.llm.SystemBlock;
import dev.disputeagent.investigator.application.port.llm.Usage;
import dev.disputeagent.investigator.application.port.mcp.McpServer;
import dev.disputeagent.investigator.application.port.mcp.McpTool;
import dev.disputeagent.investigator.application.port.mcp.McpToolCall;
import dev.disputeagent.investigator.application.port.mcp.McpToolPort;
import dev.disputeagent.investigator.application.port.mcp.McpToolResult;
import dev.disputeagent.investigator.domain.CaseId;
import dev.disputeagent.investigator.domain.investigation.CaseState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The decorators with Micrometer's test registries: no OpenTelemetry, no Prometheus. */
class ObservabilityTest {

    static final Instant T0 = Instant.parse("2026-11-24T09:00:00Z");
    static final CaseId ID = new CaseId("DSP-000018");
    static final LlmRequest REQUEST = new LlmRequest("claude-opus-5-5", Effort.MEDIUM, 16000,
            List.of(new SystemBlock("s", true)),
            List.of(dev.disputeagent.investigator.application.port.llm.LlmMessage.userText("the case")), List.of(),
            null);

    final TestObservationRegistry observations = TestObservationRegistry.create();
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @Test
    void aModelCallIsASpanWithItsTokensAndCost() {
        var response = new LlmResponse("msg", "claude-opus-5-5-20261101", StopReason.END_TURN, List.of(),
                new Usage(1200, 300, 9000, 40), null, new LlmCost(61_500, "2026-11"));

        new ObservedLlmPort(request -> response, observations).send(REQUEST);

        assertThat(observations).hasObservationWithNameEqualTo(ObservedLlmPort.NAME).that()
                .hasContextualNameEqualTo("llm claude-opus-5-5")
                .hasLowCardinalityKeyValue("model", "claude-opus-5-5")
                .hasLowCardinalityKeyValue("effort", "medium")
                .hasLowCardinalityKeyValue("stop_reason", "end_turn")
                .hasLowCardinalityKeyValue("kind", "none")
                .hasHighCardinalityKeyValue("tokens.input", "1200")
                .hasHighCardinalityKeyValue("tokens.cache_read", "9000")
                .hasHighCardinalityKeyValue("cost.micro_usd", "61500")
                .hasHighCardinalityKeyValue("answered_model", "claude-opus-5-5-20261101")
                .hasBeenStarted().hasBeenStopped();
    }

    @Test
    void aFailedModelCallCarriesItsKindAndTheError() {
        var port = new ObservedLlmPort(request -> {
            throw new LlmCallException(LlmCallException.Kind.OVERLOADED, "529", null);
        }, observations);

        assertThatThrownBy(() -> port.send(REQUEST)).isInstanceOf(LlmCallException.class);
        assertThat(observations).hasObservationWithNameEqualTo(ObservedLlmPort.NAME).that()
                .hasLowCardinalityKeyValue("kind", "overloaded")
                .hasLowCardinalityKeyValue("stop_reason", "none")
                .hasError();
    }

    @Test
    void aToolCallIsTaggedByServerToolAndOutcomeWithoutItsArguments() {
        McpToolPort tools = new McpToolPort() {
            @Override
            public List<McpTool> listTools(McpServer server) {
                return List.of();
            }

            @Override
            public McpToolResult call(McpToolCall call) {
                return new McpToolResult("NOT_FOUND: no such card", true);
            }
        };

        new ObservedMcpToolPort(tools, observations)
                .call(new McpToolCall(McpServer.LEDGER, "get_card", "{\"card_id\":\"CRD-000001\"}"));

        assertThat(observations).hasObservationWithNameEqualTo(ObservedMcpToolPort.NAME).that()
                .hasLowCardinalityKeyValue("server", "ledger")
                .hasLowCardinalityKeyValue("tool", "get_card")
                .hasLowCardinalityKeyValue("outcome", "error")
                .doesNotHaveHighCardinalityKeyValueWithKey("arguments");
    }

    @Test
    void theAuditTrailBecomesCaseMetrics() {
        List<AuditEvent> recorded = new ArrayList<>();
        AuditLog metered = new MeteredAuditLog(recorded::add, meters);
        var usage = new Usage(1000, 200, 3000, 0);

        metered.record(new AuditEvent.ModelCalled(ID, T0, AuditEvent.Stage.INVESTIGATE, "claude-opus-5-5",
                "claude-opus-5-5", "m1", StopReason.END_TURN, usage, new LlmCost(50_000, "v"), Duration.ofSeconds(3)));
        metered.record(new AuditEvent.ModelCalled(ID, T0, AuditEvent.Stage.INVESTIGATE, "claude-opus-5-5",
                "claude-opus-5-5", "m2", StopReason.END_TURN, usage, new LlmCost(30_000, "v"), Duration.ofSeconds(2)));
        metered.record(new AuditEvent.StateChanged(ID, T0, CaseState.INVESTIGATING, CaseState.DECIDED, "DECLINE"));
        var unsafe = new CaseId("DSP-000099");
        metered.record(new AuditEvent.StateChanged(unsafe, T0, CaseState.INVESTIGATING, CaseState.ESCALATED,
                "GUARD_FAILED: 1 guard violations"));
        metered.record(new AuditEvent.CaseParked(new CaseId("DSP-000036"), T0, "OVERLOADED", 0));
        metered.record(new AuditEvent.Reviewed(ID, T0.plusSeconds(600), true, "r", "ok", T0));

        assertThat(recorded).as("every event still reaches the audit log").hasSize(6);
        assertThat(meters.get("dispute.cases").tags("outcome", "decided", "reason", "none").counter().count())
                .isEqualTo(1);
        assertThat(meters.get("dispute.cases").tags("outcome", "escalated", "reason", "GUARD_FAILED").counter()
                .count()).isEqualTo(1);
        assertThat(meters.get("dispute.unsafe.proposals").counter().count()).isEqualTo(1);
        assertThat(meters.get("dispute.cases.parked").tags("after_replays", "0").counter().count()).isEqualTo(1);
        assertThat(meters.get("dispute.llm.tokens").tags("type", "cache_read").counter().count()).isEqualTo(6000);
        assertThat(meters.get("dispute.llm.cost").counter().count()).isEqualTo(0.08);
        assertThat(meters.get("dispute.case.tokens").summary().totalAmount()).as("one run, both calls")
                .isEqualTo(2400);
        assertThat(meters.get("dispute.case.cost").summary().totalAmount()).isEqualTo(0.08);
        assertThat(meters.get("dispute.approval.latency").tags("decision", "approved").timer()
                .totalTime(java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(600);
    }

    @Test
    void cardNumbersAreMaskedAndIdsAndAmountsAreNot() {
        assertThat(CardNumbers.mask("card 4111 1111 1111 1111 was used")).isEqualTo("card ************1111 was used");
        assertThat(CardNumbers.mask("pan=5555-5555-5555-4444;")).isEqualTo("pan=************4444;");
        assertThat(CardNumbers.mask("DSP-000018 TXN-007866 credited 110056 cents"))
                .isEqualTo("DSP-000018 TXN-007866 credited 110056 cents");
        assertThat(CardNumbers.mask("1234567890123456")).as("fails the Luhn check").isEqualTo("1234567890123456");
        assertThat(CardNumbers.mask(null)).isNull();
    }
}
