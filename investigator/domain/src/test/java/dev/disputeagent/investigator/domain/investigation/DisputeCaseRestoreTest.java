package dev.disputeagent.investigator.domain.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.disputeagent.investigator.domain.CaseId;
import dev.disputeagent.investigator.domain.DecisionOutcome;
import dev.disputeagent.investigator.domain.DisputeCategory;
import dev.disputeagent.investigator.domain.approval.ApprovalToken;
import dev.disputeagent.investigator.domain.approval.Review;
import dev.disputeagent.investigator.domain.decision.Confidence;
import dev.disputeagent.investigator.domain.decision.Decision;
import dev.disputeagent.investigator.domain.decision.Money;
import dev.disputeagent.investigator.domain.decision.ProposedAction;
import dev.disputeagent.investigator.domain.guard.GuardResult;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A stored case comes back as it was, and an inconsistent snapshot is refused. */
class DisputeCaseRestoreTest {

    private static final Instant T0 = Instant.parse("2026-11-23T09:00:00Z");
    private static final ProposedAction CREDIT = new ProposedAction.IssueProvisionalCredit(new Money(100, "EUR"));

    private static DisputeCase approved() {
        var c = new DisputeCase(new CaseId("DSP-000018"), DisputeCategory.UNAUTHORISED_TRANSACTION, T0);
        c.triaged(DisputeCategory.UNAUTHORISED_TRANSACTION, T0.plusSeconds(1));
        c.investigationStarted(T0.plusSeconds(2));
        c.decided(new Decision(DecisionOutcome.APPROVE_PROVISIONAL_CREDIT, Confidence.HIGH, "r", List.of("TXN-1"),
                List.of("s"), List.of(CREDIT)), new GuardResult(List.of()), T0.plusSeconds(3));
        var request = c.requestApproval(T0.plusSeconds(4));
        var review = new Review("reviewer-1", "ok", T0.plusSeconds(5));
        c.approve(review, ApprovalToken.issue(UUID.randomUUID(), request, "e".repeat(64), review), T0.plusSeconds(5));
        return c;
    }

    @Test
    void aRestoredCaseHoldsWhatTheOriginalHeldAndCarriesOn() {
        var original = approved();

        var restored = DisputeCase.restore(original.snapshot());

        assertThat(restored.snapshot()).isEqualTo(original.snapshot());
        assertThat(restored.approvalRequest()).isEqualTo(original.approvalRequest());
        restored.acted(T0.plusSeconds(6), "done");
        assertThat(restored.state()).isEqualTo(CaseState.ACTED);
        assertThat(original.state()).as("the original is untouched").isEqualTo(CaseState.APPROVED);
    }

    @Test
    void aStateThatIsNotTheEndOfItsHistoryIsRefused() {
        var s = approved().snapshot();
        var wrong = new DisputeCase.Snapshot(s.id(), s.reportedCategory(), s.category(), CaseState.ACTED, s.decision(),
                s.guardResult(), s.escalationReason(), s.replays(), s.approvalRequestedAt(), s.review(), s.token(),
                s.actionDetail(), s.history());

        assertThatThrownBy(() -> DisputeCase.restore(wrong)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("last transition");
    }

    @Test
    void anApprovedCaseWithoutItsTokenIsRefused() {
        var s = approved().snapshot();
        var noToken = new DisputeCase.Snapshot(s.id(), s.reportedCategory(), s.category(), s.state(), s.decision(),
                s.guardResult(), s.escalationReason(), s.replays(), s.approvalRequestedAt(), s.review(), null,
                s.actionDetail(), s.history());

        assertThatThrownBy(() -> DisputeCase.restore(noToken)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("token");
    }
}
