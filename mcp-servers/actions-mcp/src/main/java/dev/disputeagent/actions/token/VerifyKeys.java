package dev.disputeagent.actions.token;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The public keys this server accepts tokens from, by key id (ADR 0005). Configured as base64 X.509 keys separated by
 * commas or whitespace, so a rotation can list the old and the new key. This server never holds a private key.
 */
public final class VerifyKeys {

    /** The DER prefix of every Ed25519 SubjectPublicKeyInfo; the raw key is the 32 bytes after it. */
    private static final byte[] X509_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

    private final Map<String, PublicKey> byId;

    private VerifyKeys(Map<String, PublicKey> byId) {
        this.byId = Map.copyOf(byId);
    }

    public static VerifyKeys parse(String configured) {
        Map<String, PublicKey> keys = new LinkedHashMap<>();
        if (configured != null) {
            for (String b64 : configured.strip().split("[,\\s]+")) {
                if (!b64.isEmpty()) {
                    PublicKey key = publicKey(b64);
                    keys.put(keyId(key), key);
                }
            }
        }
        return new VerifyKeys(keys);
    }

    public PublicKey get(String keyId) {
        return byId.get(keyId);
    }

    public java.util.Set<String> ids() {
        return byId.keySet();
    }

    static PublicKey publicKey(String base64X509) {
        try {
            return KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64X509)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("not a base64 X.509 Ed25519 public key", e);
        }
    }

    /** The first 16 hex characters of the SHA-256 of the raw 32-byte key, as the investigator computes it. */
    public static String keyId(PublicKey key) {
        byte[] encoded = key.getEncoded();
        if (encoded.length != X509_PREFIX.length + 32
                || !Arrays.equals(Arrays.copyOf(encoded, X509_PREFIX.length), X509_PREFIX)) {
            throw new IllegalArgumentException("not an Ed25519 public key");
        }
        try {
            byte[] raw = Arrays.copyOfRange(encoded, X509_PREFIX.length, encoded.length);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)).substring(0, 16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
