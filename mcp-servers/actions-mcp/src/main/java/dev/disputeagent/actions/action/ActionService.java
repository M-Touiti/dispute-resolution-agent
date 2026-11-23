package dev.disputeagent.actions.action;

import dev.disputeagent.actions.token.ApprovalVerifier;
import dev.disputeagent.actions.token.ApprovalVerifier.Accepted;
import dev.disputeagent.actions.token.ApprovalVerifier.Claims;
import dev.disputeagent.actions.token.ApprovalVerifier.Rejected;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Carries an approved action out, once (ADR 0005, {@code docs/approval-token.md}).
 *
 * <ol>
 *   <li>The token is verified without the database (checks 1 to 9): a refusal changes nothing.</li>
 *   <li>One transaction, holding a lock on the dispute so two calls for one dispute run one after the other:
 *     <ol>
 *       <li>A token already used returns the stored result of its first call and writes nothing (the token id is the
 *           idempotency key). This comes before the limits, so a retry is answered from the record even when its own
 *           first call closed the dispute.</li>
 *       <li>Business limits on this server's own reading of the data: the dispute exists and is not closed; credits
 *           are positive, in the dispute's currency, and in total never above the disputed amount; an evidence list is
 *           not empty and has no duplicates; a closure gives a reason and at least one policy section.</li>
 *       <li>The action and its token id are recorded together, with the result returned.</li>
 *     </ol>
 *   </li>
 * </ol>
 */
public final class ActionService {

    /** What the tool answers: {@code ok} when the action was carried out now or by an earlier call with the token. */
    public record Outcome(boolean ok, String text) {
    }

    /** Refusals that come from this server's data, after the token itself was accepted. */
    enum Limit { UNKNOWN_DISPUTE, DISPUTE_CLOSED, LIMIT_EXCEEDED, INVALID_ARGUMENTS }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ApprovalVerifier verifier;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ActionService(ApprovalVerifier verifier, JdbcClient jdbc, TransactionTemplate transactions, Clock clock) {
        this.verifier = verifier;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.clock = clock;
    }

    public Outcome execute(String tool, JsonNode arguments) {
        var verdict = verifier.verify(tool, arguments, clock.instant());
        if (verdict instanceof Rejected r) {
            return rejected(r.reason().name(), r.detail());
        }
        Claims claims = ((Accepted) verdict).claims();
        return transactions.execute(status -> {
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:dispute, 0))")
                    .param("dispute", claims.disputeId()).query(Object.class).single();

            Optional<String> earlier = jdbc.sql("SELECT result FROM actions.actions WHERE token_id = :id")
                    .param("id", claims.tokenId()).query(String.class).optional();
            if (earlier.isPresent()) {
                return new Outcome(true, earlier.get());
            }

            var refusal = limits(tool, claims.disputeId(), arguments);
            if (refusal != null) {
                return refusal;
            }

            String result = result(tool, claims, arguments);
            jdbc.sql("""
                    INSERT INTO actions.actions (token_id, dispute_id, tool, arguments, arguments_sha256,
                        amount_minor, reviewer, key_id, result)
                    VALUES (:id, :dispute, :tool, CAST(:arguments AS jsonb), :hash, :amount, :reviewer, :kid, :result)""")
                    .param("id", claims.tokenId())
                    .param("dispute", claims.disputeId())
                    .param("tool", tool)
                    .param("arguments", JSON.writeValueAsString(withoutToken(arguments)))
                    .param("hash", claims.argumentsSha256())
                    .param("amount", tool.equals("issue_provisional_credit")
                            ? arguments.get("amount_minor").longValue() : null)
                    .param("reviewer", claims.reviewer())
                    .param("kid", claims.keyId())
                    .param("result", result)
                    .update();
            return new Outcome(true, result);
        });
    }

    /** Null when the action may run. */
    private Outcome limits(String tool, String disputeId, JsonNode arguments) {
        record Dispute(long claimedMinor, String currency) {
        }
        Optional<Dispute> dispute = jdbc.sql("""
                SELECT claimed_amount_minor, currency FROM intake.disputes WHERE dispute_id = :id""")
                .param("id", disputeId)
                .query((rs, n) -> new Dispute(rs.getLong(1), rs.getString(2)))
                .optional();
        if (dispute.isEmpty()) {
            return rejected(Limit.UNKNOWN_DISPUTE, "no dispute " + disputeId);
        }
        boolean closed = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM actions.actions WHERE dispute_id = :id AND tool = 'close_dispute')""")
                .param("id", disputeId).query(Boolean.class).single();
        if (closed) {
            return rejected(Limit.DISPUTE_CLOSED, disputeId + " is closed");
        }
        switch (tool) {
            case "issue_provisional_credit" -> {
                long amount = arguments.get("amount_minor").longValue();
                if (amount <= 0) {
                    return rejected(Limit.INVALID_ARGUMENTS, "a credit must be positive");
                }
                if (!dispute.get().currency().equals(arguments.get("currency").stringValue())) {
                    return rejected(Limit.INVALID_ARGUMENTS, "the dispute is in " + dispute.get().currency());
                }
                long credited = jdbc.sql("""
                        SELECT COALESCE(SUM(amount_minor), 0) FROM actions.actions
                        WHERE dispute_id = :id AND tool = 'issue_provisional_credit'""")
                        .param("id", disputeId).query(Long.class).single();
                if (credited + amount > dispute.get().claimedMinor()) {
                    return rejected(Limit.LIMIT_EXCEEDED, money(credited) + " already credited, " + money(amount)
                            + " more would exceed the disputed " + money(dispute.get().claimedMinor()));
                }
            }
            case "request_merchant_evidence" -> {
                JsonNode evidence = arguments.get("evidence");
                Set<String> seen = new HashSet<>();
                evidence.forEach(e -> seen.add(e.stringValue()));
                if (evidence.isEmpty() || seen.size() != evidence.size()) {
                    return rejected(Limit.INVALID_ARGUMENTS, "evidence must list at least one item, each once");
                }
            }
            case "close_dispute" -> {
                if (arguments.get("reason").stringValue().isBlank() || arguments.get("policy_section_ids").isEmpty()) {
                    return rejected(Limit.INVALID_ARGUMENTS, "a closure needs a reason and a policy section");
                }
            }
            default -> throw new IllegalArgumentException("unknown tool " + tool);
        }
        return null;
    }

    /** The answer stored with the action and returned to every call with its token. */
    private static String result(String tool, Claims claims, JsonNode arguments) {
        String summary = switch (tool) {
            case "issue_provisional_credit" -> "provisional credit of " + money(arguments.get("amount_minor").longValue())
                    + " " + arguments.get("currency").stringValue();
            case "request_merchant_evidence" -> "merchant asked for " + arguments.get("evidence").size()
                    + " evidence item(s)";
            case "close_dispute" -> "dispute closed as declined";
            default -> throw new IllegalArgumentException("unknown tool " + tool);
        };
        ObjectNode n = JSON.createObjectNode()
                .put("status", "DONE")
                .put("action", tool)
                .put("dispute_id", claims.disputeId())
                .put("token_id", claims.tokenId().toString())
                .put("summary", summary);
        return JSON.writeValueAsString(n);
    }

    private static ObjectNode withoutToken(JsonNode arguments) {
        ObjectNode copy = ((ObjectNode) arguments).deepCopy();
        copy.remove(ApprovalVerifier.TOKEN_ARGUMENT);
        return copy;
    }

    private static String money(long minor) {
        return BigDecimal.valueOf(minor, 2).toPlainString();
    }

    private static Outcome rejected(Limit limit, String detail) {
        return rejected(limit.name(), detail);
    }

    private static Outcome rejected(String reason, String detail) {
        return new Outcome(false, "APPROVAL_REJECTED: " + reason + ": " + detail + ". No action was taken.");
    }
}
