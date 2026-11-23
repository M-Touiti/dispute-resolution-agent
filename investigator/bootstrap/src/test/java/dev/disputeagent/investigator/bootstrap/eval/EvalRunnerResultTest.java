package dev.disputeagent.investigator.bootstrap.eval;

import static org.assertj.core.api.Assertions.assertThat;

import dev.disputeagent.investigator.application.intake.DisputeOpened;
import dev.disputeagent.investigator.application.port.audit.AuditEvent;
import dev.disputeagent.investigator.domain.CaseId;
import dev.disputeagent.investigator.domain.DisputeCategory;
import dev.disputeagent.investigator.domain.decision.Money;
import dev.disputeagent.investigator.domain.investigation.CaseState;
import dev.disputeagent.investigator.domain.investigation.DisputeCase;
import dev.disputeagent.investigator.domain.investigation.EscalationReason;
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

        Map<String, Object> result = EvalRunner.result(dispute, finished, events);

        assertThat(result).containsEntry("decision_rejections", 2).containsEntry("triage_fell_back", true);
        assertThat(result.get("model_failures")).isEqualTo(List.of("PARKED: OVERLOADED after 3 attempts"));
        assertThat(result.get("other_events")).isEqualTo(
                Map.of("CaseParked", 1, "StateChanged", 2, "TriageFellBack", 1));
    }
}
