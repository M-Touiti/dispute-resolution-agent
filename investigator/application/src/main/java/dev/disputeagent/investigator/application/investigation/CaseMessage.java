package dev.disputeagent.investigator.application.investigation;

import dev.disputeagent.investigator.application.intake.DisputeOpened;
import dev.disputeagent.investigator.domain.DisputeCategory;
import java.util.List;
import java.util.Map;

/**
 * The first user turn of an investigation: the case as data. Everything specific to the case goes here, never in the
 * system prompt, so the system prompt and the tool list stay a stable, cacheable prefix. Text written outside the
 * bank is wrapped as untrusted.
 */
final class CaseMessage {

    private CaseMessage() {
    }

    static String of(DisputeOpened dispute, DisputeCategory category) {
        var sb = new StringBuilder();
        sb.append("Investigate dispute ").append(dispute.disputeId()).append(".\n\n");
        sb.append("- Category recorded when the dispute was opened: ").append(dispute.category()).append('\n');
        if (category != dispute.category()) {
            sb.append("- Category the statement describes, according to triage: ").append(category)
                    .append(". Check which one the records support.\n");
        }
        sb.append("- Cardholder: ").append(dispute.cardholderId()).append('\n');
        sb.append("- Card: ").append(dispute.cardId()).append('\n');
        sb.append("- Disputed transaction: ").append(dispute.transactionId()).append('\n');
        sb.append("- Amount in dispute: ").append(dispute.claimedAmount()).append(" (")
                .append(dispute.claimedAmount().amountMinor()).append(" minor units)\n");
        sb.append("- Opened at: ").append(dispute.openedAt()).append("\n\n");
        sb.append(Untrusted.wrap("untrusted_cardholder_statement", Map.of(), dispute.cardholderStatement()));
        List<DisputeOpened.Document> documents = dispute.documents();
        if (documents.isEmpty()) {
            sb.append("\n\nNo documents are attached to this dispute.");
        }
        for (DisputeOpened.Document d : documents) {
            sb.append("\n\n").append(Untrusted.wrap("untrusted_document", Map.of(
                    "id", d.documentId(), "kind", d.kind(), "issued_by", d.issuedBy(), "dated", d.dated().toString()),
                    d.summary()));
        }
        return sb.toString();
    }

    static String fixDecision(List<String> errors) {
        return "Your answer does not match the decision schema:\n- " + String.join("\n- ", errors)
                + "\nAnswer again with the decision object only.";
    }
}
