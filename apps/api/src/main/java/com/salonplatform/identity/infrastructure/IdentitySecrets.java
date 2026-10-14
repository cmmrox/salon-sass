package com.salonplatform.identity.infrastructure;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Properties;

/** Independent externally configured 256-bit keys; only local generates a retained ignored file. */
@Component
public class IdentitySecrets {
    private final byte[] signing;
    private final byte[] challenge;
    private final byte[] encryption;
    private final byte[] proxy;

    public IdentitySecrets(Environment environment) {
        Properties saved = new Properties();
        boolean local = environment.matchesProfiles("local");
        if (local) saved = retained(environment);
        signing = read(environment, saved, "signing");
        challenge = read(environment, saved, "challenge");
        encryption = read(environment, saved, "encryption");
        proxy = read(environment, saved, "proxy");
        byte[][] keys = {signing, challenge, encryption, proxy};
        for (int a = 0; a < keys.length; a++)
            for (int b = a + 1; b < keys.length; b++)
                if (java.security.MessageDigest.isEqual(keys[a], keys[b]))
                    throw new IllegalStateException("Identity keys must be independent");
    }

    private static synchronized Properties retained(Environment environment) {
        Path path =
                Path.of(
                                environment.getProperty(
                                        "salon.identity.local-key-file",
                                        "../../.local/identity-keys.properties"))
                        .toAbsolutePath()
                        .normalize();
        Properties saved = new Properties();
        Path temporary = null;
        var mode = PosixFilePermissions.fromString("rw-------");
        try {
            Files.createDirectories(path.getParent());
            Path lockPath = path.resolveSibling(path.getFileName() + ".lock");
            // A separate lock remains stable while an atomic replacement preserves complete reads.
            try (var channel =
                    java.nio.channels.FileChannel.open(
                            lockPath,
                            java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.WRITE)) {
                Files.setPosixFilePermissions(lockPath, mode);
                try (var lock = channel.lock()) {
                    if (Files.exists(path)) {
                        Files.setPosixFilePermissions(path, mode);
                        try (InputStream in = Files.newInputStream(path)) {
                            saved.load(in);
                        }
                    }
                    boolean changed = false;
                    for (String name :
                            new String[] {"signing", "challenge", "encryption", "proxy"}) {
                        if (!saved.containsKey(name)) {
                            byte[] key = new byte[32];
                            new SecureRandom().nextBytes(key);
                            saved.setProperty(name, HexFormat.of().formatHex(key));
                            changed = true;
                        }
                    }
                    if (changed) {
                        temporary =
                                Files.createTempFile(
                                        path.getParent(),
                                        ".identity-keys-",
                                        ".tmp",
                                        PosixFilePermissions.asFileAttribute(mode));
                        try (OutputStream out = Files.newOutputStream(temporary)) {
                            saved.store(out, "Local-only identity keys. Never commit or print.");
                        }
                        Files.move(
                                temporary,
                                path,
                                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        temporary = null;
                    }
                }
            }
            return saved;
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Cannot read retained local identity keys", ex);
        } finally {
            if (temporary != null)
                try {
                    Files.deleteIfExists(temporary);
                } catch (java.io.IOException ignored) {
                }
        }
    }

    private static byte[] read(Environment env, Properties saved, String name) {
        String value = env.getProperty("salon.identity." + name + "-key", saved.getProperty(name));
        if (value == null || value.isBlank()) value = saved.getProperty(name);
        if (value == null || !value.matches("[0-9a-fA-F]{64}"))
            throw new IllegalStateException(
                    "Configure SALON_IDENTITY_"
                            + name.toUpperCase(java.util.Locale.ROOT)
                            + "_KEY with an independent 64-hex-character secret");
        return HexFormat.of().parseHex(value);
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
