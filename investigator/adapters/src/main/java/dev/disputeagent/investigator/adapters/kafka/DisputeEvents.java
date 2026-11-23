package dev.disputeagent.investigator.adapters.kafka;

import dev.disputeagent.investigator.application.CaseOutcome;
import dev.disputeagent.investigator.application.intake.DisputeOpened;
import dev.disputeagent.investigator.domain.CaseId;
import dev.disputeagent.investigator.domain.DecisionOutcome;
import dev.disputeagent.investigator.domain.DisputeCategory;
import dev.disputeagent.investigator.domain.approval.ApprovalRequest;
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
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The JSON of the investigator's topics, version 1, snake_case. Written by hand rather than by reflection so the wire format is
 * explicit and stable when classes change. {@code disputes.decided} is also the form the case store keeps, so a
 * redelivery publishes exactly what was published the first time.
 *
 * <p>{@code disputes.opened}: {@code dispute_id, cardholder_id, card_id, transaction_id, category, opened_at,
 * claimed_amount_minor, currency, cardholder_statement, documents[document_id, kind, issued_by, dated, summary]}.
 */
public final class DisputeEvents {

    public static final int VERSION = 1;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private DisputeEvents() {
    }

    /** A malformed {@code disputes.opened} payload: never retried, sent to {@code disputes.dlq} (ADR 0008). */
    public static final class MalformedEvent extends RuntimeException {
        MalformedEvent(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // disputes.opened --------------------------------------------------------------------------------------------

    public static DisputeOpened opened(String json) {
        try {
            JsonNode n = JSON.readTree(json);
            List<DisputeOpened.Document> documents = new ArrayList<>();
            for (JsonNode d : n.path("documents")) {
                documents.add(new DisputeOpened.Document(text(d, "document_id"), text(d, "kind"),
                        text(d, "issued_by"), LocalDate.parse(text(d, "dated")), text(d, "summary")));
            }
            return new DisputeOpened(new CaseId(text(n, "dispute_id")), text(n, "cardholder_id"), text(n, "card_id"),
                    text(n, "transaction_id"), DisputeCategory.valueOf(text(n, "category")),
                    Instant.parse(text(n, "opened_at")),
                    new Money(n.required("claimed_amount_minor").asLong(), text(n, "currency")),
                    text(n, "cardholder_statement"), documents);
        } catch (JacksonException | IllegalArgumentException | NullPointerException
                 | java.time.format.DateTimeParseException e) {
            throw new MalformedEvent("not a valid disputes.opened event: " + e.getMessage(), e);
        }
    }

    public static String opened(DisputeOpened d) {
        ObjectNode n = JSON.createObjectNode();
        n.put("version", VERSION);
        n.put("dispute_id", d.disputeId().value());
        n.put("cardholder_id", d.cardholderId());
        n.put("card_id", d.cardId());
        n.put("transaction_id", d.transactionId());
        n.put("category", d.category().name());
        n.put("opened_at", d.openedAt().toString());
        n.put("claimed_amount_minor", d.claimedAmount().amountMinor());
        n.put("currency", d.claimedAmount().currency());
        n.put("cardholder_statement", d.cardholderStatement());
        ArrayNode documents = n.putArray("documents");
        for (DisputeOpened.Document doc : d.documents()) {
            documents.addObject().put("document_id", doc.documentId()).put("kind", doc.kind())
                    .put("issued_by", doc.issuedBy()).put("dated", doc.dated().toString())
                    .put("summary", doc.summary());
        }
        return JSON.writeValueAsString(n);
    }

    // disputes.decided -------------------------------------------------------------------------------------------

    public static String decided(CaseOutcome o) {
        ObjectNode n = JSON.createObjectNode();
        n.put("version", VERSION);
        n.put("dispute_id", o.disputeId().value());
        n.put("state", o.state().name());
        n.put("decided_at", o.decidedAt().toString());
        n.put("reported_category", o.reportedCategory().name());
        n.put("category", o.category().name());
        n.put("escalation_reason", o.escalationReason() == null ? null : o.escalationReason().name());
        n.put("unsafe_proposal", o.unsafeProposal());
        Decision d = o.decision();
        if (d == null) {
            n.putNull("decision");
        } else {
            ObjectNode dn = n.putObject("decision");
            dn.put("outcome", d.outcome().name());
            dn.put("confidence", d.confidence().name());
            dn.put("rationale", d.rationale());
            d.citedEvidenceIds().forEach(dn.putArray("cited_evidence_ids")::add);
            d.citedPolicySections().forEach(dn.putArray("cited_policy_sections")::add);
            ArrayNode actions = dn.putArray("proposed_actions");
            d.proposedActions().forEach(a -> action(actions.addObject(), a));
        }
        ArrayNode violations = n.putArray("guard_violations");
        o.guardViolations().forEach(v -> violations.addObject().put("rule", v.rule().name()).put("message", v.message()));
        return JSON.writeValueAsString(n);
    }

    public static CaseOutcome decided(String json) {
        JsonNode n = JSON.readTree(json);
        Decision decision = null;
        JsonNode dn = n.get("decision");
        if (dn != null && !dn.isNull()) {
            List<ProposedAction> actions = new ArrayList<>();
            for (JsonNode a : dn.get("proposed_actions")) {
                actions.add(switch (a.get("action").asString()) {
                    case "issue_provisional_credit" -> new ProposedAction.IssueProvisionalCredit(
                            new Money(a.get("amount_minor").asLong(), a.get("currency").asString()));
                    case "request_merchant_evidence" -> new ProposedAction.RequestMerchantEvidence(
                            strings(a.path("evidence")).stream().map(EvidenceKind::valueOf).toList(),
                            a.get("note").asString());
                    case "close_dispute" -> new ProposedAction.CloseDispute(a.get("reason").asString(),
                            strings(a.get("policy_section_ids")));
                    default -> throw new IllegalArgumentException("unknown action " + a.get("action"));
                });
            }
            decision = new Decision(DecisionOutcome.valueOf(dn.get("outcome").asString()),
                    Confidence.valueOf(dn.get("confidence").asString()), dn.get("rationale").asString(),
                    strings(dn.get("cited_evidence_ids")), strings(dn.get("cited_policy_sections")), actions);
        }
        List<GuardResult.Violation> violations = new ArrayList<>();
        for (JsonNode v : n.get("guard_violations")) {
            violations.add(new GuardResult.Violation(GuardResult.Rule.valueOf(v.get("rule").asString()),
                    v.get("message").asString()));
        }
        JsonNode reason = n.get("escalation_reason");
        return new CaseOutcome(new CaseId(n.get("dispute_id").asString()), CaseState.valueOf(n.get("state").asString()),
                DisputeCategory.valueOf(n.get("reported_category").asString()),
                DisputeCategory.valueOf(n.get("category").asString()), decision,
                reason == null || reason.isNull() ? null : EscalationReason.valueOf(reason.asString()), violations,
                Instant.parse(n.get("decided_at").asString()));
    }

    // disputes.approval-requested --------------------------------------------------------------------------------

    /**
     * A decided case's action waits for a person (ADR 0005): {@code version, dispute_id, requested_at, action}, the
     * action in the same shape as in {@code disputes.decided}.
     */
    public static String approvalRequested(ApprovalRequest r) {
        ObjectNode n = JSON.createObjectNode();
        n.put("version", VERSION);
        n.put("dispute_id", r.caseId().value());
        n.put("requested_at", r.requestedAt().toString());
        action(n.putObject("action"), r.action());
        return JSON.writeValueAsString(n);
    }

    private static void action(ObjectNode an, ProposedAction a) {
        an.put("action", a.toolName());
        switch (a) {
            case ProposedAction.IssueProvisionalCredit c ->
                    an.put("amount_minor", c.amount().amountMinor()).put("currency", c.amount().currency());
            case ProposedAction.RequestMerchantEvidence r -> {
                r.evidence().forEach(k -> an.withArray("evidence").add(k.name()));
                an.put("note", r.note());
            }
            case ProposedAction.CloseDispute c -> {
                an.put("reason", c.reason());
                c.policySectionIds().forEach(an.putArray("policy_section_ids")::add);
            }
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.required(field);
        if (!value.isString()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return value.asString();
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(v -> out.add(v.asString()));
        return out;
    }
}
