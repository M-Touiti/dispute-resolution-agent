package dev.disputeagent.investigator.application.intake;

import dev.disputeagent.investigator.domain.CaseId;
import dev.disputeagent.investigator.domain.DisputeCategory;
import dev.disputeagent.investigator.domain.decision.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * A dispute as raised (the {@code disputes.opened} event). The statement and the documents are written by the
 * cardholder or third parties: untrusted text.
 *
 * @param claimedAmount the amount in dispute; for an amount mismatch, the difference
 */
public record DisputeOpened(
        CaseId disputeId,
        String cardholderId,
        String cardId,
        String transactionId,
        DisputeCategory category,
        Instant openedAt,
        Money claimedAmount,
        String cardholderStatement,
        List<Document> documents) {

    public DisputeOpened {
        Objects.requireNonNull(disputeId, "disputeId");
        Objects.requireNonNull(cardholderId, "cardholderId");
        Objects.requireNonNull(cardId, "cardId");
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(openedAt, "openedAt");
        Objects.requireNonNull(claimedAmount, "claimedAmount");
        Objects.requireNonNull(cardholderStatement, "cardholderStatement");
        documents = List.copyOf(documents);
    }

    /** A document attached to the dispute (receipt, order confirmation, delivery record...). */
    public record Document(String documentId, String kind, String issuedBy, LocalDate dated, String summary) {
        public Document {
            Objects.requireNonNull(documentId, "documentId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(issuedBy, "issuedBy");
            Objects.requireNonNull(dated, "dated");
            Objects.requireNonNull(summary, "summary");
        }
    }
}
