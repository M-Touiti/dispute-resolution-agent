package dev.disputeagent.investigator.adapters.approval;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Ed25519 keys as configured (ADR 0005): the private key as base64 PKCS#8, the public key as base64 X.509
 * (SubjectPublicKeyInfo), both as the JDK encodes them. The key id is the first 16 hex characters of the SHA-256 of
 * the raw 32-byte public key.
 */
public final class ApprovalKeys {

    /** The DER prefix of every Ed25519 SubjectPublicKeyInfo; the raw key is the 32 bytes after it. */
    private static final byte[] X509_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

    private ApprovalKeys() {
    }

    public static PrivateKey privateKey(String base64Pkcs8) {
        try {
            return KeyFactory.getInstance("Ed25519")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64Pkcs8.strip())));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("not a base64 PKCS#8 Ed25519 private key", e);
        }
    }

    public static PublicKey publicKey(String base64X509) {
        try {
            return KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64X509.strip())));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("not a base64 X.509 Ed25519 public key", e);
        }
    }

    public static String keyId(PublicKey key) {
        byte[] encoded = key.getEncoded();
        if (encoded.length != X509_PREFIX.length + 32
                || !Arrays.equals(Arrays.copyOf(encoded, X509_PREFIX.length), X509_PREFIX)) {
            throw new IllegalArgumentException("not an Ed25519 public key");
        }
        byte[] raw = Arrays.copyOfRange(encoded, X509_PREFIX.length, encoded.length);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)).substring(0, 16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** True when the private key's signatures verify with the public key. */
    public static boolean arePair(PrivateKey privateKey, PublicKey publicKey) {
        try {
            byte[] probe = "approval key pair check".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(privateKey);
            signer.update(probe);
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(probe);
            return verifier.verify(signer.sign());
        } catch (GeneralSecurityException e) {
            return false;
        }
    }
}
