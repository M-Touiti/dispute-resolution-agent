package dev.disputeagent.actions.token;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The verifier against the shared vectors ({@code docs/approval-token/vectors.json}), the same file the investigator's
 * signer is tested with: every verdict, and every canonical form and hash of the arguments (ADR 0005).
 */
class ApprovalVerifierVectorsTest {

    static final JsonNode VECTORS = JsonMapper.builder().build()
            .readTree(Path.of("../../docs/approval-token/vectors.json").toFile());

    @Test
    void everyVerdictInTheVectors() {
        var keys = VerifyKeys.parse(VECTORS.at("/keys/verify_key_x509").stringValue());
        assertThat(keys.ids()).containsExactly(VECTORS.at("/keys/kid").stringValue());
        var verifier = new ApprovalVerifier(keys);

        int cases = 0;
        for (JsonNode t : VECTORS.get("tokens")) {
            JsonNode check = t.get("check");
            var verdict = verifier.verify(check.get("tool").stringValue(), check.get("arguments"),
                    Instant.parse(check.get("now").stringValue()));
            String actual = verdict instanceof ApprovalVerifier.Rejected r ? "REJECT:" + r.reason() : "ACCEPT";
            assertThat(actual).as(t.get("name").stringValue()).isEqualTo(t.get("verdict").stringValue());
            cases++;
        }
        assertThat(cases).isEqualTo(VECTORS.get("tokens").size()).isGreaterThanOrEqualTo(19);
    }

    @Test
    void anAcceptedTokenCarriesItsClaims() {
        JsonNode valid = VECTORS.get("tokens").get(0);
        var keys = VerifyKeys.parse(VECTORS.at("/keys/verify_key_x509").stringValue());

        var verdict = new ApprovalVerifier(keys).verify(valid.at("/check/tool").stringValue(),
                valid.at("/check/arguments"), Instant.parse(valid.at("/check/now").stringValue()));

        var claims = ((ApprovalVerifier.Accepted) verdict).claims();
        assertThat(claims.tokenId().toString()).isEqualTo(valid.at("/claims/jti").stringValue());
        assertThat(claims.disputeId()).isEqualTo(valid.at("/claims/sub").stringValue());
        assertThat(claims.reviewer()).isEqualTo(valid.at("/claims/rev").stringValue());
        assertThat(claims.keyId()).isEqualTo(VECTORS.at("/keys/kid").stringValue());
    }

    @Test
    void canonicalArgumentsAndHashesMatchTheInvestigators() {
        for (JsonNode a : VECTORS.get("arguments")) {
            assertThat(Jcs.canonical(a.get("arguments"))).as(a.get("name").stringValue())
                    .isEqualTo(a.get("canonical").stringValue());
            assertThat(Jcs.sha256Hex(a.get("arguments"))).as(a.get("name").stringValue())
                    .isEqualTo(a.get("sha256").stringValue());
        }
    }

    @Test
    void severalKeysForARotationAndNoneAtAll() {
        String main = VECTORS.at("/keys/verify_key_x509").stringValue();
        String other = VECTORS.at("/other_key/verify_key_x509").stringValue();

        assertThat(VerifyKeys.parse(main + ",\n " + other).ids()).containsExactlyInAnyOrder(
                VECTORS.at("/keys/kid").stringValue(), VECTORS.at("/other_key/kid").stringValue());
        assertThat(VerifyKeys.parse("").ids()).isEmpty();
        assertThat(VerifyKeys.parse(null).ids()).isEmpty();
    }
}
