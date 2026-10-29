package com.salonplatform.platform.infrastructure;

import com.salonplatform.shared.secrets.SecretKeys;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The billing provider's event-signing key (docs/architecture/operator-console.md, "Local test hooks"):
 * provider events carry a lowercase hex HMAC-SHA256 of their exact body in {@value #HEADER}. The key is
 * {@code SALON_BILLING_PROVIDER_KEY} (64 hex characters), independent of every other key the application
 * holds ({@link SecretKeys}). {@code local} alone generates a missing one into an ignored file readable by its owner only and
 * keeps it across restarts. Elsewhere no real provider is chosen while D-06 is open: without a
 * configured key there is no provider, and the event intake answers as if it did not exist.
 */
@Component
public class BillingProviderKey implements SecretKeys {

    /** The request header carrying an event's signature. */
    public static final String HEADER = "X-Salon-Billing-Signature";

    private final byte[] key;

    public BillingProviderKey(Environment environment) {
        String value = environment.getProperty("salon.billing.provider-key");
        if ((value == null || value.isBlank()) && environment.matchesProfiles("local")) {
            value = retained(environment);
        }
        if (value == null || value.isBlank()) {
            key = null;
        } else if (!value.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalStateException("Configure SALON_BILLING_PROVIDER_KEY with a 64-hex-character secret");
        } else {
            key = HexFormat.of().parseHex(value);
            for (String name : new String[] {
                "salon.operator.signing-key",
                "salon.operator.challenge-key",
                "salon.operator.encryption-key",
                "salon.operator.proxy-key",
                "salon.operator.fingerprint-key"
            }) {
                String other = environment.getProperty(name);
                if (other != null
                        && other.matches("[0-9a-fA-F]{64}")
                        && MessageDigest.isEqual(key, HexFormat.of().parseHex(other))) {
                    throw new IllegalStateException("The billing provider key must be independent of every other key");
                }
            }
        }
    }

    @Override
    public Map<String, byte[]> byPurpose() {
        return key == null ? Map.of() : Map.of("billing provider", key.clone());
    }

    /** Whether a provider key is configured. */
    public boolean configured() {
        return key != null;
    }

    /** The signature of a body, or nothing when no key is configured. */
    public Optional<String> sign(byte[] body) {
        if (key == null) {
            return Optional.empty();
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Optional.of(HexFormat.of().formatHex(mac.doFinal(body)));
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", ex);
        }
    }

    /** Whether the signature is exactly this body's, compared in constant time. */
    public boolean verify(byte[] body, String signature) {
        if (key == null || signature == null || !signature.matches("[0-9a-f]{64}")) {
            return false;
        }
        return MessageDigest.isEqual(
                sign(body).orElseThrow().getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII));
    }

    private static synchronized String retained(Environment environment) {
        Path path = Path.of(
                        environment.getProperty("salon.billing.local-key-file", "../../.local/billing-keys.properties"))
                .toAbsolutePath()
                .normalize();
        var mode = PosixFilePermissions.fromString("rw-------");
        Properties saved = new Properties();
        try {
            Files.createDirectories(path.getParent());
            Path lockPath = path.resolveSibling(path.getFileName() + ".lock");
            try (FileChannel channel =
                    FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                Files.setPosixFilePermissions(lockPath, mode);
                try (var _ = channel.lock()) {
                    if (Files.exists(path)) {
                        Files.setPosixFilePermissions(path, mode);
                        try (InputStream in = Files.newInputStream(path)) {
                            saved.load(in);
                        }
                    }
                    if (!saved.containsKey("provider")) {
                        byte[] fresh = new byte[32];
                        new SecureRandom().nextBytes(fresh);
                        saved.setProperty("provider", HexFormat.of().formatHex(fresh));
                        Path temporary = Files.createTempFile(
                                path.getParent(), ".billing-keys-", ".tmp", PosixFilePermissions.asFileAttribute(mode));
                        try {
                            try (OutputStream out = Files.newOutputStream(temporary)) {
                                saved.store(out, "Local-only billing provider key. Never commit or print.");
                            }
                            Files.move(
                                    temporary,
                                    path,
                                    StandardCopyOption.ATOMIC_MOVE,
                                    StandardCopyOption.REPLACE_EXISTING);
                        } finally {
                            Files.deleteIfExists(temporary);
                        }
                    }
                }
            }
            return saved.getProperty("provider");
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read the retained local billing provider key", ex);
        }
    }
}
