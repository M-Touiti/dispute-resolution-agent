package dev.disputeagent.investigator.adapters.approval;

import static org.assertj.core.api.Assertions.assertThat;

import dev.disputeagent.investigator.domain.CaseId;
import dev.disputeagent.investigator.domain.approval.ApprovalToken;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The shared approval-token vectors ({@code docs/approval-token/vectors.json}, ADR 0005): the signer must produce
 * exactly the valid tokens, JCS must produce exactly the canonical arguments, and every verdict must be what the
 * reference verifier below decides. {@code actions-mcp} reads the same file for its verifier (task 2.6).
 *
 * <p>An intended change: run {@code ./mvnw -pl adapters test -Dtest=ApprovalTokenVectorsTest -Dvectors.update=true},
 * review the diff of the vectors, and change both sides in the same pull request. The keys in the file are kept; they
 * are test keys and sign nothing outside tests.
 */
class ApprovalTokenVectorsTest {

    static final Path VECTORS = Path.of("../../docs/approval-token/vectors.json");
    static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    static final Base64.Decoder B64 = Base64.getUrlDecoder();

    static final Instant ISSUED = Instant.parse("2026-11-23T09:00:00Z");
    static final UUID JTI = UUID.fromString("6f1c2a2e-4b7d-4c1e-9f3a-0d5b8e7c1a42");
    static final long SKEW_SECONDS = 30;

    // The vectors ---------------------------------------------------------------------------------------------

    @Test
    void theVectorsHold() throws Exception {
        if (Boolean.getBoolean("vectors.update")) {
            Files.createDirectories(VECTORS.getParent());
            Files.writeString(VECTORS, JSON.writeValueAsString(generate()).replace("\r\n", "\n") + "\n",
                    StandardCharsets.UTF_8);
        }
        JsonNode v = JSON.readTree(VECTORS.toFile());
        var signing = ApprovalKeys.privateKey(v.at("/keys/signing_key_pkcs8").stringValue());
        var verify = ApprovalKeys.publicKey(v.at("/keys/verify_key_x509").stringValue());
        assertThat(ApprovalKeys.keyId(verify)).isEqualTo(v.at("/keys/kid").stringValue());
        var other = ApprovalKeys.publicKey(v.at("/other_key/verify_key_x509").stringValue());
        assertThat(ApprovalKeys.keyId(other)).isEqualTo(v.at("/other_key/kid").stringValue());

        for (JsonNode a : v.get("arguments")) {
            assertThat(Jcs.canonical(a.get("arguments"))).as(a.get("name").stringValue())
                    .isEqualTo(a.get("canonical").stringValue());
            assertThat(Jcs.sha256Hex(a.get("arguments"))).as(a.get("name").stringValue())
                    .isEqualTo(a.get("sha256").stringValue());
        }

        var signer = new Ed25519TokenSigner(signing, verify);
        Map<String, PublicKey> keys = Map.of(ApprovalKeys.keyId(verify), verify);
        int accepted = 0;
        for (JsonNode t : v.get("tokens")) {
            String name = t.get("name").stringValue();
            String token = t.get("token").stringValue();
            if (t.has("claims")) {
                assertThat(signer.sign(claims(t.get("claims")))).as(name + ": the signer's output").isEqualTo(token);
            }
            JsonNode check = t.get("check");
            String verdict = verify(token, keys, check.get("tool").stringValue(),
                    check.get("dispute_id").stringValue(), check.get("arguments"),
                    Instant.parse(check.get("now").stringValue()));
            assertThat(verdict).as(name).isEqualTo(t.get("verdict").stringValue());
            accepted += verdict.equals("ACCEPT") ? 1 : 0;
        }
        assertThat(accepted).as("accepting cases").isGreaterThanOrEqualTo(3);
        assertThat(v.get("tokens").size() - accepted).as("rejecting cases").isGreaterThanOrEqualTo(12);
    }

    static ApprovalToken claims(JsonNode c) {
        return new ApprovalToken(UUID.fromString(c.get("jti").stringValue()), new CaseId(c.get("sub").stringValue()),
                c.get("act").stringValue(), c.get("args").stringValue(), c.get("rev").stringValue(),
                Instant.ofEpochSecond(c.get("iat").longValue()), Instant.ofEpochSecond(c.get("exp").longValue()));
    }

    // The reference verifier: ADR 0005's order, minus single use and business limits (stateful, task 2.6) --------

    static String verify(String token, Map<String, PublicKey> keys, String tool, String disputeId,
            JsonNode arguments, Instant now) {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || token.contains("=")) {
            return "REJECT:MALFORMED";
        }
        JsonNode header;
        JsonNode payload;
        byte[] signature;
        try {
            header = JSON.readTree(B64.decode(parts[0]));
            payload = JSON.readTree(B64.decode(parts[1]));
            signature = B64.decode(parts[2]);
        } catch (RuntimeException e) {
            return "REJECT:MALFORMED";
        }
        for (String claim : List.of("v", "jti", "sub", "act", "args", "rev", "iat", "exp")) {
            if (!payload.isObject() || !payload.has(claim)) {
                return "REJECT:MALFORMED";
            }
        }
        if (!header.isObject() || !"EdDSA".equals(header.path("alg").stringValue(null))
                || !Ed25519TokenSigner.TYPE.equals(header.path("typ").stringValue(null))
                || payload.get("v").longValue() != Ed25519TokenSigner.VERSION) {
            return "REJECT:UNSUPPORTED";
        }
        PublicKey key = keys.get(header.path("kid").stringValue(""));
        if (key == null) {
            return "REJECT:UNKNOWN_KEY";
        }
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(key);
            s.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!s.verify(signature)) {
                return "REJECT:BAD_SIGNATURE";
            }
        } catch (Exception e) {
            return "REJECT:BAD_SIGNATURE";
        }
        long at = now.getEpochSecond();
        if (payload.get("iat").longValue() > at + SKEW_SECONDS) {
            return "REJECT:NOT_YET_VALID";
        }
        if (payload.get("exp").longValue() + SKEW_SECONDS <= at) {
            return "REJECT:EXPIRED";
        }
        if (!tool.equals(payload.get("act").stringValue())) {
            return "REJECT:WRONG_TOOL";
        }
        if (!disputeId.equals(payload.get("sub").stringValue())) {
            return "REJECT:WRONG_DISPUTE";
        }
        ObjectNode withoutToken = ((ObjectNode) arguments).deepCopy();
        withoutToken.remove("approval_token");
        if (!Jcs.sha256Hex(withoutToken).equals(payload.get("args").stringValue())) {
            return "REJECT:ARGUMENTS_MISMATCH";
        }
        return "ACCEPT";
    }

    // Generation (vectors.update) ------------------------------------------------------------------------------

    static ObjectNode generate() throws Exception {
        ObjectNode root = JSON.createObjectNode();
        JsonNode existing = Files.exists(VECTORS) ? JSON.readTree(VECTORS.toFile()) : null;
        root.put("format", 1);
        root.put("about", "Shared approval-token vectors (ADR 0005, docs/approval-token.md). TEST KEYS ONLY: they sign "
                + "nothing outside the tests of the investigator and actions-mcp. Regenerate with "
                + "-Dvectors.update=true in investigator/adapters (ApprovalTokenVectorsTest).");
        String[] main = keys(existing, "keys");
        String[] other = keys(existing, "other_key");
        root.putObject("keys").put("signing_key_pkcs8", main[0]).put("verify_key_x509", main[1])
                .put("kid", ApprovalKeys.keyId(ApprovalKeys.publicKey(main[1])));
        root.putObject("other_key").put("signing_key_pkcs8", other[0]).put("verify_key_x509", other[1])
                .put("kid", ApprovalKeys.keyId(ApprovalKeys.publicKey(other[1])));
        PrivateKey signing = ApprovalKeys.privateKey(main[0]);
        PublicKey verify = ApprovalKeys.publicKey(main[1]);
        PrivateKey otherSigning = ApprovalKeys.privateKey(other[0]);
        String kid = ApprovalKeys.keyId(verify);
        String otherKid = ApprovalKeys.keyId(ApprovalKeys.publicKey(other[1]));

        // Arguments: the three tools as the investigator sends them (without approval_token), and JCS edge cases.
        Map<String, ObjectNode> arguments = new LinkedHashMap<>();
        arguments.put("issue_provisional_credit", JSON.createObjectNode().put("dispute_id", "DSP-000018")
                .put("amount_minor", 110056).put("currency", "EUR"));
        ObjectNode evidence = JSON.createObjectNode().put("dispute_id", "DSP-000018");
        evidence.putArray("evidence").add("RECEIPT").add("PROOF_OF_DELIVERY");
        evidence.put("note", "Please send the signed delivery record for order 4471.");
        arguments.put("request_merchant_evidence", evidence);
        ObjectNode close = JSON.createObjectNode().put("dispute_id", "DSP-000003")
                .put("reason", "Chip and PIN at a terminal in the cardholder's city; the card was present.");
        close.putArray("policy_section_ids").add("unauthorised-chip-and-pin").add("time-limits");
        arguments.put("close_dispute", close);
        ObjectNode strings = JSON.createObjectNode()
                .put("quote", "say \"no\"").put("backslash", "a\\b").put("controls", "tab\tnew\nline\u0001\u001f")
                .put("unicode", "café € 😀 ／").put("slash", "a/b");
        arguments.put("jcs_string_escapes", strings);
        ObjectNode order = JSON.createObjectNode().put("b", 1).put("a", 2).put("B", 3).put("é", 4).put("€", 5)
                .put("😀", 6).put("！", 7).put("aa", 8);
        arguments.put("jcs_member_order_utf16", order);
        ObjectNode values = JSON.createObjectNode().put("max_safe", 9007199254740991L).put("min_safe",
                -9007199254740991L).put("zero", 0).put("negative", -42).put("yes", true).put("no", false);
        values.putNull("nothing");
        values.putArray("empty");
        values.putObject("nested").putArray("list").add(3).add("x").addObject().put("k", "v");
        arguments.put("jcs_values", values);

        ArrayNode args = root.putArray("arguments");
        arguments.forEach((name, a) -> args.addObject().put("name", name).set("arguments", a));
        for (JsonNode a : args) {
            ((ObjectNode) a).put("canonical", Jcs.canonical(a.get("arguments")))
                    .put("sha256", Jcs.sha256Hex(a.get("arguments")));
        }

        // Tokens.
        ObjectNode credit = arguments.get("issue_provisional_credit");
        var claims = new ApprovalToken(JTI, new CaseId("DSP-000018"), "issue_provisional_credit",
                Jcs.sha256Hex(credit), "reviewer-1", ISSUED, ISSUED.plusSeconds(300));
        String good = new Ed25519TokenSigner(signing, verify).sign(claims);
        ObjectNode payload = Ed25519TokenSigner.payload(claims);
        long exp = claims.expiresAt().getEpochSecond();

        List<ObjectNode> tokens = new ArrayList<>();
        tokens.add(token("valid", good, claims, credit, "issue_provisional_credit", "DSP-000018", ISSUED.plusSeconds(10)));
        tokens.add(token("valid_within_expiry_skew", good, claims, credit, "issue_provisional_credit", "DSP-000018",
                Instant.ofEpochSecond(exp + SKEW_SECONDS - 1)));
        tokens.add(token("valid_issued_slightly_ahead_of_the_verifiers_clock", good, claims, credit,
                "issue_provisional_credit", "DSP-000018", ISSUED.minusSeconds(SKEW_SECONDS - 1)));
        var evidenceClaims = new ApprovalToken(UUID.fromString("0b6e4f1d-2c3a-4e5f-8a9b-7c6d5e4f3a21"),
                new CaseId("DSP-000018"), "request_merchant_evidence", Jcs.sha256Hex(evidence), "reviewer-2", ISSUED,
                ISSUED.plusSeconds(300));
        tokens.add(token("valid_request_merchant_evidence", new Ed25519TokenSigner(signing, verify).sign(evidenceClaims),
                evidenceClaims, evidence, "request_merchant_evidence", "DSP-000018", ISSUED.plusSeconds(60)));

        tokens.add(token("expired", good, null, credit, "issue_provisional_credit", "DSP-000018",
                Instant.ofEpochSecond(exp + SKEW_SECONDS)));
        tokens.add(token("not_yet_valid", good, null, credit, "issue_provisional_credit", "DSP-000018",
                ISSUED.minusSeconds(SKEW_SECONDS + 1)));
        tokens.add(token("wrong_tool", good, null, credit, "close_dispute", "DSP-000018", ISSUED.plusSeconds(10)));
        tokens.add(token("wrong_dispute", good, null, credit.deepCopy().put("dispute_id", "DSP-000019"),
                "issue_provisional_credit", "DSP-000019", ISSUED.plusSeconds(10)));
        tokens.add(token("arguments_changed_after_approval", good, null, credit.deepCopy().put("amount_minor", 110057),
                "issue_provisional_credit", "DSP-000018", ISSUED.plusSeconds(10)));

        String[] goodParts = good.split("\\.");
        String tamperedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(Jcs.canonical(
                payload.deepCopy().put("act", "close_dispute")).getBytes(StandardCharsets.UTF_8));
        tokens.add(token("payload_changed_after_signing", goodParts[0] + "." + tamperedPayload + "." + goodParts[2],
                null, credit, "close_dispute", "DSP-000018", ISSUED.plusSeconds(10)));
        tokens.add(token("signed_by_another_key_under_our_key_id",
                Ed25519TokenSigner.sign(Ed25519TokenSigner.header(kid), payload, otherSigning), null, credit,
                "issue_provisional_credit", "DSP-000018", ISSUED.plusSeconds(10)));
        tokens.add(token("unknown_key_id", Ed25519TokenSigner.sign(Ed25519TokenSigner.header(otherKid), payload,
                otherSigning), null, credit, "issue_provisional_credit", "DSP-000018", ISSUED.plusSeconds(10)));
        tokens.add(token("alg_not_eddsa", Ed25519TokenSigner.sign(Ed25519TokenSigner.header(kid).put("alg", "HS256"),
                payload, signing), null, credit, "issue_provisional_credit", "DSP-000018", ISSUED.plusSeconds(10)));
        tokens.add(token("typ_not_approval", Ed25519TokenSigner.sign(Ed25519TokenSigner.header(kid).put("typ", "JWT"),
                payload, signing), null, credit, "issue_provisional_credit", "DSP-000018", ISSUED.plusSeconds(10)));
        tokens.add(token("version_2", Ed25519TokenSigner.sign(Ed25519TokenSigner.header(kid),
                payload.deepCopy().put("v", 2), signing), null, credit, "issue_provisional_credit", "DSP-000018",
                ISSUED.plusSeconds(10)));
        ObjectNode noExpiry = payload.deepCopy();
        noExpiry.remove("exp");
        tokens.add(token("missing_exp", Ed25519TokenSigner.sign(Ed25519TokenSigner.header(kid), noExpiry, signing),
                null, credit, "issue_provisional_credit", "DSP-000018", ISSUED.plusSeconds(10)));
        tokens.add(token("two_segments", goodParts[0] + "." + goodParts[1], null, credit, "issue_provisional_credit",
                "DSP-000018", ISSUED.plusSeconds(10)));
        tokens.add(token("base64_padding", good + "==", null, credit, "issue_provisional_credit", "DSP-000018",
                ISSUED.plusSeconds(10)));
        tokens.add(token("header_not_json", "bm90IGpzb24." + goodParts[1] + "." + goodParts[2], null, credit,
                "issue_provisional_credit", "DSP-000018", ISSUED.plusSeconds(10)));

        Map<String, PublicKey> known = Map.of(kid, verify);
        ArrayNode out = root.putArray("tokens");
        for (ObjectNode t : tokens) {
            JsonNode check = t.get("check");
            t.put("verdict", verify(t.get("token").stringValue(), known, check.get("tool").stringValue(),
                    check.get("dispute_id").stringValue(), check.get("arguments"),
                    Instant.parse(check.get("now").stringValue())));
            out.add(t);
        }
        return root;
    }

    static ObjectNode token(String name, String token, ApprovalToken claims, ObjectNode arguments, String tool,
            String disputeId, Instant now) {
        ObjectNode t = JSON.createObjectNode().put("name", name).put("token", token);
        if (claims != null) {
            t.set("claims", Ed25519TokenSigner.payload(claims));
        }
        ObjectNode call = arguments.deepCopy().put("approval_token", token);
        t.putObject("check").put("tool", tool).put("dispute_id", disputeId).put("now", now.toString())
                .set("arguments", call);
        return t;
    }

    static String[] keys(JsonNode existing, String field) throws Exception {
        if (existing != null && existing.has(field)) {
            return new String[] {existing.get(field).get("signing_key_pkcs8").stringValue(),
                    existing.get(field).get("verify_key_x509").stringValue()};
        }
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        return new String[] {Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()),
                Base64.getEncoder().encodeToString(pair.getPublic().getEncoded())};
    }
}
