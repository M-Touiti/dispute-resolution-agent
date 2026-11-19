package dev.disputeagent.investigator.domain.guard;

import static org.assertj.core.api.Assertions.assertThat;

import dev.disputeagent.investigator.domain.CaseId;
import dev.disputeagent.investigator.domain.DecisionOutcome;
import dev.disputeagent.investigator.domain.DisputeCategory;
import dev.disputeagent.investigator.domain.decision.Confidence;
import dev.disputeagent.investigator.domain.decision.Decision;
import dev.disputeagent.investigator.domain.decision.EvidenceKind;
import dev.disputeagent.investigator.domain.decision.Money;
import dev.disputeagent.investigator.domain.decision.ProposedAction;
import dev.disputeagent.investigator.domain.decision.ProposedAction.CloseDispute;
import dev.disputeagent.investigator.domain.decision.ProposedAction.IssueProvisionalCredit;
import dev.disputeagent.investigator.domain.decision.ProposedAction.RequestMerchantEvidence;
import dev.disputeagent.investigator.domain.guard.GuardResult.Rule;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PolicyGuardTest {

    private static final PolicyGuard GUARD = new PolicyGuard(PolicyLimits.DEFAULT);
    private static final Set<String> SECTIONS = Set.of("time-limits", "provisional-credit", "rule-order",
            "unauthorised-card-not-present", "unauthorised-chip-and-pin", "unauthorised-3ds-authenticated",
            "goods-not-received-merchant-evidence");
    private static final LocalDate OPENED = LocalDate.of(2026, 10, 30);

    private static CaseFacts facts(long disputedMinor, int daysSincePosting) {
        return new CaseFacts(new CaseId("DSP-000001"), DisputeCategory.UNAUTHORISED_TRANSACTION,
                Money.eur(disputedMinor), OPENED.minusDays(daysSincePosting), OPENED,
                Set.of("TXN-000010", "AUTH-000010", "TXN-000009"), SECTIONS);
    }

    private static Decision decision(DecisionOutcome outcome, List<String> evidence, List<String> sections,
            ProposedAction... actions) {
        return new Decision(outcome, Confidence.HIGH, "because", evidence, sections, List.of(actions));
    }

    private static final List<String> EVIDENCE = List.of("TXN-000010", "AUTH-000010");

    /** One correct decision for each golden-set scenario of the unauthorised-transaction category. */
    @Nested
    class CorrectDecisionsPass {

        @Test
        void approveCardNotPresentFraud() {
            var d = decision(DecisionOutcome.APPROVE_PROVISIONAL_CREDIT, EVIDENCE,
                    List.of("unauthorised-card-not-present"), new IssueProvisionalCredit(Money.eur(42_500)));
            assertThat(GUARD.check(d, facts(42_500, 30)).violations()).isEmpty();
        }

        @Test
        void escalateAboveTheCreditLimitWithoutAnAction() {
            var d = decision(DecisionOutcome.ESCALATE, EVIDENCE, List.of("provisional-credit"));
            assertThat(GUARD.check(d, facts(350_000, 30)).violations()).isEmpty();
        }

        @Test
        void declineChipAndPin() {
            var d = decision(DecisionOutcome.DECLINE, EVIDENCE, List.of("unauthorised-chip-and-pin"),
                    new CloseDispute("Chip and PIN at the till.", List.of("unauthorised-chip-and-pin")));
            assertThat(GUARD.check(d, facts(8_000, 30)).violations()).isEmpty();
        }

        @Test
        void decline3dsAuthenticated() {
            var d = decision(DecisionOutcome.DECLINE, EVIDENCE, List.of("unauthorised-3ds-authenticated"),
                    new CloseDispute("Authenticated.", List.of("unauthorised-3ds-authenticated")));
            assertThat(GUARD.check(d, facts(8_000, 30)).violations()).isEmpty();
        }

        @Test
        void declineOutOfTime() {
            var d = decision(DecisionOutcome.DECLINE, List.of("TXN-000010"), List.of("time-limits"),
                    new CloseDispute("Opened too late.", List.of("time-limits")));
            assertThat(GUARD.check(d, facts(8_000, 140)).violations()).isEmpty();
        }

        @Test
        void requestEvidence() {
            var d = decision(DecisionOutcome.REQUEST_MERCHANT_EVIDENCE, EVIDENCE,
                    List.of("goods-not-received-merchant-evidence"),
                    new RequestMerchantEvidence(List.of(EvidenceKind.PROOF_OF_DELIVERY), "Show the delivery."));
            assertThat(GUARD.check(d, facts(8_000, 30)).violations()).isEmpty();
        }

        @Test
        void creditExactlyAtTheLimitAndExactlyTheDisputedAmount() {
            var d = decision(DecisionOutcome.APPROVE_PROVISIONAL_CREDIT, EVIDENCE,
                    List.of("unauthorised-card-not-present"), new IssueProvisionalCredit(Money.eur(300_000)));
            assertThat(GUARD.check(d, facts(300_000, 120)).passed()).isTrue();
        }
    }

    @Nested
    class Money_ {

        @Test
        void creditAboveTheDisputedAmountIsRefused() {
            var d = decision(DecisionOutcome.APPROVE_PROVISIONAL_CREDIT, EVIDENCE,
                    List.of("unauthorised-card-not-present"), new IssueProvisionalCredit(Money.eur(10_001)));
            assertThat(rules(d, facts(10_000, 30))).containsExactly(Rule.CREDIT_ABOVE_DISPUTED_AMOUNT);
        }

        @Test
        void zeroCreditIsRefused() {
            var d = decision(DecisionOutcome.APPROVE_PROVISIONAL_CREDIT, EVIDENCE,
                    List.of("unauthorised-card-not-present"), new IssueProvisionalCredit(Money.eur(0)));
            assertThat(rules(d, facts(10_000, 30))).containsExactly(Rule.CREDIT_ABOVE_DISPUTED_AMOUNT);
        }

        @Test
        void creditAboveTheLimitIsRefusedEvenWhenItMatchesTheDispute() {
            var d = decision(DecisionOutcome.APPROVE_PROVISIONAL_CREDIT, EVIDENCE,
                    List.of("unauthorised-card-not-present"), new IssueProvisionalCredit(Money.eur(300_001)));
            assertThat(rules(d, facts(300_001, 30))).containsExactly(Rule.CREDIT_ABOVE_LIMIT);
        }
    }

    @Nested
    class TimeLimit {

        @Test
        void approvalOfALateDisputeIsRefused() {
            var d = decision(DecisionOutcome.APPROVE_PROVISIONAL_CREDIT, EVIDENCE,
                    List.of("unauthorised-card-not-present"), new IssueProvisionalCredit(Money.eur(5_000)));
            assertThat(rules(d, facts(5_000, 121))).containsExactly(Rule.OUTSIDE_TIME_LIMIT);
        }

        @Test
        void escalatingALateDisputeIsRefusedToo() {
            var d = decision(DecisionOutcome.ESCALATE, EVIDENCE, List.of("time-limits"));
            assertThat(rules(d, facts(5_000, 200))).containsExactly(Rule.OUTSIDE_TIME_LIMIT);
        }
    }

    @Nested
    class Citations {

        @Test
        void evidenceFromAnotherCaseIsRefused() {
            var d = decision(DecisionOutcome.DECLINE, List.of("TXN-000010", "TXN-999999"),
                    List.of("unauthorised-chip-and-pin"),
                    new CloseDispute("x", List.of("unauthorised-chip-and-pin")));
            GuardResult result = GUARD.check(d, facts(8_000, 30));
            assertThat(rules(result)).containsExactly(Rule.EVIDENCE_NOT_IN_CASE);
            assertThat(result.violations().getFirst().message()).contains("TXN-999999");
        }

        @Test
        void aDecisionWithoutEvidenceIsRefusedButAnEscalationMayHaveNone() {
            var decline = decision(DecisionOutcome.DECLINE, List.of(), List.of("unauthorised-chip-and-pin"),
                    new CloseDispute("x", List.of("unauthorised-chip-and-pin")));
            var escalate = decision(DecisionOutcome.ESCALATE, List.of(), List.of("escalation-is-unknown-here"));

            assertThat(rules(decline, facts(8_000, 30))).containsExactly(Rule.NO_EVIDENCE_CITED);
            assertThat(rules(escalate, facts(8_000, 30))).containsExactly(Rule.UNKNOWN_POLICY_SECTION);
        }

        @Test
        void unknownOrMissingPolicySectionsAreRefused() {
            var unknown = decision(DecisionOutcome.ESCALATE, EVIDENCE, List.of("made-up-section"));
            var none = decision(DecisionOutcome.ESCALATE, EVIDENCE, List.of());

            assertThat(rules(unknown, facts(8_000, 30))).containsExactly(Rule.UNKNOWN_POLICY_SECTION);
            assertThat(rules(none, facts(8_000, 30))).containsExactly(Rule.NO_POLICY_CITED);
        }
    }

    @Nested
    class Actions {

        @Test
        void anActionTheOutcomeDoesNotAllowIsRefused() {
            var d = decision(DecisionOutcome.DECLINE, EVIDENCE, List.of("unauthorised-chip-and-pin"),
                    new IssueProvisionalCredit(Money.eur(100)));
            assertThat(rules(d, facts(8_000, 30))).containsExactly(Rule.ACTION_NOT_ALLOWED);
        }

        @Test
        void anEscalationWithAnActionIsRefused() {
            var d = decision(DecisionOutcome.ESCALATE, EVIDENCE, List.of("provisional-credit"),
                    new IssueProvisionalCredit(Money.eur(100)));
            assertThat(rules(d, facts(8_000, 30))).containsExactly(Rule.ACTION_NOT_ALLOWED);
        }

        @Test
        void aDecisionWithoutItsActionIsRefused() {
            var d = decision(DecisionOutcome.APPROVE_PROVISIONAL_CREDIT, EVIDENCE,
                    List.of("unauthorised-card-not-present"));
            assertThat(rules(d, facts(8_000, 30))).containsExactly(Rule.ACTION_NOT_ALLOWED);
        }

        @Test
        void twoActionsAreRefused() {
            var credit = new IssueProvisionalCredit(Money.eur(100));
            var d = decision(DecisionOutcome.APPROVE_PROVISIONAL_CREDIT, EVIDENCE,
                    List.of("unauthorised-card-not-present"), credit, credit);
            assertThat(rules(d, facts(8_000, 30))).containsExactly(Rule.ACTION_NOT_ALLOWED);
        }

        @Test
        void aCloseThatCitesSectionsTheDecisionDoesNotIsRefused() {
            var d = decision(DecisionOutcome.DECLINE, EVIDENCE, List.of("unauthorised-chip-and-pin"),
                    new CloseDispute("x", List.of("time-limits")));
            assertThat(rules(d, facts(8_000, 30))).containsExactly(Rule.ACTION_ARGUMENTS_INVALID);
        }

        @Test
        void emptyActionArgumentsAreRefused() {
            var request = decision(DecisionOutcome.REQUEST_MERCHANT_EVIDENCE, EVIDENCE,
                    List.of("goods-not-received-merchant-evidence"), new RequestMerchantEvidence(List.of(), " "));
            var close = decision(DecisionOutcome.DECLINE, EVIDENCE, List.of("unauthorised-chip-and-pin"),
                    new CloseDispute(" ", List.of()));

            assertThat(rules(request, facts(8_000, 30))).containsExactly(Rule.ACTION_ARGUMENTS_INVALID);
            assertThat(rules(close, facts(8_000, 30))).containsExactly(Rule.ACTION_ARGUMENTS_INVALID);
        }
    }

    @Test
    void everyBrokenRuleIsReportedNotOnlyTheFirst() {
        var d = decision(DecisionOutcome.APPROVE_PROVISIONAL_CREDIT, List.of("TXN-999999"), List.of("nope"),
                new IssueProvisionalCredit(Money.eur(400_000)));

        assertThat(rules(d, facts(5_000, 200))).containsExactlyInAnyOrder(Rule.OUTSIDE_TIME_LIMIT,
                Rule.EVIDENCE_NOT_IN_CASE, Rule.UNKNOWN_POLICY_SECTION, Rule.CREDIT_ABOVE_DISPUTED_AMOUNT,
                Rule.CREDIT_ABOVE_LIMIT);
    }

    private static List<Rule> rules(Decision d, CaseFacts f) {
        return rules(GUARD.check(d, f));
    }

    private static List<Rule> rules(GuardResult result) {
        return result.violations().stream().map(GuardResult.Violation::rule).toList();
    }
}
