package dev.disputeagent.investigator.bootstrap.eval;

import static org.assertj.core.api.Assertions.assertThat;

import dev.disputeagent.investigator.application.intake.DisputeOpened;
import dev.disputeagent.investigator.application.port.audit.AuditEvent;
import dev.disputeagent.investigator.application.port.llm.LlmCost;
import dev.disputeagent.investigator.application.port.llm.StopReason;
import dev.disputeagent.investigator.application.port.llm.Usage;
import dev.disputeagent.investigator.domain.CaseId;
import dev.disputeagent.investigator.domain.DisputeCategory;
import dev.disputeagent.investigator.domain.decision.Money;
import dev.disputeagent.investigator.domain.investigation.CaseState;
import dev.disputeagent.investigator.domain.investigation.DisputeCase;
import dev.disputeagent.investigator.domain.investigation.EscalationReason;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** What one result file says about a case, measured from its audit trail. */
class EvalRunnerResultTest {

    private static final Instant T0 = Instant.parse("2026-11-23T09:00:00Z");
    private static final CaseId ID = new CaseId("DSP-000018");

    @Test
    void rejectionsAreCountedAndUnmeasuredEventsAreCountedByType() {
        var dispute = new DisputeOpened(ID, "CH-1", "CRD-1", "TXN-1", DisputeCategory.UNAUTHORISED_TRANSACTION, T0,
                new Money(100, "EUR"), "statement", List.of());
        var finished = new DisputeCase(ID, DisputeCategory.UNAUTHORISED_TRANSACTION, T0);
        finished.park(T0.plusSeconds(1), "OVERLOADED");
        finished.escalate(EscalationReason.DEPENDENCY_UNAVAILABLE, T0.plusSeconds(2), "replay cap");

        List<AuditEvent> events = List.of(
                new AuditEvent.StateChanged(ID, T0, null, CaseState.RECEIVED, "received"),
                new AuditEvent.TriageFellBack(ID, T0, "no answer"),
                new AuditEvent.DecisionRejected(ID, T0, 1, List.of("missing outcome")),
                new AuditEvent.DecisionRejected(ID, T0, 2, List.of("missing outcome")),
                new AuditEvent.CaseParked(ID, T0, "OVERLOADED after 3 attempts", 0),
                new AuditEvent.StateChanged(ID, T0, CaseState.RECEIVED, CaseState.AWAITING_RETRY, "parked"));

        Map<String, Object> result = EvalRunner.result(dispute, finished, events, Duration.ofMillis(1500));

        assertThat(result).containsEntry("decision_rejections", 2).containsEntry("triage_fell_back", true);
        assertThat(result.get("model_failures")).isEqualTo(List.of("PARKED: OVERLOADED after 3 attempts"));
        assertThat(result.get("other_events")).isEqualTo(
                Map.of("CaseParked", 1, "StateChanged", 2, "TriageFellBack", 1));
    }

    @Test
    void theWallClockAndTheLatencyOfEveryModelCallAreRecorded() {
        var dispute = new DisputeOpened(ID, "CH-1", "CRD-1", "TXN-1", DisputeCategory.UNAUTHORISED_TRANSACTION, T0,
                new Money(100, "EUR"), "statement", List.of());
        var finished = new DisputeCase(ID, DisputeCategory.UNAUTHORISED_TRANSACTION, T0);
        finished.escalate(EscalationReason.DEPENDENCY_UNAVAILABLE, T0.plusSeconds(2), "model unavailable");

        List<AuditEvent> events = List.of(
                new AuditEvent.ModelCalled(ID, T0, AuditEvent.Stage.TRIAGE, "haiku", "haiku", "msg_1",
                        StopReason.END_TURN, new Usage(10, 5, 0, 0), new LlmCost(7, "2026-11-20"),
                        Duration.ofMillis(420)),
                new AuditEvent.ModelCallFailed(ID, T0, AuditEvent.Stage.INVESTIGATE, "opus", "OVERLOADED",
                        Duration.ofMillis(95)));

        Map<String, Object> result = EvalRunner.result(dispute, finished, events, Duration.ofMillis(2345));

        assertThat(result.get("timing")).isEqualTo(Map.of("wall_clock_ms", 2345L, "model_call_ms", List.of(420L, 95L)));
        assertThat(((Map<?, ?>) result.get("usage")).get("models")).isEqualTo(List.of("haiku"));
    }
}
