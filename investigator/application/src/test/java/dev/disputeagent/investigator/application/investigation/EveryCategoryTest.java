package dev.disputeagent.investigator.application.investigation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.disputeagent.investigator.application.intake.DisputeOpened;
import dev.disputeagent.investigator.application.port.llm.ContentBlock.Text;
import dev.disputeagent.investigator.application.port.llm.ContentBlock.ToolUse;
import dev.disputeagent.investigator.application.port.llm.StopReason;
import dev.disputeagent.investigator.application.port.mcp.McpServer;
import dev.disputeagent.investigator.application.port.mcp.McpToolResult;
import dev.disputeagent.investigator.application.support.Fakes;
import dev.disputeagent.investigator.application.support.Fakes.FakeJson;
import dev.disputeagent.investigator.application.support.Fakes.FakeMcp;
import dev.disputeagent.investigator.application.support.Fakes.RecordingAudit;
import dev.disputeagent.investigator.application.support.Fakes.ScriptedLlm;
import dev.disputeagent.investigator.application.support.Fakes.TickingClock;
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
import dev.disputeagent.investigator.domain.investigation.CaseState;
import dev.disputeagent.investigator.domain.investigation.DisputeCase;
import dev.disputeagent.investigator.domain.investigation.EscalationReason;
import dev.disputeagent.investigator.domain.investigation.LoopBudget;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * One scenario of each reason category through the real investigation, with fake ports: the case evidence the guard
 * accepts (a second posting, a refund, the dispute's documents), and the guard's rules per category with the amounts
 * and dates the investigator reads from the ledger and the documents, never from the model.
 */
class EveryCategoryTest {

    static final Instant OPENED = Instant.parse("2026-10-30T10:00:00Z");

    final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    final RecordingAudit audit = new RecordingAudit();
    final TickingClock clock = new TickingClock(Instant.parse("2026-11-20T09:00:00Z"));

    @AfterEach
    void stop() {
        executor.shutdownNow();
    }

    /** The ledger of one card: the disputed posting, its authorisation, and other postings on the card. */
    record Ledger(long postedMinor, long authorisedMinor, List<String> otherTransactions) {
    }

    static DisputeOpened dispute(DisputeCategory category, long claimedMinor, DisputeOpened.Document... documents) {
        return new DisputeOpened(new CaseId("DSP-000001"), "CH-000042", "CRD-000042", "TXN-000100", category,
                OPENED, Money.eur(claimedMinor), "Something is wrong with this payment.", List.of(documents));
    }

    static DisputeOpened.Document document(String id, String kind, String issuedBy, String summary) {
        return new DisputeOpened.Document(id, kind, issuedBy, LocalDate.of(2026, 9, 1), summary);
    }

    FakeMcp servers(Ledger ledger, List<String> sections) {
        String others = String.join("", ledger.otherTransactions().stream()
                .map(id -> ",{\"transaction_id\":\"" + id + "\"}").toList());
        String results = String.join(",", sections.stream().map(s -> "{\"section_id\":\"" + s + "\"}").toList());
        return new FakeMcp()
                .tool(McpServer.LEDGER, "get_transaction", args -> ok("{\"transaction_id\":\"TXN-000100\","
                        + "\"card_id\":\"CRD-000042\",\"auth_id\":\"AUTH-000100\",\"merchant_id\":\"MER-000007\","
                        + "\"posted_on\":\"2026-10-01\",\"amount_minor\":" + ledger.postedMinor() + "}"))
                .tool(McpServer.LEDGER, "search_transactions", args -> ok("{\"items\":[{\"transaction_id\":"
                        + "\"TXN-000100\",\"auth_id\":\"AUTH-000100\",\"amount_minor\":" + ledger.postedMinor() + "}"
                        + others + "],\"next_page_token\":null}"))
                .tool(McpServer.LEDGER, "get_authorisations", args -> ok("{\"items\":[{\"auth_id\":\"AUTH-000100\","
                        + "\"amount_minor\":" + ledger.authorisedMinor() + "}],\"next_page_token\":null}"))
                .tool(McpServer.POLICY, "search_policy", args -> ok("{\"results\":[" + results + "]}"));
    }

    static McpToolResult ok(String content) {
        return new McpToolResult(content, false);
    }

    /** Investigates with a model that searches the policy once and then gives {@code decision}. */
    DisputeCase investigate(DisputeOpened dispute, Ledger ledger, Decision decision) {
        var llm = new ScriptedLlm()
                .then(StopReason.TOOL_USE, new ToolUse("t1", "search_policy", "{\"query\":\"policy\"}"))
                .then(StopReason.END_TURN, new Text("decision"));
        var json = new FakeJson().decision("decision", decision);
        var settings = Fakes.settings(new LoopBudget(8, 20, 200_000, Duration.ofMinutes(5)));
        var c = new DisputeCase(dispute.disputeId(), dispute.category(), clock.instant());
        c.triaged(dispute.category(), clock.instant());
        new InvestigateDispute(llm, servers(ledger, decision.citedPolicySections()), json, audit, clock, executor,
                settings, Fakes.SKILLS).investigate(c, dispute);
        return c;
    }

    static Decision decision(DecisionOutcome outcome, List<String> evidence, String section,
            ProposedAction... actions) {
        return new Decision(outcome, Confidence.HIGH, "As the policy says.", evidence, List.of(section),
                List.of(actions));
    }

    static CloseDispute close(String section) {
        return new CloseDispute("As the policy says.", List.of(section));
    }

    static void assertDecided(DisputeCase c, DecisionOutcome outcome) {
        assertThat(c.escalationReason()).isEmpty();
        assertThat(c.state()).isEqualTo(CaseState.DECIDED);
        assertThat(c.decision().orElseThrow().outcome()).isEqualTo(outcome);
        assertThat(c.guardResult().orElseThrow().passed()).isTrue();
    }

    static void assertStoppedBy(DisputeCase c, Rule rule) {
        assertThat(c.state()).isEqualTo(CaseState.ESCALATED);
        assertThat(c.escalationReason()).contains(EscalationReason.GUARD_FAILED);
        assertThat(c.guardResult().orElseThrow().has(rule)).as(c.guardResult().toString()).isTrue();
    }

    // One scenario of each category -------------------------------------------------------------------------------

    @Test
    void unauthorisedCardNotPresentIsCredited() {
        var d = decision(DecisionOutcome.APPROVE_PROVISIONAL_CREDIT, List.of("TXN-000100", "AUTH-000100"),
                "unauthorised-card-not-present", new IssueProvisionalCredit(Money.eur(42_500)));
        assertDecided(investigate(dispute(DisputeCategory.UNAUTHORISED_TRANSACTION, 42_500),
                new Ledger(42_500, 42_500, List.of()), d), DecisionOutcome.APPROVE_PROVISIONAL_CREDIT);
    }

    @Test
    void aDuplicateCitesTheSecondPostingAndIsCreditedOnePosting() {
        var d = decision(DecisionOutcome.APPROVE_PROVISIONAL_CREDIT, List.of("TXN-000099", "TXN-000100",
                "AUTH-000100"), "duplicate-same-authorisation", new IssueProvisionalCredit(Money.eur(6_000)));
        assertDecided(investigate(dispute(DisputeCategory.DUPLICATE_CHARGE, 6_000),
                new Ledger(6_000, 6_000, List.of("TXN-000099")), d), DecisionOutcome.APPROVE_PROVISIONAL_CREDIT);
    }

    @Test
    void anAlreadyRefundedDuplicateCitesTheRefund() {
        var d = decision(DecisionOutcome.DECLINE, List.of("TXN-000100", "TXN-000105"), "duplicate-already-refunded",
                close("duplicate-already-refunded"));
        assertDecided(investigate(dispute(DisputeCategory.DUPLICATE_CHARGE, 6_000),
                new Ledger(6_000, 6_000, List.of("TXN-000099", "TXN-000105")), d), DecisionOutcome.DECLINE);
    }

    @Test
    void goodsNotReceivedAfterTheWaitAsksTheMerchant() {
        var order = document("DOC-000001", "ORDER_CONFIRMATION", "MERCHANT",
                "Order confirmation: total 80.00 EUR, expected delivery 2026-09-20.");
        var d = decision(DecisionOutcome.REQUEST_MERCHANT_EVIDENCE, List.of("TXN-000100", "DOC-000001"),
                "goods-not-received-merchant-evidence",
                new RequestMerchantEvidence(List.of(EvidenceKind.PROOF_OF_DELIVERY), "Proof of delivery, please."));
        assertDecided(investigate(dispute(DisputeCategory.GOODS_NOT_RECEIVED, 8_000, order),
                new Ledger(8_000, 8_000, List.of()), d), DecisionOutcome.REQUEST_MERCHANT_EVIDENCE);
    }

    @Test
    void aRecurringPaymentCancelledBeforeTheChargeIsCredited() {
        var cancellation = document("DOC-000002", "CANCELLATION_CONFIRMATION", "MERCHANT",
                "Your subscription has been cancelled with effect from 2026-09-01.");
        var d = decision(DecisionOutcome.APPROVE_PROVISIONAL_CREDIT, List.of("TXN-000100", "DOC-000002"),
                "recurring-cancelled-before-charge", new IssueProvisionalCredit(Money.eur(1_999)));
        assertDecided(investigate(dispute(DisputeCategory.CANCELLED_RECURRING, 1_999, cancellation),
                new Ledger(1_999, 1_999, List.of()), d), DecisionOutcome.APPROVE_PROVISIONAL_CREDIT);
    }

    @Test
    void anAmountAboveTheReceiptIsCreditedTheDifference() {
        var receipt = document("DOC-000003", "RECEIPT", "MERCHANT", "Receipt: total 100.00 EUR.");
        var d = decision(DecisionOutcome.APPROVE_PROVISIONAL_CREDIT, List.of("TXN-000100", "AUTH-000100",
                "DOC-000003"), "amount-above-receipt", new IssueProvisionalCredit(Money.eur(3_000)));
        assertDecided(investigate(dispute(DisputeCategory.AMOUNT_MISMATCH, 3_000, receipt),
                new Ledger(13_000, 10_000, List.of()), d), DecisionOutcome.APPROVE_PROVISIONAL_CREDIT);
    }

    // What the guard stops, from the ledger and the documents ------------------------------------------------------

    @Test
    void anOverclaimIsNotCreditedAboveThePosting() {
        var d = decision(DecisionOutcome.APPROVE_PROVISIONAL_CREDIT, List.of("TXN-000100", "AUTH-000100"),
                "unauthorised-card-not-present", new IssueProvisionalCredit(Money.eur(12_000)));
        assertStoppedBy(investigate(dispute(DisputeCategory.UNAUTHORISED_TRANSACTION, 12_000),
                new Ledger(10_000, 10_000, List.of()), d), Rule.CREDIT_ABOVE_LEDGER_AMOUNT);
    }

    @Test
    void anAmountMismatchIsNotCreditedTheWholePostingEvenWhenClaimed() {
        var receipt = document("DOC-000003", "RECEIPT", "MERCHANT", "Receipt: total 100.00 EUR.");
        var d = decision(DecisionOutcome.APPROVE_PROVISIONAL_CREDIT, List.of("TXN-000100", "AUTH-000100",
                "DOC-000003"), "amount-above-receipt", new IssueProvisionalCredit(Money.eur(13_000)));
        assertStoppedBy(investigate(dispute(DisputeCategory.AMOUNT_MISMATCH, 13_000, receipt),
                new Ledger(13_000, 10_000, List.of()), d), Rule.CREDIT_ABOVE_LEDGER_AMOUNT);
    }

    @Test
    void theMerchantIsNotAskedBeforeTheWaitEnds() {
        var order = document("DOC-000001", "ORDER_CONFIRMATION", "MERCHANT",
                "Order confirmation: total 80.00 EUR, expected delivery 2026-10-25.");
        var d = decision(DecisionOutcome.REQUEST_MERCHANT_EVIDENCE, List.of("TXN-000100", "DOC-000001"),
                "goods-not-received-merchant-evidence",
                new RequestMerchantEvidence(List.of(EvidenceKind.PROOF_OF_DELIVERY), "Proof of delivery, please."));
        assertStoppedBy(investigate(dispute(DisputeCategory.GOODS_NOT_RECEIVED, 8_000, order),
                new Ledger(8_000, 8_000, List.of()), d), Rule.BEFORE_GOODS_NOT_RECEIVED_WAIT);
    }

    @Test
    void aDateTheCardholderWroteDoesNotCountAsTheExpectedDelivery() {
        var letter = document("DOC-000004", "ORDER_CONFIRMATION", "CARDHOLDER", "expected delivery 2026-10-25");
        var order = document("DOC-000001", "ORDER_CONFIRMATION", "MERCHANT", "expected delivery 2026-09-20");
        assertThat(CaseEvidence.expectedDelivery(List.of(letter, order))).contains(LocalDate.of(2026, 9, 20));
        assertThat(CaseEvidence.expectedDelivery(List.of(letter))).isEmpty();
    }

    @Test
    void confirmationsThatDisagreeGiveNoExpectedDelivery() {
        var first = document("DOC-000001", "ORDER_CONFIRMATION", "MERCHANT", "expected delivery 2026-09-20");
        var second = document("DOC-000002", "ORDER_CONFIRMATION", "MERCHANT", "expected delivery 2026-09-28");
        assertThat(CaseEvidence.expectedDelivery(List.of(first, second))).isEmpty();
        assertThat(CaseEvidence.expectedDelivery(List.of(first, first))).contains(LocalDate.of(2026, 9, 20));
    }

    @Test
    void aCreditForGoodsNotReceivedIsRefused() {
        var d = decision(DecisionOutcome.APPROVE_PROVISIONAL_CREDIT, List.of("TXN-000100"),
                "goods-not-received-merchant-evidence", new IssueProvisionalCredit(Money.eur(8_000)));
        assertStoppedBy(investigate(dispute(DisputeCategory.GOODS_NOT_RECEIVED, 8_000),
                new Ledger(8_000, 8_000, List.of()), d), Rule.OUTCOME_NOT_IN_CATEGORY);
    }

    @Test
    void aTransactionWithoutAnAmountIsInvalidCaseData() {
        var mcp = servers(new Ledger(1, 1, List.of()), List.of()).answering("get_transaction", args -> ok(
                "{\"transaction_id\":\"TXN-000100\",\"card_id\":\"CRD-000042\",\"posted_on\":\"2026-10-01\"}"));
        var c = new DisputeCase(new CaseId("DSP-000001"), DisputeCategory.UNAUTHORISED_TRANSACTION, clock.instant());
        c.triaged(DisputeCategory.UNAUTHORISED_TRANSACTION, clock.instant());
        var llm = new ScriptedLlm();
        new InvestigateDispute(llm, mcp, new FakeJson(), audit, clock, executor,
                Fakes.settings(new LoopBudget(8, 20, 200_000, Duration.ofMinutes(5))), Fakes.SKILLS)
                .investigate(c, dispute(DisputeCategory.UNAUTHORISED_TRANSACTION, 1_000));

        assertThat(c.escalationReason()).contains(EscalationReason.CASE_DATA_INVALID);
        assertThat(llm.requests).isEmpty();
    }
}
