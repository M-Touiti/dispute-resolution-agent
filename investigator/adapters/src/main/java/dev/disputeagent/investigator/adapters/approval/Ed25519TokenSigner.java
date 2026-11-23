package dev.disputeagent.investigator.adapters.approval;

import dev.disputeagent.investigator.application.port.approval.TokenSigner;
import dev.disputeagent.investigator.domain.approval.ApprovalToken;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The approval token as a compact JWS (RFC 7515) signed with Ed25519 by the JDK (ADR 0005, {@code
 * docs/approval-token.md}). Header and payload are serialised with JCS, so the same claims and key always give the same
 * bytes (Ed25519 signatures are deterministic) and the shared test vectors can pin whole tokens.
 */
public final class Ed25519TokenSigner implements TokenSigner {

    public static final String TYPE = "approval+jwt";
    public static final int VERSION = 1;

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final PrivateKey privateKey;
    private final String keyId;

    /** Refuses keys that are not a pair: a mismatch would sign tokens actions-mcp can never verify. */
    public Ed25519TokenSigner(PrivateKey privateKey, PublicKey publicKey) {
        if (!ApprovalKeys.arePair(privateKey, publicKey)) {
            throw new IllegalArgumentException("the approval signing key and its public key are not a pair");
        }
        this.privateKey = privateKey;
        this.keyId = ApprovalKeys.keyId(publicKey);
    }

    public String keyId() {
        return keyId;
    }

    @Override
    public String sign(ApprovalToken claims) {
        return sign(header(keyId), payload(claims), privateKey);
    }

    static ObjectNode header(String keyId) {
        return JSON.createObjectNode().put("alg", "EdDSA").put("kid", keyId).put("typ", TYPE);
    }

    static ObjectNode payload(ApprovalToken t) {
        return JSON.createObjectNode()
                .put("v", VERSION)
                .put("jti", t.tokenId().toString())
                .put("sub", t.caseId().value())
                .put("act", t.action())
                .put("args", t.argumentsSha256())
                .put("rev", t.reviewer())
                .put("iat", t.issuedAt().getEpochSecond())
                .put("exp", t.expiresAt().getEpochSecond());
    }

    /** {@code base64url(JCS(header)) . base64url(JCS(payload)) . base64url(Ed25519 signature of the first two)}. */
    static String sign(ObjectNode header, ObjectNode payload, PrivateKey key) {
        String signingInput = B64.encodeToString(Jcs.canonical(header).getBytes(StandardCharsets.UTF_8)) + "."
                + B64.encodeToString(Jcs.canonical(payload).getBytes(StandardCharsets.UTF_8));
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(key);
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + B64.encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("could not sign an approval token", e);
        }
    }
}
