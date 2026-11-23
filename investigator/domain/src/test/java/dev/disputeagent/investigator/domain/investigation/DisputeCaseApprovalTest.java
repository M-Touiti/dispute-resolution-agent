package dev.disputeagent.investigator.domain.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.disputeagent.investigator.domain.CaseId;
import dev.disputeagent.investigator.domain.DecisionOutcome;
import dev.disputeagent.investigator.domain.DisputeCategory;
import dev.disputeagent.investigator.domain.approval.ApprovalRequest;
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

/** After the decision: one action, one person, one token, one result (ADR 0005). */
class DisputeCaseApprovalTest {

    private static final Instant T0 = Instant.parse("2026-11-23T09:00:00Z");
    private static final CaseId ID = new CaseId("DSP-000018");
    private static final ProposedAction CREDIT = new ProposedAction.IssueProvisionalCredit(new Money(110056, "EUR"));
    private static final String HASH = "a".repeat(64);

    private static DisputeCase decided() {
        var c = new DisputeCase(ID, DisputeCategory.UNAUTHORISED_TRANSACTION, T0);
        c.triaged(DisputeCategory.UNAUTHORISED_TRANSACTION, T0.plusSeconds(1));
        c.investigationStarted(T0.plusSeconds(2));
        c.decided(new Decision(DecisionOutcome.APPROVE_PROVISIONAL_CREDIT, Confidence.HIGH, "r", List.of("TXN-1"),
                List.of("unauthorised-card-not-present"), List.of(CREDIT)), new GuardResult(List.of()),
                T0.plusSeconds(30));
        return c;
    }

    private static Review review(String reviewer, int second) {
        return new Review(reviewer, "checked", T0.plusSeconds(second));
    }

    private static ApprovalToken token(ApprovalRequest request, Review review) {
        return ApprovalToken.issue(UUID.randomUUID(), request, HASH, review);
    }

    @Test
    void aDecisionWaitsForAPersonThenTheApprovedActionRunsOnce() {
        var c = decided();

        ApprovalRequest request = c.requestApproval(T0.plusSeconds(31));
        assertThat(c.state()).isEqualTo(CaseState.AWAITING_APPROVAL);
        assertThat(request.action()).isEqualTo(CREDIT);
        assertThat(c.approvalRequest()).contains(request);

        Review review = review("reviewer-1", 600);
        ApprovalToken token = token(request, review);
        c.approve(review, token, T0.plusSeconds(600));
        assertThat(c.state()).isEqualTo(CaseState.APPROVED);
        assertThat(c.token()).contains(token);
        assertThat(c.review()).contains(review);

        c.acted(T0.plusSeconds(601), "credited 1100.56 EUR");
        assertThat(c.state()).isEqualTo(CaseState.ACTED);
        assertThat(c.state().isTerminal()).isTrue();
        assertThat(c.actionDetail()).contains("credited 1100.56 EUR");
        assertThat(c.history()).extracting(DisputeCase.StateChange::to).endsWith(CaseState.DECIDED,
                CaseState.AWAITING_APPROVAL, CaseState.APPROVED, CaseState.ACTED);
    }

    @Test
    void aRejectionEndsTheCaseWithNothingDone() {
        var c = decided();
        c.requestApproval(T0.plusSeconds(31));

        c.reject(review("reviewer-1", 600), T0.plusSeconds(600));

        assertThat(c.state()).isEqualTo(CaseState.REJECTED);
        assertThat(c.token()).isEmpty();
        assertThatThrownBy(() -> c.acted(T0.plusSeconds(601), "x")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aFailedActionIsRecordedWithTheServersReason() {
        var c = decided();
        var request = c.requestApproval(T0.plusSeconds(31));
        var review = review("reviewer-1", 600);
        c.approve(review, token(request, review), T0.plusSeconds(600));

        c.actionFailed(T0.plusSeconds(601), "APPROVAL_REJECTED: dispute already closed");

        assertThat(c.state()).isEqualTo(CaseState.ACTION_FAILED);
        assertThat(c.actionDetail()).contains("APPROVAL_REJECTED: dispute already closed");
    }

    @Test
    void aTokenForAnotherCaseOrActionOrReviewerIsRefused() {
        var c = decided();
        var request = c.requestApproval(T0.plusSeconds(31));
        var review = review("reviewer-1", 600);

        var otherCase = token(new ApprovalRequest(new CaseId("DSP-000002"), CREDIT, T0), review);
        var otherAction = token(new ApprovalRequest(ID, new ProposedAction.CloseDispute("r", List.of("x")), T0),
                review);
        var otherReviewer = token(request, review("reviewer-2", 600));

        for (var wrong : List.of(otherCase, otherAction, otherReviewer)) {
            assertThatThrownBy(() -> c.approve(review, wrong, T0.plusSeconds(600)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(c.state()).isEqualTo(CaseState.AWAITING_APPROVAL);
    }

    @Test
    void anExpiredTokenIsRefused() {
        var c = decided();
        var request = c.requestApproval(T0.plusSeconds(31));
        var review = review("reviewer-1", 600);
        var token = token(request, review);

        assertThatThrownBy(() -> c.approve(review, token, token.expiresAt()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("expired");
        assertThat(c.state()).isEqualTo(CaseState.AWAITING_APPROVAL);
    }

    @Test
    void onlyADecidedCaseAsksForApprovalAndOnlyOnce() {
        var c = decided();
        c.requestApproval(T0.plusSeconds(31));

        assertThatThrownBy(() -> c.requestApproval(T0.plusSeconds(32))).isInstanceOf(IllegalStateException.class);

        var escalated = new DisputeCase(ID, DisputeCategory.UNAUTHORISED_TRANSACTION, T0);
        escalated.escalate(EscalationReason.CASE_DATA_INVALID, T0.plusSeconds(1), "x");
        assertThatThrownBy(() -> escalated.requestApproval(T0.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
    }
}
