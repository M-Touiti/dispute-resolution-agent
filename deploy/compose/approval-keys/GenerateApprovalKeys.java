import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Generates the approval-token key pair for the compose stack (ADR 0005), once: a second start keeps the existing
 * pair. Run by the one-shot {@code approval-keys} service with the JDK's single-file launcher, no build and no library.
 *
 * <ul>
 *   <li>{@code <signing dir>}, mounted only into the investigator: the private key (PKCS#8) and its public key
 *       (X.509), as Spring config-tree files {@code investigator.approval.signing-key} and
 *       {@code investigator.approval.verify-key}.</li>
 *   <li>{@code <verify dir>}, mounted only into actions-mcp: the public key alone, as
 *       {@code actions-mcp.approval.verify-keys}. The write server never sees the private key.</li>
 * </ul>
 *
 * Both images run as uid 10001; the files are given to it, the private key readable by its owner only.
 */
public class GenerateApprovalKeys {

    static final int APP_UID = 10001;

    public static void main(String[] args) throws Exception {
        Path signing = Path.of(args[0]);
        Path verify = Path.of(args[1]);
        Path privateFile = signing.resolve("investigator.approval.signing-key");
        Path publicFile = signing.resolve("investigator.approval.verify-key");
        Path verifyFile = verify.resolve("actions-mcp.approval.verify-keys");

        if (Files.exists(privateFile) && Files.exists(publicFile)) {
            String pub = Files.readString(publicFile).strip();
            write(verifyFile, pub, "r--r--r--");
            System.out.println("approval keys exist; key id " + keyId(Base64.getDecoder().decode(pub)));
            return;
        }
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String pub = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        write(privateFile, Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()), "r--------");
        write(publicFile, pub, "r--r--r--");
        write(verifyFile, pub, "r--r--r--");
        System.out.println("approval keys generated; key id " + keyId(pair.getPublic().getEncoded()));
    }

    static void write(Path file, String content, String permissions) throws Exception {
        Files.createDirectories(file.getParent());
        Files.deleteIfExists(file);
        Files.writeString(file, content, StandardCharsets.US_ASCII);
        Files.setAttribute(file, "unix:uid", APP_UID);
        Files.setAttribute(file, "unix:gid", APP_UID);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(permissions));
    }

    /** As the investigator computes it: the first 16 hex characters of the SHA-256 of the raw 32-byte key. */
    static String keyId(byte[] x509) throws Exception {
        byte[] raw = Arrays.copyOfRange(x509, x509.length - 32, x509.length);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)).substring(0, 16);
    }
}
