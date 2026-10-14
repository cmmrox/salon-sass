package com.salonplatform.identity.infrastructure;

import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.salonplatform.shared.api.ApplicationProblemException;
import com.salonplatform.shared.api.ErrorCode;
import com.salonplatform.shared.tenancy.SignedTenantVerifier;

import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Maintained JWT/password/TOTP implementations; purpose-bound authenticated encryption. */
@Component
public class IdentityCrypto implements SignedTenantVerifier {
    private static final String ISSUER = "salon-platform";
    private static final String AUDIENCE = "salon-api";
    private final IdentitySecrets secrets;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final PasswordEncoder passwords;
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final TimeBasedOneTimePasswordGenerator totp = new TimeBasedOneTimePasswordGenerator();
    private final String dummyHash;

    public IdentityCrypto(IdentitySecrets secrets, Clock clock) {
        this.secrets = secrets;
        this.clock = clock;
        Pbkdf2PasswordEncoder pbkdf2 = new Pbkdf2PasswordEncoder("", 16, 600000, 256);
        pbkdf2.setAlgorithm(Pbkdf2PasswordEncoder.SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256);
        passwords =
                new DelegatingPasswordEncoder(
                        "pbkdf2-sha256-600k", Map.of("pbkdf2-sha256-600k", pbkdf2));
        dummyHash = passwords.encode(randomToken());
        encoder = new NimbusJwtEncoder(new ImmutableSecret<>(secrets.signing()));
        NimbusJwtDecoder jwtDecoder =
                NimbusJwtDecoder.withSecretKey(new SecretKeySpec(secrets.signing(), "HmacSHA256"))
                        .macAlgorithm(MacAlgorithm.HS256)
                        .build();
        // Fixed issuer/audience/time checks below use the application's controllable clock with no
        // hidden skew.
        jwtDecoder.setJwtValidator(
                jwt ->
                        org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
                                .success());
        decoder = jwtDecoder;
    }

    public String encodePassword(String value) {
        return passwords.encode(value);
    }

    public boolean matchesPassword(String value, String hash) {
        return passwords.matches(value == null ? "" : value, hash == null ? dummyHash : hash);
    }

    public String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String hash(String purpose, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secrets.challenge(), "HmacSHA256"));
            return HexFormat.of()
                    .formatHex(
                            mac.doFinal((purpose + ":" + value).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("Credential hashing unavailable", ex);
        }
    }

    public boolean equalHash(String a, String b) {
        return a != null
                && b != null
                && MessageDigest.isEqual(
                        a.getBytes(StandardCharsets.US_ASCII),
                        b.getBytes(StandardCharsets.US_ASCII));
    }

    public String encrypt(String purpose, String plain) {
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(secrets.encryption(), "AES"),
                    new GCMParameterSpec(128, nonce));
            c.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(nonce)
                    + "."
                    + Base64.getEncoder()
                            .encodeToString(c.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("Secret encryption unavailable", ex);
        }
    }

    public String decrypt(String purpose, String encrypted) {
        try {
            String[] parts = encrypted.split("\\.");
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(secrets.encryption(), "AES"),
                    new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])));
            c.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
            return new String(
                    c.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("Stored secret could not be authenticated");
        }
    }

    public record Claims(
            UUID tenantId, UUID accountId, UUID sessionId, String kind, long generation) {}

    public String access(UUID tenant, UUID account, UUID session, String kind, long generation) {
        return access(
                tenant,
                account,
                session,
                kind,
                generation,
                clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    }

    public String access(
            UUID tenant, UUID account, UUID session, String kind, long generation, Instant at) {
        JwtClaimsSet claims =
                JwtClaimsSet.builder()
                        .issuer(ISSUER)
                        .audience(java.util.List.of(AUDIENCE))
                        .subject(account.toString())
                        .issuedAt(at)
                        .expiresAt(at.plusSeconds(300))
                        .claim("tenant", tenant.toString())
                        .claim("kind", kind)
                        .claim("session", session.toString())
                        .claim("generation", generation)
                        .build();
        return encoder.encode(
                        JwtEncoderParameters.from(
                                JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    public Claims verify(String token) {
        try {
            Jwt jwt = decoder.decode(token);
            Instant now = clock.instant();
            if (!"HS256".equals(jwt.getHeaders().get("alg"))
                    || !ISSUER.equals(jwt.getClaimAsString("iss"))
                    || !jwt.getAudience().equals(java.util.List.of(AUDIENCE))
                    || jwt.getExpiresAt() == null
                    || !now.isBefore(jwt.getExpiresAt())
                    || jwt.getIssuedAt() == null
                    || jwt.getIssuedAt().isAfter(now)
                    || !java.util.Set.of("staff", "customer")
                            .contains(jwt.getClaimAsString("kind")))
                throw new IllegalArgumentException();
            return new Claims(
                    UUID.fromString(jwt.getClaimAsString("tenant")),
                    UUID.fromString(jwt.getSubject()),
                    UUID.fromString(jwt.getClaimAsString("session")),
                    jwt.getClaimAsString("kind"),
                    ((Number) jwt.getClaim("generation")).longValue());
        } catch (Exception ex) {
            throw new ApplicationProblemException(401, ErrorCode.INVALID_SESSION);
        }
    }

    @Override
    public UUID tenantOf(String token) {
        return verify(token).tenantId();
    }

    public String newTotpSecret() {
        byte[] bytes = new byte[20];
        random.nextBytes(bytes);
        return base32(bytes);
    }

    public String totpCode(String secret, long step) {
        try {
            return totp.generateOneTimePasswordString(
                    new SecretKeySpec(unbase32(secret), "HmacSHA1"),
                    Instant.ofEpochSecond(step * 30));
        } catch (java.security.InvalidKeyException ex) {
            throw new IllegalStateException("TOTP key unavailable", ex);
        }
    }

    public Long matchingStep(String secret, String code) {
        if (code == null || !code.matches("[0-9]{6}")) return null;
        long current = clock.instant().getEpochSecond() / 30;
        for (long step = current - 1; step <= current + 1; step++)
            if (MessageDigest.isEqual(
                    code.getBytes(StandardCharsets.US_ASCII),
                    totpCode(secret, step).getBytes(StandardCharsets.US_ASCII))) return step;
        return null;
    }

    private static String base32(byte[] data) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        StringBuilder out = new StringBuilder();
        int bits = 0, value = 0;
        for (byte b : data) {
            value = (value << 8) | (b & 255);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                out.append(alphabet.charAt((value >> bits) & 31));
            }
        }
        if (bits > 0) out.append(alphabet.charAt((value << (5 - bits)) & 31));
        return out.toString();
    }

    private static byte[] unbase32(String s) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int bits = 0, value = 0;
        for (char ch : s.toUpperCase(java.util.Locale.ROOT).toCharArray()) {
            int n = alphabet.indexOf(ch);
            if (n < 0) throw new IllegalArgumentException();
            value = (value << 5) | n;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                out.write((value >> bits) & 255);
            }
        }
        return out.toByteArray();
    }
}
