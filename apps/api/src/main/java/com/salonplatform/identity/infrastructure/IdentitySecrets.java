package com.salonplatform.identity.infrastructure;

import com.salonplatform.shared.secrets.SecretKeys;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Identity's four independent 256-bit keys: access-token signing, challenge hashing, MFA secret encryption
 * and the sign-in address assertion. Each is separate from every other key the application holds
 * ({@link SecretKeys}). Outside {@code local} every key must be configured ({@code SALON_IDENTITY_*_KEY});
 * {@code local} alone generates missing ones into an ignored file readable by its owner only and keeps them
 * across restarts.
 */
@Component
public class IdentitySecrets implements SecretKeys {

    private static final List<String> NAMES = List.of("signing", "challenge", "encryption", "proxy");

    private final byte[] signing;
    private final byte[] challenge;
    private final byte[] encryption;
    private final byte[] proxy;

    public IdentitySecrets(Environment environment) {
        Properties saved = environment.matchesProfiles("local") ? retained(environment) : new Properties();
        signing = read(environment, saved, "signing");
        challenge = read(environment, saved, "challenge");
        encryption = read(environment, saved, "encryption");
        proxy = read(environment, saved, "proxy");
        List<byte[]> keys = List.of(signing, challenge, encryption, proxy);
        for (int a = 0; a < keys.size(); a++) {
            for (int b = a + 1; b < keys.size(); b++) {
                if (MessageDigest.isEqual(keys.get(a), keys.get(b))) {
                    throw new IllegalStateException("Identity keys must be independent");
                }
            }
        }
    }

    private static synchronized Properties retained(Environment environment) {
        Path path = Path.of(environment.getProperty(
                        "salon.identity.local-key-file", "../../.local/identity-keys.properties"))
                .toAbsolutePath()
                .normalize();
        Properties saved = new Properties();
        Path temporary = null;
        var mode = PosixFilePermissions.fromString("rw-------");
        try {
            Files.createDirectories(path.getParent());
            Path lockPath = path.resolveSibling(path.getFileName() + ".lock");
            // A separate lock remains stable while an atomic replacement preserves complete reads.
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
                    boolean changed = false;
                    for (String name : NAMES) {
                        if (!saved.containsKey(name)) {
                            byte[] key = new byte[32];
                            new SecureRandom().nextBytes(key);
                            saved.setProperty(name, HexFormat.of().formatHex(key));
                            changed = true;
                        }
                    }
                    if (changed) {
                        temporary = Files.createTempFile(
                                path.getParent(),
                                ".identity-keys-",
                                ".tmp",
                                PosixFilePermissions.asFileAttribute(mode));
                        try (OutputStream out = Files.newOutputStream(temporary)) {
                            saved.store(out, "Local-only identity keys. Never commit or print.");
                        }
                        Files.move(
                                temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                        temporary = null;
                    }
                }
            }
            return saved;
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read retained local identity keys", ex);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Nothing more can be done; the file holds no key until it is moved into place.
                }
            }
        }
    }

    private static byte[] read(Environment environment, Properties saved, String name) {
        String value = environment.getProperty("salon.identity." + name + "-key");
        if (value == null || value.isBlank()) {
            value = saved.getProperty(name);
        }
        if (value == null || !value.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalStateException("Configure SALON_IDENTITY_" + name.toUpperCase(Locale.ROOT)
                    + "_KEY with an independent 64-hex-character secret");
        }
        return HexFormat.of().parseHex(value);
    }

    @Override
    public Map<String, byte[]> byPurpose() {
        return Map.of(
                "identity signing", signing(),
                "identity challenge", challenge(),
                "identity encryption", encryption(),
                "identity proxy", proxy());
    }

    public byte[] signing() {
        return signing.clone();
    }

    public byte[] challenge() {
        return challenge.clone();
    }

    public byte[] encryption() {
        return encryption.clone();
    }

    public byte[] proxy() {
        return proxy.clone();
    }
}
