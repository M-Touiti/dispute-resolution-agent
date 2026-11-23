package dev.disputeagent.actions.token;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Checks 1 to 9 of {@code docs/approval-token.md}, in that order: everything about a token that needs no database.
 * Business limits and single use come after, in the write's transaction ({@code ActionService}). The shared vectors
 * pin every verdict; this class implements the verifier those vectors describe.
 */
public final class ApprovalVerifier {

    public static final String TYPE = "approval+jwt";
    public static final int VERSION = 1;
    public static final long SKEW_SECONDS = 30;
    public static final String TOKEN_ARGUMENT = "approval_token";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Base64.Decoder B64 = Base64.getUrlDecoder();
    private static final List<String> CLAIMS = List.of("v", "jti", "sub", "act", "args", "rev", "iat", "exp");

    /** Why a token was refused; the text after {@code APPROVAL_REJECTED:}. */
    public enum Reason {
        MALFORMED, UNSUPPORTED, UNKNOWN_KEY, BAD_SIGNATURE, NOT_YET_VALID, EXPIRED, WRONG_TOOL, WRONG_DISPUTE,
        ARGUMENTS_MISMATCH
    }

    /** What a verified token asserts. */
    public record Claims(UUID tokenId, String disputeId, String tool, String argumentsSha256, String reviewer,
            String keyId, Instant issuedAt, Instant expiresAt) {
    }

    public sealed interface Verdict {
    }

    public record Accepted(Claims claims) implements Verdict {
    }

    public record Rejected(Reason reason, String detail) implements Verdict {
    }

    private final VerifyKeys keys;

    public ApprovalVerifier(VerifyKeys keys) {
        this.keys = keys;
    }

    /**
     * @param arguments the call's arguments as received, {@code approval_token} included; it is left out of the hash
     */
    public Verdict verify(String tool, JsonNode arguments, Instant now) {
        JsonNode token = arguments.get(TOKEN_ARGUMENT);
        if (token == null || !token.isString()) {
            return new Rejected(Reason.MALFORMED, "no approval_token");
        }
        String[] parts = token.stringValue().split("\\.", -1);
        if (parts.length != 3 || token.stringValue().contains("=")) {
            return new Rejected(Reason.MALFORMED, "not three unpadded base64url segments");
        }
        JsonNode header;
        JsonNode payload;
        byte[] signature;
        try {
            header = JSON.readTree(B64.decode(parts[0]));
            payload = JSON.readTree(B64.decode(parts[1]));
            signature = B64.decode(parts[2]);
        } catch (RuntimeException e) {
            return new Rejected(Reason.MALFORMED, "a segment is not base64url JSON");
        }
        if (!header.isObject() || !payload.isObject()) {
            return new Rejected(Reason.MALFORMED, "header and payload must be JSON objects");
        }
        for (String claim : CLAIMS) {
            if (!payload.has(claim)) {
                return new Rejected(Reason.MALFORMED, "missing claim " + claim);
            }
        }
        if (!"EdDSA".equals(header.path("alg").stringValue(null)) || !TYPE.equals(header.path("typ").stringValue(null))
                || !payload.get("v").isIntegralNumber() || payload.get("v").longValue() != VERSION) {
            return new Rejected(Reason.UNSUPPORTED, "only alg EdDSA, typ " + TYPE + ", v " + VERSION);
        }
        String keyId = header.path("kid").stringValue("");
        PublicKey key = keys.get(keyId);
        if (key == null) {
            return new Rejected(Reason.UNKNOWN_KEY, "no configured key " + keyId);
        }
        if (!signatureValid(key, parts[0] + "." + parts[1], signature)) {
            return new Rejected(Reason.BAD_SIGNATURE, "the signature does not verify with key " + keyId);
        }

        Claims claims;
        try {
            claims = new Claims(UUID.fromString(payload.get("jti").stringValue()), payload.get("sub").stringValue(),
                    payload.get("act").stringValue(), payload.get("args").stringValue(),
                    payload.get("rev").stringValue(), keyId, Instant.ofEpochSecond(payload.get("iat").longValue()),
                    Instant.ofEpochSecond(payload.get("exp").longValue()));
        } catch (RuntimeException e) {
            return new Rejected(Reason.MALFORMED, "a claim has the wrong type");
        }
        long at = now.getEpochSecond();
        if (claims.issuedAt().getEpochSecond() > at + SKEW_SECONDS) {
            return new Rejected(Reason.NOT_YET_VALID, "issued at " + claims.issuedAt());
        }
        if (claims.expiresAt().getEpochSecond() + SKEW_SECONDS <= at) {
            return new Rejected(Reason.EXPIRED, "expired at " + claims.expiresAt());
        }
        if (!tool.equals(claims.tool())) {
            return new Rejected(Reason.WRONG_TOOL, "the token is for " + claims.tool());
        }
        String disputeId = arguments.path("dispute_id").stringValue("");
        if (!disputeId.equals(claims.disputeId())) {
            return new Rejected(Reason.WRONG_DISPUTE, "the token is for " + claims.disputeId());
        }
        if (!argumentsSha256(arguments).equals(claims.argumentsSha256())) {
            return new Rejected(Reason.ARGUMENTS_MISMATCH, "the arguments differ from the approved ones");
        }
        return new Accepted(claims);
    }

    /** The hash of the call's arguments without {@code approval_token}, as the token binds them. */
    public static String argumentsSha256(JsonNode arguments) {
        ObjectNode withoutToken = ((ObjectNode) arguments).deepCopy();
        withoutToken.remove(TOKEN_ARGUMENT);
        return Jcs.sha256Hex(withoutToken);
    }

    private static boolean signatureValid(PublicKey key, String signingInput, byte[] signature) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }
}
