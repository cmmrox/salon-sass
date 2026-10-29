package com.salonplatform.platform.infrastructure;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
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
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/**
 * The support credential and entry codes (F03-S04; docs/architecture/operator-console.md, "Support
 * sessions"). The credential is a JWT of kind {@code support}, issuer {@code salon-support}, the salon
 * as its only tenant and the session's expiry, signed with {@code SALON_SUPPORT_SIGNING_KEY} (64 hex
 * characters, independent of every other key) and never refreshed. Outside {@code local} the key must
 * be configured; {@code local} alone generates a missing one into an ignored file readable by its
 * owner only ({@code .local/support-keys.properties}) and keeps it across restarts.
 *
 * <p>Entry codes are 256 random bits, URL-safe; only their SHA-256 is stored.
 */
@Component
public class SupportCrypto {

    public static final String ISSUER = "salon-support";
    public static final String AUDIENCE = "salon-api";
    public static final String KIND = "support";

    /** The claims of a verified support credential. */
    public record Claims(UUID salonId, UUID sessionId, UUID operatorId, Instant issuedAt, Instant expiresAt) {}

    private final Clock clock;
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final SecureRandom random = new SecureRandom();

    public SupportCrypto(Environment environment, Clock clock) {
        this.clock = clock;
        String value = environment.getProperty("salon.support.signing-key");
        if ((value == null || value.isBlank()) && environment.matchesProfiles("local")) {
            value = retained(environment);
        }
        if (value == null || !value.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalStateException(
                    "Configure SALON_SUPPORT_SIGNING_KEY with an independent 64-hex-character secret");
        }
        byte[] key = HexFormat.of().parseHex(value);
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
            "salon.billing.provider-key",
            "salon.export.key"
        }) {
            String other = environment.getProperty(name);
            if (other != null
                    && other.matches("[0-9a-fA-F]{64}")
                    && MessageDigest.isEqual(key, HexFormat.of().parseHex(other))) {
                throw new IllegalStateException("The support signing key must be independent of every other key");
            }
        }
        encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(key, "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        // Issuer, audience, kind and time are checked below against the application's clock.
        jwtDecoder.setJwtValidator(jwt -> OAuth2TokenValidatorResult.success());
        decoder = jwtDecoder;
    }

    /** A support credential for one session, valid from {@code issuedAt} until the session's expiry. */
    public String credential(UUID salonId, UUID sessionId, UUID operatorId, Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .subject(operatorId.toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("kind", KIND)
                .claim("tenant", salonId.toString())
                .claim("session", sessionId.toString())
                .build();
        return encoder.encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    /**
     * The claims of a credential signed with the support key, whatever their times; empty when it is
     * not one (another kind's credential, a malformed or altered one).
     */
    public Optional<Claims> claims(String credential) {
        if (credential == null || credential.isBlank() || credential.length() > 4096) {
            return Optional.empty();
        }
        try {
            Jwt jwt = decoder.decode(credential);
            if (!"HS256".equals(String.valueOf(jwt.getHeaders().get("alg")))
                    || !ISSUER.equals(jwt.getClaimAsString("iss"))
                    || !List.of(AUDIENCE).equals(jwt.getAudience())
                    || !KIND.equals(jwt.getClaimAsString("kind"))
                    || jwt.getExpiresAt() == null
                    || jwt.getIssuedAt() == null) {
                return Optional.empty();
            }
            return Optional.of(new Claims(
                    UUID.fromString(jwt.getClaimAsString("tenant")),
                    UUID.fromString(jwt.getClaimAsString("session")),
                    UUID.fromString(jwt.getSubject()),
                    jwt.getIssuedAt(),
                    jwt.getExpiresAt()));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    /** Whether claims are within their time: issued no later than now and not yet expired. */
    public boolean current(Claims claims) {
        Instant now = clock.instant();
        return !claims.issuedAt().isAfter(now) && now.isBefore(claims.expiresAt());
    }

    /** A fresh entry code: 256 random bits, URL-safe Base64 without padding (43 characters). */
    public String newCode() {
        byte[] code = new byte[32];
        random.nextBytes(code);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(code);
    }

    /** Whether a string has the exact shape of an entry code. */
    public static boolean codeShaped(String code) {
        return code != null && code.matches("[A-Za-z0-9_-]{43}");
    }

    /** The stored form of an entry code: its SHA-256, lowercase hex. */
    public static String codeHash(String code) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static synchronized String retained(Environment environment) {
        Path path = Path.of(
                        environment.getProperty("salon.support.local-key-file", "../../.local/support-keys.properties"))
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
                    if (!saved.containsKey("support")) {
                        byte[] fresh = new byte[32];
                        new SecureRandom().nextBytes(fresh);
                        saved.setProperty("support", HexFormat.of().formatHex(fresh));
                        Path temporary = Files.createTempFile(
                                path.getParent(), ".support-keys-", ".tmp", PosixFilePermissions.asFileAttribute(mode));
                        try {
                            try (OutputStream out = Files.newOutputStream(temporary)) {
                                saved.store(out, "Local-only support credential signing key. Never commit or print.");
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
            return saved.getProperty("support");
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read the retained local support key", ex);
        }
    }
}
