package dev.disputeagent.investigator.domain.guard;

import static java.time.temporal.ChronoUnit.DAYS;

import dev.disputeagent.investigator.domain.DecisionOutcome;
import dev.disputeagent.investigator.domain.DisputeCategory;
import dev.disputeagent.investigator.domain.decision.Decision;
import dev.disputeagent.investigator.domain.decision.Money;
import dev.disputeagent.investigator.domain.decision.ProposedAction;
import dev.disputeagent.investigator.domain.decision.ProposedAction.CloseDispute;
import dev.disputeagent.investigator.domain.decision.ProposedAction.IssueProvisionalCredit;
import dev.disputeagent.investigator.domain.decision.ProposedAction.RequestMerchantEvidence;
import dev.disputeagent.investigator.domain.guard.GuardResult.Rule;
import dev.disputeagent.investigator.domain.guard.GuardResult.Violation;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic checks on every decision, independent of the model's prose (data/policy-corpus). The guard does not
 * decide cases; it refuses decisions that would be unsafe or unsupported, and a refused decision escalates.
 *
 * <p>Pure: no clock, no I/O. The same decision and facts always give the same result.
 */
public final class PolicyGuard {

    /**
     * The outcomes each category's policy can reach (data/policy-corpus). A decline (the time limit) and an escalation
     * (rule-order, escalation) are open to every category; no rule credits goods not received, and only goods not
     * received, a cancelled recurring payment and an amount mismatch name evidence to request from the merchant.
     */
    private static final Map<DisputeCategory, Set<DecisionOutcome>> OUTCOMES = Map.of(
            DisputeCategory.UNAUTHORISED_TRANSACTION, EnumSet.of(DecisionOutcome.APPROVE_PROVISIONAL_CREDIT,
                    DecisionOutcome.DECLINE, DecisionOutcome.ESCALATE),
            DisputeCategory.DUPLICATE_CHARGE, EnumSet.of(DecisionOutcome.APPROVE_PROVISIONAL_CREDIT,
                    DecisionOutcome.DECLINE, DecisionOutcome.ESCALATE),
            DisputeCategory.GOODS_NOT_RECEIVED, EnumSet.of(DecisionOutcome.REQUEST_MERCHANT_EVIDENCE,
                    DecisionOutcome.DECLINE, DecisionOutcome.ESCALATE),
            DisputeCategory.CANCELLED_RECURRING, EnumSet.allOf(DecisionOutcome.class),
            DisputeCategory.AMOUNT_MISMATCH, EnumSet.allOf(DecisionOutcome.class));

    private final PolicyLimits limits;

    public PolicyGuard(PolicyLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public GuardResult check(Decision decision, CaseFacts facts) {
        List<Violation> violations = new ArrayList<>();
        DecisionOutcome outcome = decision.outcome();

        // Time limit: a late dispute can only be declined (policy section time-limits).
        long days = DAYS.between(facts.postedOn(), facts.openedOn());
        if (days > limits.timeLimitDays() && outcome != DecisionOutcome.DECLINE) {
            violations.add(new Violation(Rule.OUTSIDE_TIME_LIMIT, "opened " + days + " days after posting (limit "
                    + limits.timeLimitDays() + "); only a decline is allowed, got " + outcome));
        }

        // Each category's policy reaches only some outcomes.
        if (!OUTCOMES.get(facts.category()).contains(outcome)) {
            violations.add(new Violation(Rule.OUTCOME_NOT_IN_CATEGORY, "no rule for " + facts.category()
                    + " leads to " + outcome));
        }

        // Goods not received: the merchant is asked for evidence only once the wait after the expected delivery
        // date has passed (policy section goods-not-received-wait). Without an order confirmation the date is unknown
        // and the rule cannot apply.
        if (facts.category() == DisputeCategory.GOODS_NOT_RECEIVED
                && outcome == DecisionOutcome.REQUEST_MERCHANT_EVIDENCE && facts.expectedDeliveryOn().isPresent()) {
            LocalDate expected = facts.expectedDeliveryOn().get();
            long waited = DAYS.between(expected, facts.openedOn());
            if (waited < limits.goodsNotReceivedWaitDays()) {
                violations.add(new Violation(Rule.BEFORE_GOODS_NOT_RECEIVED_WAIT, "opened " + waited
                        + " days after the expected delivery on " + expected + " (wait "
                        + limits.goodsNotReceivedWaitDays() + "); only a decline is allowed"));
            }
        }

        // Citations: every id must exist for this case; non-escalations must cite records.
        if (decision.citedEvidenceIds().isEmpty() && outcome != DecisionOutcome.ESCALATE) {
            violations.add(new Violation(Rule.NO_EVIDENCE_CITED, outcome + " must cite the records it relies on"));
        }
        List<String> foreign = decision.citedEvidenceIds().stream()
                .filter(id -> !facts.evidenceIdsInCase().contains(id)).distinct().sorted().toList();
        if (!foreign.isEmpty()) {
            violations.add(new Violation(Rule.EVIDENCE_NOT_IN_CASE, "not records of this case: " + foreign));
        }
        if (decision.citedPolicySections().isEmpty()) {
            violations.add(new Violation(Rule.NO_POLICY_CITED, "a decision must cite the policy that decides it"));
        }
        List<String> unknown = decision.citedPolicySections().stream()
                .filter(id -> !facts.knownPolicySections().contains(id)).distinct().sorted().toList();
        if (!unknown.isEmpty()) {
            violations.add(new Violation(Rule.UNKNOWN_POLICY_SECTION, "no such policy sections: " + unknown));
        }

        checkActions(decision, facts, violations);
        return new GuardResult(violations);
    }

    private void checkActions(Decision decision, CaseFacts facts, List<Violation> violations) {
        DecisionOutcome outcome = decision.outcome();
        List<ProposedAction> actions = decision.proposedActions();

        if (actions.size() > 1) {
            violations.add(new Violation(Rule.ACTION_NOT_ALLOWED, "at most one action, got " + actions.size()));
        }
        boolean needsAction = outcome != DecisionOutcome.ESCALATE;
        if (needsAction && actions.isEmpty()) {
            violations.add(new Violation(Rule.ACTION_NOT_ALLOWED, outcome + " requires its action"));
        }
        for (ProposedAction action : actions) {
            if (action.allowedWith() != outcome) {
                violations.add(new Violation(Rule.ACTION_NOT_ALLOWED,
                        action.toolName() + " is not allowed with " + outcome));
            }
            switch (action) {
                case IssueProvisionalCredit credit -> checkCredit(credit, facts, violations);
                case RequestMerchantEvidence request -> {
                    if (request.evidence().isEmpty() || request.note().isBlank()) {
                        violations.add(new Violation(Rule.ACTION_ARGUMENTS_INVALID,
                                "request_merchant_evidence needs at least one evidence kind and a note"));
                    }
                }
                case CloseDispute close -> {
                    if (close.reason().isBlank() || close.policySectionIds().isEmpty()) {
                        violations.add(new Violation(Rule.ACTION_ARGUMENTS_INVALID,
                                "close_dispute needs a reason and the policy sections that support it"));
                    } else if (!decision.citedPolicySections().containsAll(close.policySectionIds())) {
                        violations.add(new Violation(Rule.ACTION_ARGUMENTS_INVALID,
                                "close_dispute cites sections the decision does not: " + close.policySectionIds()));
                    }
                }
            }
        }
    }

    private void checkCredit(IssueProvisionalCredit credit, CaseFacts facts, List<Violation> violations) {
        if (credit.amount().amountMinor() == 0 || credit.amount().isGreaterThan(facts.disputedAmount())) {
            violations.add(new Violation(Rule.CREDIT_ABOVE_DISPUTED_AMOUNT, "credit " + credit.amount()
                    + " must be positive and at most the disputed amount " + facts.disputedAmount()));
        }
        Money ceiling = facts.creditCeiling();
        if (credit.amount().isGreaterThan(ceiling)) {
            violations.add(new Violation(Rule.CREDIT_ABOVE_LEDGER_AMOUNT, "credit " + credit.amount()
                    + " is above what the ledger supports for " + facts.category() + ": " + ceiling));
        }
        if (credit.amount().isGreaterThan(limits.provisionalCreditLimit())) {
            violations.add(new Violation(Rule.CREDIT_ABOVE_LIMIT, "credit " + credit.amount() + " is above the limit "
                    + limits.provisionalCreditLimit() + "; the case must escalate"));
        }
    }
}
