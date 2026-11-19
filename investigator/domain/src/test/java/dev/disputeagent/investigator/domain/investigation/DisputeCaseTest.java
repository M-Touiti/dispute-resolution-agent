package dev.disputeagent.investigator.domain.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.disputeagent.investigator.domain.CaseId;
import dev.disputeagent.investigator.domain.DecisionOutcome;
import dev.disputeagent.investigator.domain.DisputeCategory;
import dev.disputeagent.investigator.domain.decision.Confidence;
import dev.disputeagent.investigator.domain.decision.Decision;
import dev.disputeagent.investigator.domain.guard.GuardResult;
import dev.disputeagent.investigator.domain.guard.GuardResult.Rule;
import dev.disputeagent.investigator.domain.guard.GuardResult.Violation;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DisputeCaseTest {

    private static final Instant T0 = Instant.parse("2026-11-20T09:00:00Z");
    private static final GuardResult PASSED = new GuardResult(List.of());

    private static DisputeCase investigating() {
        var c = new DisputeCase(new CaseId("DSP-000001"), DisputeCategory.UNAUTHORISED_TRANSACTION, T0);
        c.triaged(DisputeCategory.UNAUTHORISED_TRANSACTION, T0.plusSeconds(1));
        c.investigationStarted(T0.plusSeconds(2));
        return c;
    }

    private static Decision decision(DecisionOutcome outcome) {
        return new Decision(outcome, Confidence.HIGH, "r", List.of("TXN-1"), List.of("time-limits"), List.of());
    }

    @Test
    void aPassingDecisionEndsTheInvestigation() {
        var c = investigating();
        c.decided(decision(DecisionOutcome.DECLINE), PASSED, T0.plusSeconds(30));

        assertThat(c.state()).isEqualTo(CaseState.DECIDED);
        assertThat(c.escalationReason()).isEmpty();
        assertThat(c.history()).extracting(DisputeCase.StateChange::to).containsExactly(CaseState.RECEIVED,
                CaseState.TRIAGED, CaseState.INVESTIGATING, CaseState.DECIDED);
    }

    @Test
    void aGuardFailureEscalatesAndCountsAsUnsafe() {
        var c = investigating();
        var failed = new GuardResult(List.of(new Violation(Rule.CREDIT_ABOVE_LIMIT, "too much")));

        c.decided(decision(DecisionOutcome.APPROVE_PROVISIONAL_CREDIT), failed, T0.plusSeconds(30));

        assertThat(c.state()).isEqualTo(CaseState.ESCALATED);
        assertThat(c.escalationReason()).contains(EscalationReason.GUARD_FAILED);
        assertThat(c.escalationReason().orElseThrow().unsafeProposal()).isTrue();
        assertThat(c.decision()).isPresent(); // kept for the reviewer and the audit
    }

    @Test
    void anEscalateOutcomeEscalatesAsTheAgentsDecision() {
        var c = investigating();
        c.decided(decision(DecisionOutcome.ESCALATE), PASSED, T0.plusSeconds(30));

        assertThat(c.state()).isEqualTo(CaseState.ESCALATED);
        assertThat(c.escalationReason()).contains(EscalationReason.AGENT_DECISION);
        assertThat(EscalationReason.AGENT_DECISION.unsafeProposal()).isFalse();
    }

    @Test
    void triageKeepsBothCategoriesWhenTheyDisagree() {
        var c = new DisputeCase(new CaseId("DSP-000002"), DisputeCategory.DUPLICATE_CHARGE, T0);
        c.triaged(DisputeCategory.UNAUTHORISED_TRANSACTION, T0.plusSeconds(1));

        assertThat(c.reportedCategory()).isEqualTo(DisputeCategory.DUPLICATE_CHARGE);
        assertThat(c.category()).isEqualTo(DisputeCategory.UNAUTHORISED_TRANSACTION);
        assertThat(c.history().getLast().note()).contains("UNAUTHORISED_TRANSACTION", "DUPLICATE_CHARGE");
    }

    @Test
    void aCaseCanEscalateBeforeInvestigating() {
        var c = new DisputeCase(new CaseId("DSP-000003"), DisputeCategory.UNAUTHORISED_TRANSACTION, T0);
        c.escalate(EscalationReason.DEPENDENCY_UNAVAILABLE, T0.plusSeconds(1), "triage model unreachable");

        assertThat(c.state()).isEqualTo(CaseState.ESCALATED);
    }

    @Test
    void illegalTransitionsAreRejected() {
        var received = new DisputeCase(new CaseId("DSP-000004"), DisputeCategory.UNAUTHORISED_TRANSACTION, T0);
        assertThatThrownBy(() -> received.investigationStarted(T0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> received.decided(decision(DecisionOutcome.DECLINE), PASSED, T0))
                .isInstanceOf(IllegalStateException.class);

        var decided = investigating();
        decided.decided(decision(DecisionOutcome.DECLINE), PASSED, T0.plusSeconds(30));
        assertThatThrownBy(() -> decided.decided(decision(DecisionOutcome.DECLINE), PASSED, T0))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> decided.escalate(EscalationReason.CAP_REACHED, T0, "late"))
                .isInstanceOf(IllegalStateException.class);
    }
}
