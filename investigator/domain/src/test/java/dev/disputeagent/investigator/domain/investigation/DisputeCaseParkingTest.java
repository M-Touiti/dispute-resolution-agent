package dev.disputeagent.investigator.domain.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.disputeagent.investigator.domain.CaseId;
import dev.disputeagent.investigator.domain.DecisionOutcome;
import dev.disputeagent.investigator.domain.DisputeCategory;
import dev.disputeagent.investigator.domain.decision.Confidence;
import dev.disputeagent.investigator.domain.decision.Decision;
import dev.disputeagent.investigator.domain.guard.GuardResult;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A case the model or the ledger cannot serve is parked and replayed from the start (ADR 0008). */
class DisputeCaseParkingTest {

    private static final Instant T0 = Instant.parse("2026-11-23T09:00:00Z");

    private static DisputeCase investigating() {
        var c = new DisputeCase(new CaseId("DSP-000018"), DisputeCategory.UNAUTHORISED_TRANSACTION, T0);
        c.triaged(DisputeCategory.DUPLICATE_CHARGE, T0.plusSeconds(1));
        c.investigationStarted(T0.plusSeconds(2));
        return c;
    }

    @Test
    void aParkedCaseIsReplayedFromTheStartWithItsHistory() {
        var c = investigating();

        c.park(T0.plusSeconds(10), "OVERLOADED after 3 attempts");
        assertThat(c.state()).isEqualTo(CaseState.AWAITING_RETRY);
        assertThat(c.state().endsInvestigation()).isFalse();
        assertThat(c.escalationReason()).isEmpty();

        c.replay(T0.plusSeconds(70));
        assertThat(c.state()).isEqualTo(CaseState.RECEIVED);
        assertThat(c.replays()).isEqualTo(1);
        assertThat(c.category()).as("triage runs again").isEqualTo(DisputeCategory.UNAUTHORISED_TRANSACTION);

        c.triaged(DisputeCategory.UNAUTHORISED_TRANSACTION, T0.plusSeconds(71));
        c.investigationStarted(T0.plusSeconds(72));
        c.decided(new Decision(DecisionOutcome.DECLINE, Confidence.HIGH, "r", List.of("TXN-1"),
                List.of("time-limits"), List.of()), new GuardResult(List.of()), T0.plusSeconds(90));

        assertThat(c.state()).isEqualTo(CaseState.DECIDED);
        assertThat(c.history()).extracting(DisputeCase.StateChange::to).containsExactly(CaseState.RECEIVED,
                CaseState.TRIAGED, CaseState.INVESTIGATING, CaseState.AWAITING_RETRY, CaseState.RECEIVED,
                CaseState.TRIAGED, CaseState.INVESTIGATING, CaseState.DECIDED);
    }

    @Test
    void theReplayCountGrowsAndAParkedCaseCanStillEscalate() {
        var c = new DisputeCase(new CaseId("DSP-000018"), DisputeCategory.UNAUTHORISED_TRANSACTION, T0);
        for (int i = 1; i <= 3; i++) {
            c.park(T0.plusSeconds(10L * i), "CONNECTION");
            c.replay(T0.plusSeconds(10L * i + 5));
        }
        c.park(T0.plusSeconds(100), "CONNECTION");

        c.escalate(EscalationReason.DEPENDENCY_UNAVAILABLE, T0.plusSeconds(110), "replay cap");

        assertThat(c.replays()).isEqualTo(3);
        assertThat(c.state()).isEqualTo(CaseState.ESCALATED);
        assertThat(c.escalationReason()).contains(EscalationReason.DEPENDENCY_UNAVAILABLE);
    }

    @Test
    void aFinishedInvestigationCannotBeParkedOrReplayed() {
        var c = investigating();
        c.escalate(EscalationReason.MODEL_REFUSED, T0.plusSeconds(5), "refusal");

        assertThatThrownBy(() -> c.park(T0.plusSeconds(6), "x")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> c.replay(T0.plusSeconds(6))).isInstanceOf(IllegalStateException.class);
    }
}
