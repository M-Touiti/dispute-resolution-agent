package dev.disputeagent.ledger.store;

import dev.disputeagent.ledger.store.LedgerRecords.Authorisation;
import dev.disputeagent.ledger.store.LedgerRecords.Card;
import dev.disputeagent.ledger.store.LedgerRecords.Cardholder;
import dev.disputeagent.ledger.store.LedgerRecords.Merchant;
import dev.disputeagent.ledger.store.LedgerRecords.Transaction;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-only queries over the {@code ledger} schema. Every query is parameterised and bounded; lists are ordered
 * newest first with the id as tie-breaker, and continue after a keyset cursor rather than an offset.
 */
@Repository
public class LedgerRepository {

    private final JdbcClient jdbc;

    public LedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A position in a newest-first list: the timestamp and id of the last row already returned. */
    public record Cursor(Instant at, String id) {
    }

    /** Optional filters; null means "no filter". Dates are inclusive, in UTC. */
    public record Filters(LocalDate fromDate, LocalDate toDate, String merchantId, String kindOrResponse,
            String authId) {
    }

    public Optional<Cardholder> cardholder(String cardholderId) {
        return jdbc.sql("""
                SELECT cardholder_id, given_name, family_name, city, country, customer_since
                FROM ledger.cardholders WHERE cardholder_id = :id""")
                .param("id", cardholderId)
                .query((rs, n) -> new Cardholder(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getObject(6, LocalDate.class)))
                .optional();
    }

    public Optional<Card> card(String cardId) {
        return jdbc.sql("""
                SELECT card_id, cardholder_id, pan_last4, product, status, issued_on, expiry
                FROM ledger.cards WHERE card_id = :id""")
                .param("id", cardId)
                .query((rs, n) -> new Card(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getObject(6, LocalDate.class), rs.getString(7)))
                .optional();
    }

    public Optional<Merchant> merchant(String merchantId) {
        return jdbc.sql("""
                SELECT merchant_id, name, category, city, country, statement_descriptor, ecommerce,
                       recurring_billing
                FROM ledger.merchants WHERE merchant_id = :id""")
                .param("id", merchantId)
                .query((rs, n) -> new Merchant(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getBoolean(7), rs.getBoolean(8)))
                .optional();
    }

    public Optional<Transaction> transaction(String transactionId) {
        return jdbc.sql(TRANSACTION_COLUMNS + " WHERE transaction_id = :id")
                .param("id", transactionId)
                .query(LedgerRepository::transactionRow)
                .optional();
    }

    /** Up to {@code limit} transactions on the card, newest first, after {@code after} when given. */
    public List<Transaction> transactions(String cardId, Filters f, Cursor after, int limit) {
        var sql = new StringBuilder(TRANSACTION_COLUMNS).append(" WHERE card_id = :card");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("card", cardId);
        appendCommon(sql, params, "transaction_at", "transaction_id", f, after);
        if (f.kindOrResponse() != null) {
            sql.append(" AND kind = :kind");
            params.put("kind", f.kindOrResponse());
        }
        sql.append(" ORDER BY transaction_at DESC, transaction_id DESC LIMIT :limit");
        params.put("limit", limit);
        return jdbc.sql(sql.toString()).params(params).query(LedgerRepository::transactionRow).list();
    }

    /** Up to {@code limit} authorisation attempts on the card, newest first, after {@code after} when given. */
    public List<Authorisation> authorisations(String cardId, Filters f, Cursor after, int limit) {
        var sql = new StringBuilder("""
                SELECT auth_id, card_id, merchant_id, requested_at, amount_minor, currency, entry_mode,
                       three_ds_result, cvv_result, pin_verified, response, decline_reason, fraud_flag
                FROM ledger.authorisations WHERE card_id = :card""");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("card", cardId);
        appendCommon(sql, params, "requested_at", "auth_id", f, after);
        if (f.kindOrResponse() != null) {
            sql.append(" AND response = :response");
            params.put("response", f.kindOrResponse());
        }
        if (f.authId() != null) {
            sql.append(" AND auth_id = :auth");
            params.put("auth", f.authId());
        }
        sql.append(" ORDER BY requested_at DESC, auth_id DESC LIMIT :limit");
        params.put("limit", limit);
        return jdbc.sql(sql.toString()).params(params).query((rs, n) -> new Authorisation(
                rs.getString(1), rs.getString(2), rs.getString(3), instant(rs, 4), rs.getLong(5), rs.getString(6),
                rs.getString(7), rs.getString(8), rs.getString(9), rs.getBoolean(10), rs.getString(11),
                rs.getString(12), rs.getBoolean(13))).list();
    }

    private static final String TRANSACTION_COLUMNS = """
            SELECT transaction_id, kind, card_id, merchant_id, auth_id, transaction_at, posted_on, amount_minor,
                   currency, description, related_transaction_id
            FROM ledger.transactions""";

    private static Transaction transactionRow(ResultSet rs, int n) throws SQLException {
        return new Transaction(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                instant(rs, 6), rs.getObject(7, LocalDate.class), rs.getLong(8), rs.getString(9), rs.getString(10),
                rs.getString(11));
    }

    private static void appendCommon(StringBuilder sql, Map<String, Object> params, String atColumn,
            String idColumn, Filters f, Cursor after) {
        if (f.fromDate() != null) {
            sql.append(" AND ").append(atColumn).append(" >= :from");
            params.put("from", f.fromDate().atStartOfDay().atOffset(ZoneOffset.UTC));
        }
        if (f.toDate() != null) {
            sql.append(" AND ").append(atColumn).append(" < :toExclusive");
            params.put("toExclusive", f.toDate().plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC));
        }
        if (f.merchantId() != null) {
            sql.append(" AND merchant_id = :merchant");
            params.put("merchant", f.merchantId());
        }
        if (after != null) {
            sql.append(" AND (").append(atColumn).append(", ").append(idColumn).append(") < (:afterAt, :afterId)");
            params.put("afterAt", after.at().atOffset(ZoneOffset.UTC));
            params.put("afterId", after.id());
        }
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }
}
