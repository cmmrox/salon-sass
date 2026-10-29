package com.salonplatform.platform.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Properties;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
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
public class ExportKey {

    private static final int NONCE = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
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
            "salon.identity.signing-key",
            "salon.identity.challenge-key",
            "salon.identity.encryption-key",
            "salon.identity.proxy-key",
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
    }

    /** Encrypts a value for one purpose: the nonce followed by the ciphertext and its tag. */
    public byte[] seal(byte[] plain, String purpose) {
        try {
            byte[] nonce = new byte[NONCE];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(plain);
            return ByteBuffer.allocate(NONCE + sealed.length)
                    .put(nonce)
                    .put(sealed)
                    .array();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("AES-GCM is unavailable", ex);
        }
    }

    /** Decrypts a value sealed for this purpose; fails when it was altered or sealed for another. */
    public byte[] open(byte[] sealed, String purpose) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, Arrays.copyOf(sealed, NONCE)));
            cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(sealed, NONCE, sealed.length - NONCE);
        } catch (GeneralSecurityException ex) {
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
