package dev.disputeagent.investigator.application.investigation;

import dev.disputeagent.investigator.application.intake.DisputeOpened;
import dev.disputeagent.investigator.application.port.audit.AuditEvent.Caller;
import dev.disputeagent.investigator.application.port.audit.AuditLog;
import dev.disputeagent.investigator.application.port.json.JsonPort;
import dev.disputeagent.investigator.application.port.mcp.McpServer;
import dev.disputeagent.investigator.application.port.mcp.McpToolCall;
import dev.disputeagent.investigator.application.port.mcp.McpToolResult;
import dev.disputeagent.investigator.domain.DisputeCategory;
import dev.disputeagent.investigator.domain.guard.CaseFacts;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The records that belong to a case, collected by the investigator itself (not by the model) before the loop: the
 * disputed transaction, every transaction and authorisation on the dispute's card, the card, the cardholder, the
 * merchant, and the documents attached to the dispute. The guard accepts citations from this set only.
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

    private final LocalDate postedOn;
    private final Set<String> ids;

    private CaseEvidence(LocalDate postedOn, Set<String> ids) {
        this.postedOn = postedOn;
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
        ids.add(dispute.transactionId());
        ids.addAll(json.valuesOf(txn, "auth_id"));
        ids.addAll(json.valuesOf(txn, "merchant_id"));
        ids.add(dispute.cardId());
        ids.add(dispute.cardholderId());

        ids.addAll(allPages(dispute, tools, json, audit, "search_transactions", List.of("transaction_id", "auth_id")));
        ids.addAll(allPages(dispute, tools, json, audit, "get_authorisations", List.of("auth_id")));
        dispute.documents().forEach(d -> ids.add(d.documentId()));
        return new CaseEvidence(postedOn, ids);
    }

    CaseFacts facts(DisputeOpened dispute, DisputeCategory category, Set<String> policySectionsRetrieved) {
        return new CaseFacts(dispute.disputeId(), category, dispute.claimedAmount(), postedOn,
                LocalDate.ofInstant(dispute.openedAt(), ZoneOffset.UTC), ids, policySectionsRetrieved);
    }

    Set<String> ids() {
        return ids;
    }

    LocalDate postedOn() {
        return postedOn;
    }

    private static Set<String> allPages(DisputeOpened dispute, ToolInvoker tools, JsonPort json, AuditLog audit,
            String tool, List<String> idFields) {
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
