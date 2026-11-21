package dev.disputeagent.investigator.application;

import dev.disputeagent.investigator.domain.CaseId;
import dev.disputeagent.investigator.domain.DisputeCategory;
import dev.disputeagent.investigator.domain.decision.Decision;
import dev.disputeagent.investigator.domain.guard.GuardResult;
import dev.disputeagent.investigator.domain.investigation.CaseState;
import dev.disputeagent.investigator.domain.investigation.DisputeCase;
import dev.disputeagent.investigator.domain.investigation.EscalationReason;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * What a finished investigation produced: stored with the case and published as {@code disputes.decided}. A decided
 * case has a decision; an escalated one has a reason and, if the agent answered, the decision it proposed.
 */
public record CaseOutcome(
        CaseId disputeId,
        CaseState state,
        DisputeCategory reportedCategory,
        DisputeCategory category,
        Decision decision,
        EscalationReason escalationReason,
        List<GuardResult.Violation> guardViolations,
        Instant decidedAt) {

    public CaseOutcome {
        Objects.requireNonNull(disputeId, "disputeId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(reportedCategory, "reportedCategory");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(decidedAt, "decidedAt");
        guardViolations = List.copyOf(guardViolations);
        if (!state.isTerminal()) {
            throw new IllegalArgumentException("an outcome is for a finished case, got " + state);
        }
        if ((state == CaseState.ESCALATED) != (escalationReason != null)) {
            throw new IllegalArgumentException("an escalated case, and only one, has an escalation reason");
        }
        if (state == CaseState.DECIDED && decision == null) {
            throw new IllegalArgumentException("a decided case has a decision");
        }
    }

    public static CaseOutcome of(DisputeCase finished) {
        if (!finished.state().isTerminal()) {
            throw new IllegalStateException("case " + finished.id() + " is still " + finished.state());
        }
        return new CaseOutcome(finished.id(), finished.state(), finished.reportedCategory(), finished.category(),
                finished.decision().orElse(null), finished.escalationReason().orElse(null),
                finished.guardResult().map(GuardResult::violations).orElse(List.of()),
                finished.history().getLast().at());
    }

    /** True when the guard stopped a proposal: the evaluation's unsafe-proposal count. */
    public boolean unsafeProposal() {
        return escalationReason != null && escalationReason.unsafeProposal();
    }
}
