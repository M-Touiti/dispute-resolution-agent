package dev.disputeagent.investigator.domain.approval;

import dev.disputeagent.investigator.domain.CaseId;
import dev.disputeagent.investigator.domain.decision.ProposedAction;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * What an approval token asserts (ADR 0005): this person approved this tool, on this dispute, with exactly these
 * arguments, once, until the expiry. The signature and the encoding are the adapter's; the domain checks the binding.
 *
 * @param tokenId         the JWS {@code jti}; single use and the idempotency key of the action
 * @param argumentsSha256 lower-case hex SHA-256 of the tool's arguments without {@code approval_token}, as JCS
 */
public record ApprovalToken(UUID tokenId, CaseId caseId, String action, String argumentsSha256, String reviewer,
        Instant issuedAt, Instant expiresAt) {

    /** How long a token is valid: the action runs right after signing (ADR 0005). */
    public static final Duration LIFETIME = Duration.ofMinutes(5);

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    public ApprovalToken {
        Objects.requireNonNull(tokenId, "tokenId");
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(argumentsSha256, "argumentsSha256");
        Objects.requireNonNull(reviewer, "reviewer");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!SHA256_HEX.matcher(argumentsSha256).matches()) {
            throw new IllegalArgumentException("argumentsSha256 must be 64 lower-case hex characters");
        }
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("a token must expire after it is issued");
        }
        if (Duration.between(issuedAt, expiresAt).compareTo(LIFETIME) > 0) {
            throw new IllegalArgumentException("a token lives at most " + LIFETIME);
        }
    }

    /** A new token for a reviewed request, valid for {@link #LIFETIME} from the review. */
    public static ApprovalToken issue(UUID tokenId, ApprovalRequest request, String argumentsSha256, Review review) {
        return new ApprovalToken(tokenId, request.caseId(), request.action().toolName(), argumentsSha256,
                review.reviewer(), review.at(), review.at().plus(LIFETIME));
    }

    /** True when the token was issued for this action on this case. */
    public boolean bindsTo(CaseId caseId, ProposedAction action) {
        return this.caseId.equals(caseId) && this.action.equals(action.toolName());
    }

    /** Expired at {@code at}: the expiry instant itself is already too late. */
    public boolean expiredAt(Instant at) {
        return !at.isBefore(expiresAt);
    }
}
