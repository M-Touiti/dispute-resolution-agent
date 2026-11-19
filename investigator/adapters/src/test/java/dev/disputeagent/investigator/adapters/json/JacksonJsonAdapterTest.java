package dev.disputeagent.investigator.adapters.json;

import static org.assertj.core.api.Assertions.assertThat;

import dev.disputeagent.investigator.application.port.json.Parsed;
import dev.disputeagent.investigator.application.triage.Triage;
import dev.disputeagent.investigator.domain.DecisionOutcome;
import dev.disputeagent.investigator.domain.DisputeCategory;
import dev.disputeagent.investigator.domain.decision.Confidence;
import dev.disputeagent.investigator.domain.decision.Decision;
import dev.disputeagent.investigator.domain.decision.EvidenceKind;
import dev.disputeagent.investigator.domain.decision.Money;
import dev.disputeagent.investigator.domain.decision.ProposedAction;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JacksonJsonAdapterTest {

    private final JacksonJsonAdapter json = new JacksonJsonAdapter();

    @Test
    void parsesAValidDecisionWithEachKindOfAction() {
        Parsed<Decision> credit = json.decision("""
                {"outcome":"APPROVE_PROVISIONAL_CREDIT","confidence":"HIGH","rationale":"Fraud flag set.",
                 "cited_evidence_ids":["TXN-000100","AUTH-000100"],"cited_policy_sections":["unauthorised-card-not-present"],
                 "proposed_actions":[{"action":"issue_provisional_credit","amount_minor":42500,"currency":"EUR"}]}""");
        Parsed<Decision> evidence = json.decision("""
                {"outcome":"REQUEST_MERCHANT_EVIDENCE","confidence":"MEDIUM","rationale":"r","cited_evidence_ids":["TXN-1"],
                 "cited_policy_sections":["goods-not-received-merchant-evidence"],
                 "proposed_actions":[{"action":"request_merchant_evidence","evidence":["PROOF_OF_DELIVERY"],"note":"n"}]}""");
        Parsed<Decision> close = json.decision("""
                {"outcome":"DECLINE","confidence":"LOW","rationale":"r","cited_evidence_ids":["TXN-1"],
                 "cited_policy_sections":["time-limits"],
                 "proposed_actions":[{"action":"close_dispute","reason":"late","policy_section_ids":["time-limits"]}]}""");

        assertThat(credit.ok()).as(String.valueOf(credit.errors())).isTrue();
        assertThat(credit.value().outcome()).isEqualTo(DecisionOutcome.APPROVE_PROVISIONAL_CREDIT);
        assertThat(credit.value().confidence()).isEqualTo(Confidence.HIGH);
        assertThat(credit.value().proposedActions())
                .containsExactly(new ProposedAction.IssueProvisionalCredit(Money.eur(42_500)));
        assertThat(evidence.value().proposedActions()).containsExactly(
                new ProposedAction.RequestMerchantEvidence(List.of(EvidenceKind.PROOF_OF_DELIVERY), "n"));
        assertThat(close.value().proposedActions())
                .containsExactly(new ProposedAction.CloseDispute("late", List.of("time-limits")));
    }

    @Test
    void schemaViolationsComeBackAsTextForTheModel() {
        Parsed<Decision> parsed = json.decision("""
                {"outcome":"REFUND_EVERYTHING","confidence":"HIGH","rationale":"r","cited_evidence_ids":[],
                 "cited_policy_sections":[],"proposed_actions":[],"extra":1}""");

        assertThat(parsed.ok()).isFalse();
        assertThat(String.join("\n", parsed.errors())).contains("outcome", "extra");
    }

    @Test
    void anActionWithTheWrongShapeIsRejected() {
        Parsed<Decision> parsed = json.decision("""
                {"outcome":"APPROVE_PROVISIONAL_CREDIT","confidence":"HIGH","rationale":"r","cited_evidence_ids":[],
                 "cited_policy_sections":[],"proposed_actions":[{"action":"issue_provisional_credit","amount_minor":"lots",
                 "currency":"EUR"}]}""");

        assertThat(parsed.ok()).isFalse();
    }

    @Test
    void notJsonIsRejectedWithoutAnException() {
        assertThat(json.decision("I think we should refund").errors()).singleElement()
                .satisfies(e -> assertThat(e).startsWith("not valid JSON"));
    }

    @Test
    void triage() {
        Parsed<Triage> ok = json.triage("{\"category\":\"DUPLICATE_CHARGE\",\"reason\":\"charged twice\"}");
        Parsed<Triage> bad = json.triage("{\"category\":\"FRAUD\",\"reason\":\"x\"}");

        assertThat(ok.value()).isEqualTo(new Triage(DisputeCategory.DUPLICATE_CHARGE, "charged twice"));
        assertThat(bad.ok()).isFalse();
    }

    @Test
    void valuesOfFindsAFieldAtAnyDepthInOrderAndIgnoresNulls() {
        String page = """
                {"items":[{"transaction_id":"TXN-2","auth_id":"AUTH-2"},{"transaction_id":"TXN-1","auth_id":null}],
                 "next_page_token":null}""";

        assertThat(json.valuesOf(page, "transaction_id")).containsExactly("TXN-2", "TXN-1");
        assertThat(json.valuesOf(page, "auth_id")).containsExactly("AUTH-2");
        assertThat(json.valuesOf(page, "next_page_token")).isEmpty();
        assertThat(json.valuesOf("NOT_FOUND: no card", "card_id")).isEmpty();
    }

    @Test
    void objectWritesMembersInKeyOrder() {
        Map<String, Object> members = new LinkedHashMap<>();
        members.put("page_size", 50);
        members.put("card_id", "CRD-000042");

        assertThat(json.object(members)).isEqualTo("{\"card_id\":\"CRD-000042\",\"page_size\":50}");
    }
}
