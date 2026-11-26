package dev.disputeagent.investigator.application.investigation;

import dev.disputeagent.investigator.application.intake.DisputeOpened;
import dev.disputeagent.investigator.application.port.audit.AuditEvent.Caller;
import dev.disputeagent.investigator.application.port.audit.AuditLog;
import dev.disputeagent.investigator.application.port.json.JsonPort;
import dev.disputeagent.investigator.application.port.mcp.McpServer;
import dev.disputeagent.investigator.application.port.mcp.McpToolCall;
import dev.disputeagent.investigator.application.port.mcp.McpToolResult;
import dev.disputeagent.investigator.domain.DisputeCategory;
import dev.disputeagent.investigator.domain.decision.Money;
import dev.disputeagent.investigator.domain.guard.CaseFacts;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The records that belong to a case, collected by the investigator itself (not by the model) before the loop: the
 * disputed transaction, every transaction and authorisation on the dispute's card, the card, the cardholder, the
 * merchant, and the documents attached to the dispute. The guard accepts citations from this set only. It also keeps
 * the amounts and dates the guard's rules need: the posted and authorised amounts of the disputed transaction, and the
 * expected delivery date from the merchant's order confirmation. None of this reaches the model.
 */
final class CaseEvidence {

    /** The records contradict the dispute; the case escalates. */
    static final class InvalidCaseData extends RuntimeException {
        InvalidCaseData(String message) {
            super(message, null, false, false);
        }
    }

    /** A ledger tool answered with an error while the index was being built. */
    static final class LedgerUnavailable extends RuntimeException {
        LedgerUnavailable(String message) {
            super(message, null, false, false);
        }
    }

    private static final int PAGE_SIZE = 50;
    private static final int MAX_PAGES = 40;

    /** As the generator and merchants write it: "expected delivery 2026-08-31" (goods-not-received-wait). */
    private static final Pattern EXPECTED_DELIVERY = Pattern.compile("expected delivery (\\d{4}-\\d{2}-\\d{2})");

    private final LocalDate postedOn;
    private final Money postedAmount;
    private final Optional<Money> authorisedAmount;
    private final Optional<LocalDate> expectedDeliveryOn;
    private final Set<String> ids;

    private CaseEvidence(LocalDate postedOn, Money postedAmount, Optional<Money> authorisedAmount,
            Optional<LocalDate> expectedDeliveryOn, Set<String> ids) {
        this.postedOn = postedOn;
        this.postedAmount = postedAmount;
        this.authorisedAmount = authorisedAmount;
        this.expectedDeliveryOn = expectedDeliveryOn;
        this.ids = Set.copyOf(ids);
    }

    static CaseEvidence collect(DisputeOpened dispute, ToolInvoker tools, JsonPort json, AuditLog audit) {
        Set<String> ids = new TreeSet<>();
        String txn = call(dispute, tools, audit, "get_transaction", json.object(Map.of("transaction_id",
                dispute.transactionId())));
        if (!json.valuesOf(txn, "card_id").contains(dispute.cardId())) {
            throw new InvalidCaseData("transaction " + dispute.transactionId() + " is not on card " + dispute.cardId());
        }
        LocalDate postedOn;
        try {
            postedOn = LocalDate.parse(json.valuesOf(txn, "posted_on").getFirst());
        } catch (RuntimeException e) {
            throw new InvalidCaseData("transaction " + dispute.transactionId() + " has no valid posted_on");
        }
        Money postedAmount = json.numberIn(txn, "transaction_id", dispute.transactionId(), "amount_minor")
                .map(Money::eur)
                .orElseThrow(() -> new InvalidCaseData("transaction " + dispute.transactionId() + " has no amount"));
        List<String> authIds = json.valuesOf(txn, "auth_id");
        ids.add(dispute.transactionId());
        ids.addAll(authIds);
        ids.addAll(json.valuesOf(txn, "merchant_id"));
        ids.add(dispute.cardId());
        ids.add(dispute.cardholderId());

        ids.addAll(allPages(dispute, tools, json, audit, "search_transactions", List.of("transaction_id", "auth_id"),
                page -> { }));
        // The disputed transaction's authorisation is on one of the card's pages: no extra call.
        Money[] authorised = new Money[1];
        ids.addAll(allPages(dispute, tools, json, audit, "get_authorisations", List.of("auth_id"), page -> {
            if (authorised[0] == null && !authIds.isEmpty()) {
                json.numberIn(page, "auth_id", authIds.getFirst(), "amount_minor")
                        .ifPresent(amount -> authorised[0] = Money.eur(amount));
            }
        }));
        dispute.documents().forEach(d -> ids.add(d.documentId()));
        return new CaseEvidence(postedOn, postedAmount, Optional.ofNullable(authorised[0]),
                expectedDelivery(dispute.documents()), ids);
    }

    /**
     * The expected delivery date stated by the merchant's order confirmation. Only a merchant's confirmation counts (a
     * date written by the cardholder is a claim), and only when the dispute's confirmations agree on one date.
     */
    static Optional<LocalDate> expectedDelivery(List<DisputeOpened.Document> documents) {
        Set<LocalDate> dates = new TreeSet<>();
        for (DisputeOpened.Document d : documents) {
            if (!"ORDER_CONFIRMATION".equals(d.kind()) || !"MERCHANT".equals(d.issuedBy())) {
                continue;
            }
            Matcher m = EXPECTED_DELIVERY.matcher(d.summary());
            while (m.find()) {
                try {
                    dates.add(LocalDate.parse(m.group(1)));
                } catch (DateTimeParseException e) {
                    return Optional.empty();
                }
            }
        }
        return dates.size() == 1 ? Optional.of(dates.iterator().next()) : Optional.empty();
    }

    CaseFacts facts(DisputeOpened dispute, DisputeCategory category, Set<String> policySectionsRetrieved) {
        return new CaseFacts(dispute.disputeId(), category, dispute.claimedAmount(), postedAmount, authorisedAmount,
                postedOn, LocalDate.ofInstant(dispute.openedAt(), ZoneOffset.UTC), expectedDeliveryOn, ids,
                policySectionsRetrieved);
    }

    Set<String> ids() {
        return ids;
    }

    LocalDate postedOn() {
        return postedOn;
    }

    private static Set<String> allPages(DisputeOpened dispute, ToolInvoker tools, JsonPort json, AuditLog audit,
            String tool, List<String> idFields, Consumer<String> eachPage) {
        Set<String> found = new TreeSet<>();
        String token = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("card_id", dispute.cardId());
            args.put("page_size", PAGE_SIZE);
            if (token != null) {
                args.put("page_token", token);
            }
            String result = call(dispute, tools, audit, tool, json.object(args));
            idFields.forEach(field -> found.addAll(json.valuesOf(result, field)));
            eachPage.accept(result);
            List<String> next = json.valuesOf(result, "next_page_token");
            if (next.isEmpty()) {
                return found;
            }
            token = next.getFirst();
        }
        throw new InvalidCaseData(tool + " returned more than " + MAX_PAGES + " pages for card " + dispute.cardId());
    }

    private static String call(DisputeOpened dispute, ToolInvoker tools, AuditLog audit, String tool, String args) {
        var invocation = tools.invoke(dispute.disputeId(), Caller.SYSTEM, new McpToolCall(McpServer.LEDGER, tool, args));
        audit.record(invocation.audit());
        McpToolResult result = invocation.result();
        if (result.isError()) {
            if (result.content().startsWith("NOT_FOUND")) {
                throw new InvalidCaseData(tool + ": " + result.content());
            }
            throw new LedgerUnavailable(tool + ": " + result.content());
        }
        return result.content();
    }
}
