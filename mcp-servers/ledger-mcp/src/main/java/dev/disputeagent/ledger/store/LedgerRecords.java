package dev.disputeagent.ledger.store;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * What the ledger tools return. Field names become snake_case JSON. Amounts are integer minor units (cents) with
 * their currency; timestamps are UTC instants. No card number beyond the last four digits, no cardholder e-mail.
 */
public final class LedgerRecords {

    private LedgerRecords() {
    }

    public record Cardholder(
            String cardholderId, String givenName, String familyName, String city, String country,
            LocalDate customerSince) {
    }

    public record Card(
            String cardId, String cardholderId, String panLast4, String product, String status, LocalDate issuedOn,
            String expiry) {
    }

    public record Merchant(
            String merchantId, String name, String category, String city, String country,
            String statementDescriptor, boolean ecommerce, boolean recurringBilling) {
    }

    public record Transaction(
            String transactionId, String kind, String cardId, String merchantId, String authId,
            Instant transactionAt, LocalDate postedOn, long amountMinor, String currency, String description,
            String relatedTransactionId) {
    }

    public record Authorisation(
            String authId, String cardId, String merchantId, Instant requestedAt, long amountMinor,
            String currency, String entryMode, String threeDsResult, String cvvResult, boolean pinVerified,
            String response, String declineReason, boolean fraudFlag) {
    }

    /** One page of results, newest first; {@code nextPageToken} is null on the last page. */
    public record Page<T>(List<T> items, String nextPageToken) {
    }
}
