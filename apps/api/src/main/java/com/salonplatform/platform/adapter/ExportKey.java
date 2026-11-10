package com.salonplatform.platform.adapter;

import com.salonplatform.shared.secrets.KeyRing;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The key that encrypts a closed salon's export at rest (docs/architecture/operator-console.md,
 * "Suspension, closure, export and retention"): {@code SALON_EXPORT_KEY}, 64 hex characters,
 * independent of every operator, identity and billing key. AES-256-GCM with a fresh 96-bit nonce per
 * value and the value's purpose as associated data, so a part, an archive or a sealed link cannot be
 * swapped for another. Outside {@code local} the key must be configured; {@code local} alone generates
 * a missing one into an ignored file readable by its owner only ({@code .local/export-keys.properties})
 * and keeps it across restarts, so a stored archive stays readable.
 *
 * <p>Also issues the 256-bit download tokens, of which only a SHA-256 hash is stored.
 */
@Component
public class ExportKey implements SecretKeys {

    private static final int NONCE = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final List<byte[]> earlier;
    private final KeyRing keys;
    private final SecureRandom random = new SecureRandom();

    public ExportKey(Environment environment) {
        String value = environment.getProperty("salon.export.key");
        if ((value == null || value.isBlank()) && environment.matchesProfiles("local")) {
            value = retained(environment);
        }
        if (value == null || !value.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalStateException("Configure SALON_EXPORT_KEY with an independent 64-hex-character secret");
        }
        byte[] bytes = HexFormat.of().parseHex(value);
        for (String name : new String[] {
            "salon.operator.signing-key",
            "salon.operator.challenge-key",
            "salon.operator.encryption-key",
            "salon.operator.proxy-key",
            "salon.operator.fingerprint-key",
            "salon.billing.provider-key"
        }) {
            String other = environment.getProperty(name);
            if (other != null
                    && other.matches("[0-9a-fA-F]{64}")
                    && MessageDigest.isEqual(bytes, HexFormat.of().parseHex(other))) {
                throw new IllegalStateException("The export key must be independent of every other key");
            }
        }
        key = new SecretKeySpec(bytes, "AES");
        // Keys that encrypted exports before the current one (decision 120): they decrypt, never encrypt.
        earlier = KeyRing.keysOf(environment.getProperty("salon.export.earlier-keys"), "salon.export.earlier-keys");
        keys = new KeyRing("export", bytes, earlier);
    }

    @Override
    public Map<String, byte[]> byPurpose() {
        Map<String, byte[]> all = new java.util.LinkedHashMap<>(Map.of("export", key.getEncoded()));
        for (int i = 0; i < earlier.size(); i++) {
            all.put("export, earlier key " + (i + 1), earlier.get(i).clone());
        }
        return all;
    }

    /**
     * Encrypts a value for one purpose under the current key, carrying the key's id, so that the key can be rotated
     * (decision 120, {@link KeyRing}).
     */
    public byte[] seal(byte[] plain, String purpose) {
        return keys.seal(plain, purpose);
    }

    /**
     * Decrypts a value sealed for this purpose under the current or an earlier key, with or without a key id; fails
     * when it was altered or sealed for another.
     */
    public byte[] open(byte[] sealed, String purpose) {
        try {
            return keys.open(sealed, purpose);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("An export value could not be decrypted", ex);
        }
    }

    /** A fresh download token: 256 random bits, URL-safe Base64 without padding (43 characters). */
    public String newToken() {
        byte[] token = new byte[32];
        random.nextBytes(token);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    /** Whether a string has the exact shape of a download token. */
    public static boolean tokenShaped(String token) {
        return token != null && token.matches("[A-Za-z0-9_-]{43}");
    }

    /** The stored form of a token: its SHA-256, lowercase hex. */
    public static String tokenHash(String token) {
        return sha256(token.getBytes(StandardCharsets.US_ASCII));
    }

    public static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static synchronized String retained(Environment environment) {
        Path path = Path.of(
                        environment.getProperty("salon.export.local-key-file", "../../.local/export-keys.properties"))
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
                    if (!saved.containsKey("export")) {
                        byte[] fresh = new byte[32];
                        new SecureRandom().nextBytes(fresh);
                        saved.setProperty("export", HexFormat.of().formatHex(fresh));
                        Path temporary = Files.createTempFile(
                                path.getParent(), ".export-keys-", ".tmp", PosixFilePermissions.asFileAttribute(mode));
                        try {
                            try (OutputStream out = Files.newOutputStream(temporary)) {
                                saved.store(out, "Local-only export encryption key. Never commit or print.");
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
            return saved.getProperty("export");
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read the retained local export key", ex);
        }
    }
}
