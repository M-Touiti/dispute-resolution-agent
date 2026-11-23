package dev.disputeagent.actions;

import dev.disputeagent.actions.token.Jcs;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Signs approval tokens in tests the way the investigator does ({@code docs/approval-token.md}), with the test key of
 * the shared vectors. Test code only: this server never signs.
 */
public final class TestTokens {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static JsonNode vectors;

    private TestTokens() {
    }

    static synchronized JsonNode vectors() {
        if (vectors == null) {
            try {
                vectors = JSON.readTree(ActionsDatabase.VECTORS.toFile());
            } catch (RuntimeException e) {
                throw new UncheckedIOException(new IOException("cannot read " + ActionsDatabase.VECTORS, e));
            }
        }
        return vectors;
    }

    public static String verifyKey() {
        return vectors().at("/keys/verify_key_x509").stringValue();
    }

    /** A token for these exact arguments (without approval_token), issued now, by the vectors' key. */
    public static String sign(String tool, Map<String, Object> arguments, String reviewer, UUID tokenId,
            Instant issuedAt) {
        ObjectNode payload = JSON.createObjectNode()
                .put("v", 1)
                .put("jti", tokenId.toString())
                .put("sub", (String) arguments.get("dispute_id"))
                .put("act", tool)
                .put("args", Jcs.sha256Hex(JSON.valueToTree(arguments)))
                .put("rev", reviewer)
                .put("iat", issuedAt.getEpochSecond())
                .put("exp", issuedAt.getEpochSecond() + 300);
        ObjectNode header = JSON.createObjectNode().put("alg", "EdDSA")
                .put("kid", vectors().at("/keys/kid").stringValue()).put("typ", "approval+jwt");
        var b64 = Base64.getUrlEncoder().withoutPadding();
        String input = b64.encodeToString(Jcs.canonical(header).getBytes(StandardCharsets.UTF_8)) + "."
                + b64.encodeToString(Jcs.canonical(payload).getBytes(StandardCharsets.UTF_8));
        try {
            PrivateKey key = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(
                    Base64.getDecoder().decode(vectors().at("/keys/signing_key_pkcs8").stringValue())));
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(key);
            s.update(input.getBytes(StandardCharsets.US_ASCII));
            return input + "." + b64.encodeToString(s.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
