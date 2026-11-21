package dev.disputeagent.investigator.adapters.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.disputeagent.investigator.application.CaseOutcome;
import dev.disputeagent.investigator.application.intake.DisputeOpened;
import dev.disputeagent.investigator.domain.CaseId;
import dev.disputeagent.investigator.domain.DecisionOutcome;
import dev.disputeagent.investigator.domain.DisputeCategory;
import dev.disputeagent.investigator.domain.decision.Confidence;
import dev.disputeagent.investigator.domain.decision.Decision;
import dev.disputeagent.investigator.domain.decision.EvidenceKind;
import dev.disputeagent.investigator.domain.decision.Money;
import dev.disputeagent.investigator.domain.decision.ProposedAction;
import dev.disputeagent.investigator.domain.guard.GuardResult;
import dev.disputeagent.investigator.domain.investigation.CaseState;
import dev.disputeagent.investigator.domain.investigation.EscalationReason;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class DisputeEventsTest {

    static final DisputeOpened OPENED = new DisputeOpened(new CaseId("DSP-000001"), "CH-000042", "CRD-000042",
            "TXN-000100", DisputeCategory.GOODS_NOT_RECEIVED, Instant.parse("2026-10-30T10:00:00Z"), Money.eur(4_250),
            "It never arrived. \"Quotes\" and\nnew lines survive.",
            List.of(new DisputeOpened.Document("DOC-000001", "ORDER_CONFIRMATION", "MERCHANT",
                    LocalDate.parse("2026-09-01"), "Expected delivery 2026-09-08.")));

    @Test
    void disputesOpenedRoundTrips() {
        assertThat(DisputeEvents.opened(DisputeEvents.opened(OPENED))).isEqualTo(OPENED);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "not json",
        "{\"dispute_id\":\"DSP-1\"}",
        "{\"dispute_id\":\"DSP-1\",\"cardholder_id\":\"CH\",\"card_id\":\"C\",\"transaction_id\":\"T\","
                + "\"category\":\"FRAUD\",\"opened_at\":\"2026-10-30T10:00:00Z\",\"claimed_amount_minor\":1,"
                + "\"currency\":\"EUR\",\"cardholder_statement\":\"x\",\"documents\":[]}",
        "{\"dispute_id\":\"DSP-1\",\"cardholder_id\":\"CH\",\"card_id\":\"C\",\"transaction_id\":\"T\","
                + "\"category\":\"DUPLICATE_CHARGE\",\"opened_at\":\"yesterday\",\"claimed_amount_minor\":1,"
                + "\"currency\":\"EUR\",\"cardholder_statement\":\"x\",\"documents\":[]}",
        "{\"dispute_id\":\"DSP-1\",\"cardholder_id\":\"CH\",\"card_id\":\"C\",\"transaction_id\":\"T\","
                + "\"category\":\"DUPLICATE_CHARGE\",\"opened_at\":\"2026-10-30T10:00:00Z\",\"claimed_amount_minor\":1,"
                + "\"currency\":\"USD\",\"cardholder_statement\":\"x\",\"documents\":[]}",
    })
    void aMalformedDisputesOpenedIsRecognisedAsSuch(String payload) {
        assertThatThrownBy(() -> DisputeEvents.opened(payload)).isInstanceOf(DisputeEvents.MalformedEvent.class);
    }

    @Test
    void disputesDecidedRoundTripsWithEveryActionAndIsByteStable() {
        for (ProposedAction action : List.of(
                new ProposedAction.IssueProvisionalCredit(Money.eur(4_250)),
                new ProposedAction.RequestMerchantEvidence(List.of(EvidenceKind.PROOF_OF_DELIVERY), "Show it."),
                new ProposedAction.CloseDispute("Too early.", List.of("goods-not-received-wait")))) {
            var outcome = new CaseOutcome(new CaseId("DSP-000001"), CaseState.DECIDED,
                    DisputeCategory.GOODS_NOT_RECEIVED, DisputeCategory.GOODS_NOT_RECEIVED,
                    new Decision(action.allowedWith(), Confidence.MEDIUM, "r", List.of("TXN-1"),
                            List.of("goods-not-received-wait"), List.of(action)),
                    null, List.of(), Instant.parse("2026-11-20T09:00:05Z"));

            String json = DisputeEvents.decided(outcome);

            assertThat(DisputeEvents.decided(json)).isEqualTo(outcome);
            assertThat(DisputeEvents.decided(DisputeEvents.decided(json))).isEqualTo(json);
        }
    }

    @Test
    void anUnsafeEscalationCarriesTheProposalAndTheViolations() {
        var outcome = new CaseOutcome(new CaseId("DSP-000002"), CaseState.ESCALATED,
                DisputeCategory.UNAUTHORISED_TRANSACTION, DisputeCategory.UNAUTHORISED_TRANSACTION,
                new Decision(DecisionOutcome.APPROVE_PROVISIONAL_CREDIT, Confidence.HIGH, "r", List.of("TXN-1"),
                        List.of("unauthorised-card-not-present"),
                        List.of(new ProposedAction.IssueProvisionalCredit(Money.eur(400_000)))),
                EscalationReason.GUARD_FAILED,
                List.of(new GuardResult.Violation(GuardResult.Rule.CREDIT_ABOVE_LIMIT, "too much")),
                Instant.parse("2026-11-20T09:00:05Z"));

        String json = DisputeEvents.decided(outcome);

        assertThat(json).contains("\"unsafe_proposal\":true", "\"rule\":\"CREDIT_ABOVE_LIMIT\"",
                "\"escalation_reason\":\"GUARD_FAILED\"");
        assertThat(DisputeEvents.decided(json)).isEqualTo(outcome);
    }
}
