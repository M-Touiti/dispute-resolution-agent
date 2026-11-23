package dev.disputeagent.investigator.adapters.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.disputeagent.investigator.domain.CaseId;
import dev.disputeagent.investigator.domain.approval.ApprovalToken;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class Ed25519TokenSignerTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Instant T0 = Instant.parse("2026-11-23T09:00:00.987654321Z");

    static KeyPair pair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    static ApprovalToken claims() {
        return new ApprovalToken(UUID.randomUUID(), new CaseId("DSP-000018"), "issue_provisional_credit",
                "0f".repeat(32), "reviewer-1", T0, T0.plusSeconds(300));
    }

    @Test
    void aTokenIsACompactJwsTheJdkVerifiesWithThePublicKey() throws Exception {
        var keys = pair();
        var signer = new Ed25519TokenSigner(keys.getPrivate(), keys.getPublic());
        var claims = claims();

        String token = signer.sign(claims);

        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);
        assertThat(token).doesNotContain("=").matches("[A-Za-z0-9_.-]+");
        JsonNode header = JSON.readTree(Base64.getUrlDecoder().decode(parts[0]));
        JsonNode payload = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(header.toString()).isEqualTo(
                "{\"alg\":\"EdDSA\",\"kid\":\"" + signer.keyId() + "\",\"typ\":\"approval+jwt\"}");
        assertThat(payload.get("v").intValue()).isEqualTo(1);
        assertThat(payload.get("jti").stringValue()).isEqualTo(claims.tokenId().toString());
        assertThat(payload.get("sub").stringValue()).isEqualTo("DSP-000018");
        assertThat(payload.get("act").stringValue()).isEqualTo("issue_provisional_credit");
        assertThat(payload.get("rev").stringValue()).isEqualTo("reviewer-1");
        assertThat(payload.get("iat").longValue()).as("whole seconds").isEqualTo(T0.getEpochSecond());
        assertThat(payload.get("exp").longValue()).isEqualTo(T0.getEpochSecond() + 300);

        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(keys.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
        assertThat(signer.sign(claims)).as("deterministic").isEqualTo(token);
    }

    @Test
    void keysThatAreNotAPairAreRefusedAtStart() throws Exception {
        var a = pair();
        var b = pair();

        assertThatThrownBy(() -> new Ed25519TokenSigner(a.getPrivate(), b.getPublic()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a pair");
    }

    @Test
    void configuredKeysAreReadFromBase64AndTheKeyIdIsStable() throws Exception {
        var keys = pair();
        String privateB64 = Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded());
        String publicB64 = Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());

        var signer = new Ed25519TokenSigner(ApprovalKeys.privateKey(privateB64 + "\n"),
                ApprovalKeys.publicKey(" " + publicB64));

        assertThat(signer.keyId()).hasSize(16).matches("[0-9a-f]+")
                .isEqualTo(ApprovalKeys.keyId(keys.getPublic()));
        assertThatThrownBy(() -> ApprovalKeys.privateKey("not a key")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApprovalKeys.publicKey(privateB64)).isInstanceOf(IllegalArgumentException.class);
    }
}
