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
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PolicyGuardTest {

    private static final PolicyGuard GUARD = new PolicyGuard(PolicyLimits.DEFAULT);
    private static final Set<String> SECTIONS = Set.of("time-limits", "provisional-credit", "rule-order",
            "unauthorised-card-not-present", "unauthorised-chip-and-pin", "unauthorised-3ds-authenticated",
            "goods-not-received-merchant-evidence", "goods-not-received-wait", "goods-not-received-proof-of-delivery",
            "duplicate-same-authorisation", "duplicate-separate-authorisations", "duplicate-already-refunded",
            "recurring-cancelled-before-charge", "recurring-cancelled-after-charge", "recurring-no-proof",
            "amount-above-receipt", "amount-restaurant-tip", "amount-no-receipt");
    private static final LocalDate OPENED = LocalDate.of(2026, 10, 30);

    /** An unauthorised-transaction case where the ledger agrees with the claim. */
    private static CaseFacts facts(long disputedMinor, int daysSincePosting) {
        return facts(DisputeCategory.UNAUTHORISED_TRANSACTION, disputedMinor, disputedMinor, disputedMinor,
                daysSincePosting, null);
    }

    private static CaseFacts facts(DisputeCategory category, long claimedMinor, long postedMinor, Long authorisedMinor,
            int daysSincePosting, LocalDate expectedDelivery) {
        return new CaseFacts(new CaseId("DSP-000001"), category, Money.eur(claimedMinor), Money.eur(postedMinor),
                Optional.ofNullable(authorisedMinor).map(Money::eur), OPENED.minusDays(daysSincePosting), OPENED,
                Optional.ofNullable(expectedDelivery), Set.of("TXN-000010", "AUTH-000010", "TXN-000009", "DOC-000001"),
                SECTIONS);
    }

    private static CaseFacts goodsNotReceived(int daysAfterExpectedDelivery) {
        return facts(DisputeCategory.GOODS_NOT_RECEIVED, 8_000, 8_000, 8_000L, 40,
                OPENED.minusDays(daysAfterExpectedDelivery));
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
            assertThat(GUARD.check(d, goodsNotReceived(30)).violations()).isEmpty();
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
            var claimBelowThePosting = facts(DisputeCategory.UNAUTHORISED_TRANSACTION, 10_000, 20_000, 20_000L, 30,
                    null);
            assertThat(rules(d, claimBelowThePosting)).containsExactly(Rule.CREDIT_ABOVE_DISPUTED_AMOUNT);
        }

        @Test
        void creditAboveThePostingIsRefusedWhateverTheClaim() {
            var d = decision(DecisionOutcome.APPROVE_PROVISIONAL_CREDIT, EVIDENCE,
                    List.of("unauthorised-card-not-present"), new IssueProvisionalCredit(Money.eur(12_000)));
            var overclaim = facts(DisputeCategory.UNAUTHORISED_TRANSACTION, 12_000, 10_000, 10_000L, 30, null);
            assertThat(rules(d, overclaim)).containsExactly(Rule.CREDIT_ABOVE_LEDGER_AMOUNT);
        }

        @Test
        void anAmountMismatchIsCreditedTheDifferenceNotThePosting() {
            var whole = decision(DecisionOutcome.APPROVE_PROVISIONAL_CREDIT, EVIDENCE,
                    List.of("amount-above-receipt"), new IssueProvisionalCredit(Money.eur(15_000)));
            var difference = decision(DecisionOutcome.APPROVE_PROVISIONAL_CREDIT, EVIDENCE,
                    List.of("amount-above-receipt"), new IssueProvisionalCredit(Money.eur(5_000)));
            var claimsTheWhole = facts(DisputeCategory.AMOUNT_MISMATCH, 15_000, 15_000, 10_000L, 30, null);

            assertThat(rules(whole, claimsTheWhole)).containsExactly(Rule.CREDIT_ABOVE_LEDGER_AMOUNT);
            assertThat(rules(difference, claimsTheWhole)).isEmpty();
        }

        @Test
        void anAmountMismatchWithoutAnExcessOverTheAuthorisationSupportsNoCredit() {
            var d = decision(DecisionOutcome.APPROVE_PROVISIONAL_CREDIT, EVIDENCE,
                    List.of("amount-above-receipt"), new IssueProvisionalCredit(Money.eur(1_000)));
            var noExcess = facts(DisputeCategory.AMOUNT_MISMATCH, 1_000, 10_000, 10_000L, 30, null);
            var noAuthorisation = facts(DisputeCategory.AMOUNT_MISMATCH, 1_000, 10_000, null, 30, null);

            assertThat(rules(d, noExcess)).containsExactly(Rule.CREDIT_ABOVE_LEDGER_AMOUNT);
            assertThat(rules(d, noAuthorisation)).containsExactly(Rule.CREDIT_ABOVE_LEDGER_AMOUNT);
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

            assertThat(rules(request, goodsNotReceived(30))).containsExactly(Rule.ACTION_ARGUMENTS_INVALID);
            assertThat(rules(close, facts(8_000, 30))).containsExactly(Rule.ACTION_ARGUMENTS_INVALID);
        }
    }

    /** One correct decision for each scenario of the other four categories (data/datagen/policy.py): none is refused. */
    @Nested
    class EveryCategory {

        private static CloseDispute close(String section) {
            return new CloseDispute("As the policy says.", List.of(section));
        }

        @Test
        void duplicateCharge() {
            var facts = facts(DisputeCategory.DUPLICATE_CHARGE, 6_000, 6_000, 6_000L, 30, null);
            var approve = decision(DecisionOutcome.APPROVE_PROVISIONAL_CREDIT, List.of("TXN-000010", "TXN-000009",
                    "AUTH-000010"), List.of("duplicate-same-authorisation"), new IssueProvisionalCredit(Money.eur(6_000)));
            var twoPurchases = decision(DecisionOutcome.DECLINE, EVIDENCE,
                    List.of("duplicate-separate-authorisations"), close("duplicate-separate-authorisations"));
            var refunded = decision(DecisionOutcome.DECLINE, List.of("TXN-000010", "TXN-000009"),
                    List.of("duplicate-already-refunded"), close("duplicate-already-refunded"));

            assertThat(rules(approve, facts)).isEmpty();
            assertThat(rules(twoPurchases, facts)).isEmpty();
            assertThat(rules(refunded, facts)).isEmpty();
        }

        @Test
        void goodsNotReceivedCases() {
            var request = decision(DecisionOutcome.REQUEST_MERCHANT_EVIDENCE, List.of("TXN-000010", "DOC-000001"),
                    List.of("goods-not-received-merchant-evidence"),
                    new RequestMerchantEvidence(List.of(EvidenceKind.PROOF_OF_DELIVERY), "Proof of delivery."));
            var tooEarly = decision(DecisionOutcome.DECLINE, List.of("TXN-000010", "DOC-000001"),
                    List.of("goods-not-received-wait"), close("goods-not-received-wait"));
            var delivered = decision(DecisionOutcome.DECLINE, List.of("TXN-000010", "DOC-000001"),
                    List.of("goods-not-received-proof-of-delivery"), close("goods-not-received-proof-of-delivery"));

            assertThat(rules(request, goodsNotReceived(40))).isEmpty();
            assertThat(rules(tooEarly, goodsNotReceived(5))).isEmpty();
            assertThat(rules(delivered, goodsNotReceived(40))).isEmpty();
        }

        @Test
        void cancelledRecurring() {
            var facts = facts(DisputeCategory.CANCELLED_RECURRING, 1_999, 1_999, 1_999L, 30, null);
            var approve = decision(DecisionOutcome.APPROVE_PROVISIONAL_CREDIT, List.of("TXN-000010", "DOC-000001"),
                    List.of("recurring-cancelled-before-charge"), new IssueProvisionalCredit(Money.eur(1_999)));
            var after = decision(DecisionOutcome.DECLINE, List.of("TXN-000010", "DOC-000001"),
                    List.of("recurring-cancelled-after-charge"), close("recurring-cancelled-after-charge"));
            var noProof = decision(DecisionOutcome.REQUEST_MERCHANT_EVIDENCE, List.of("TXN-000010"),
                    List.of("recurring-no-proof"), new RequestMerchantEvidence(
                            List.of(EvidenceKind.CANCELLATION_POLICY), "Your cancellation records."));

            assertThat(rules(approve, facts)).isEmpty();
            assertThat(rules(after, facts)).isEmpty();
            assertThat(rules(noProof, facts)).isEmpty();
        }

        @Test
        void amountMismatch() {
            var aboveReceipt = facts(DisputeCategory.AMOUNT_MISMATCH, 3_000, 13_000, 10_000L, 30, null);
            var approve = decision(DecisionOutcome.APPROVE_PROVISIONAL_CREDIT, List.of("TXN-000010", "AUTH-000010",
                    "DOC-000001"), List.of("amount-above-receipt"), new IssueProvisionalCredit(Money.eur(3_000)));
            var tip = decision(DecisionOutcome.DECLINE, EVIDENCE, List.of("amount-restaurant-tip"),
                    close("amount-restaurant-tip"));
            var noReceipt = decision(DecisionOutcome.REQUEST_MERCHANT_EVIDENCE, List.of("TXN-000010"),
                    List.of("amount-no-receipt"), new RequestMerchantEvidence(
                            List.of(EvidenceKind.RECEIPT), "The receipt or order details."));

            assertThat(rules(approve, aboveReceipt)).isEmpty();
            assertThat(rules(tip, aboveReceipt)).isEmpty();
            assertThat(rules(noReceipt, facts(DisputeCategory.AMOUNT_MISMATCH, 2_000, 9_000, 9_000L, 30, null)))
                    .isEmpty();
        }

        @Test
        void anEscalationIsOpenToEveryCategory() {
            for (DisputeCategory category : DisputeCategory.values()) {
                var d = decision(DecisionOutcome.ESCALATE, EVIDENCE, List.of("provisional-credit"));
                assertThat(rules(d, facts(category, 5_000, 5_000, 5_000L, 30, null))).as(category.name()).isEmpty();
            }
        }
    }

    @Nested
    class Categories {

        @Test
        void noRuleCreditsGoodsNotReceived() {
            var d = decision(DecisionOutcome.APPROVE_PROVISIONAL_CREDIT, EVIDENCE,
                    List.of("goods-not-received-merchant-evidence"), new IssueProvisionalCredit(Money.eur(8_000)));
            assertThat(rules(d, goodsNotReceived(40))).containsExactly(Rule.OUTCOME_NOT_IN_CATEGORY);
        }

        @Test
        void noRuleAsksTheMerchantAboutAnUnauthorisedTransactionOrADuplicate() {
            var d = decision(DecisionOutcome.REQUEST_MERCHANT_EVIDENCE, EVIDENCE,
                    List.of("unauthorised-card-not-present"),
                    new RequestMerchantEvidence(List.of(EvidenceKind.PROOF_OF_DELIVERY), "Who made it?"));
            assertThat(rules(d, facts(8_000, 30))).containsExactly(Rule.OUTCOME_NOT_IN_CATEGORY);
            assertThat(rules(d, facts(DisputeCategory.DUPLICATE_CHARGE, 8_000, 8_000, 8_000L, 30, null)))
                    .containsExactly(Rule.OUTCOME_NOT_IN_CATEGORY);
        }
    }

    @Nested
    class GoodsNotReceivedWait {

        private final Decision request = decision(DecisionOutcome.REQUEST_MERCHANT_EVIDENCE, EVIDENCE,
                List.of("goods-not-received-merchant-evidence"),
                new RequestMerchantEvidence(List.of(EvidenceKind.PROOF_OF_DELIVERY), "Proof of delivery."));

        @Test
        void theMerchantIsAskedOnlyOnceTheWaitHasPassed() {
            assertThat(rules(request, goodsNotReceived(14))).containsExactly(Rule.BEFORE_GOODS_NOT_RECEIVED_WAIT);
            assertThat(rules(request, goodsNotReceived(15))).isEmpty();
        }

        @Test
        void withoutAnExpectedDeliveryDateTheRuleCannotApply() {
            var unknownDate = facts(DisputeCategory.GOODS_NOT_RECEIVED, 8_000, 8_000, 8_000L, 40, null);
            assertThat(rules(request, unknownDate)).isEmpty();
        }
    }

    @Test
    void everyBrokenRuleIsReportedNotOnlyTheFirst() {
        var d = decision(DecisionOutcome.APPROVE_PROVISIONAL_CREDIT, List.of("TXN-999999"), List.of("nope"),
                new IssueProvisionalCredit(Money.eur(400_000)));

        assertThat(rules(d, facts(5_000, 200))).containsExactlyInAnyOrder(Rule.OUTSIDE_TIME_LIMIT,
                Rule.EVIDENCE_NOT_IN_CASE, Rule.UNKNOWN_POLICY_SECTION, Rule.CREDIT_ABOVE_DISPUTED_AMOUNT,
                Rule.CREDIT_ABOVE_LEDGER_AMOUNT, Rule.CREDIT_ABOVE_LIMIT);
    }

    private static List<Rule> rules(Decision d, CaseFacts f) {
        return rules(GUARD.check(d, f));
    }

    private static List<Rule> rules(GuardResult result) {
        return result.violations().stream().map(GuardResult.Violation::rule).toList();
    }
}
