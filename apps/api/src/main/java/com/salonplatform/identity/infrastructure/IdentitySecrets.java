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

    public IdentitySecrets(Environment environment) {
        Properties saved = new Properties();
        boolean local = environment.matchesProfiles("local");
        if (local) {
            Path path =
                    Path.of(
                            environment.getProperty(
                                    "salon.identity.local-key-file",
                                    "../../.local/identity-keys.properties"));
            try {
                Files.createDirectories(path.toAbsolutePath().getParent());
                if (!Files.exists(path)) {
                    for (String name : new String[] {"signing", "challenge", "encryption"}) {
                        byte[] key = new byte[32];
                        new SecureRandom().nextBytes(key);
                        saved.setProperty(name, HexFormat.of().formatHex(key));
                    }
                    // CREATE_NEW avoids overwriting a racing starter's keys.
                    Files.createFile(
                            path,
                            PosixFilePermissions.asFileAttribute(
                                    PosixFilePermissions.fromString("rw-------")));
                    try (OutputStream out = Files.newOutputStream(path)) {
                        saved.store(out, "Local-only identity keys. Never commit or print.");
                    }
                }
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
                try (InputStream in = Files.newInputStream(path)) {
                    saved.load(in);
                }
            } catch (java.io.IOException ex) {
                throw new IllegalStateException("Cannot read retained local identity keys", ex);
            }
        }
        signing = read(environment, saved, "signing");
        challenge = read(environment, saved, "challenge");
        encryption = read(environment, saved, "encryption");
        if (java.security.MessageDigest.isEqual(signing, challenge)
                || java.security.MessageDigest.isEqual(signing, encryption)
                || java.security.MessageDigest.isEqual(challenge, encryption))
            throw new IllegalStateException("Identity keys must be independent");
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
}
