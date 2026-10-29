package com.salonplatform.platform.infrastructure;

import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.salonplatform.shared.api.ApplicationProblemException;
import com.salonplatform.shared.api.ErrorCode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
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
 * The operator console's cryptography, on the maintained implementations identity uses (Spring
 * Security's PBKDF2 and JWT, java-otp TOTP) with the console's own independent keys. Operator access
 * tokens have kind {@code operator}, issuer {@code salon-operator}, audience
 * {@code salon-operator-api} and no tenant claim, so they can never authenticate a staff or customer
 * endpoint, and no salon token can authenticate here.
 */
@Component
public class OperatorCrypto {

    public static final String ISSUER = "salon-operator";
    public static final String AUDIENCE = "salon-operator-api";
    public static final String KIND = "operator";

    private final OperatorSecrets secrets;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final PasswordEncoder passwords;
    private final String dummyHash;
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final TimeBasedOneTimePasswordGenerator totp = new TimeBasedOneTimePasswordGenerator();

    public OperatorCrypto(OperatorSecrets secrets, Clock clock) {
        this.secrets = secrets;
        this.clock = clock;
        Pbkdf2PasswordEncoder pbkdf2 = new Pbkdf2PasswordEncoder(
                "", 16, 600000, Pbkdf2PasswordEncoder.SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256);
        passwords = new DelegatingPasswordEncoder("pbkdf2-sha256-600k", Map.of("pbkdf2-sha256-600k", pbkdf2));
        dummyHash = passwords.encode(newRandomToken());
        encoder = new NimbusJwtEncoder(new ImmutableSecret<>(secrets.signing()));
        NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(secrets.signing(), "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        // Issuer, audience, kind and time are checked below against the application's clock.
        jwtDecoder.setJwtValidator(jwt -> OAuth2TokenValidatorResult.success());
        decoder = jwtDecoder;
    }

    public String encodePassword(String value) {
        return passwords.encode(value);
    }

    /** Constant work whether or not the account exists: an unknown account checks a dummy hash. */
    public boolean matchesPassword(String value, String hash) {
        return passwords.matches(value == null ? "" : value, hash == null ? dummyHash : hash);
    }

    /** 256 random bits, URL-safe. */
    public String randomToken() {
        return newRandomToken();
    }

    /** Private, so that the constructor calls nothing a subclass could override. */
    private String newRandomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Keyed, purpose-bound hash for challenge and refresh credentials, rate keys and audit subjects. */
    public String hash(String purpose, String value) {
        return hmac(secrets.challenge(), purpose + ":" + value);
    }

    /** Keyed fingerprint of a normalized request, for idempotency receipts; a key of its own. */
    public String fingerprint(String canonical) {
        return hmac(secrets.fingerprint(), canonical);
    }

    byte[] proxyKey() {
        return secrets.proxy();
    }

    private static String hmac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Keyed hashing unavailable", ex);
        }
    }

    public boolean equalHash(String a, String b) {
        return a != null
                && b != null
                && MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }

    public String encrypt(String purpose, String plain) {
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(secrets.encryption(), "AES"),
                    new GCMParameterSpec(128, nonce));
            cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(nonce) + "."
                    + Base64.getEncoder().encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Secret encryption unavailable", ex);
        }
    }

    public String decrypt(String purpose, String encrypted) {
        try {
            String[] parts = encrypted.split("\\.");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(secrets.encryption(), "AES"),
                    new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])));
            cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("Stored secret could not be authenticated");
        }
    }

    /** What a verified operator access token says. */
    public record Claims(UUID operatorId, UUID sessionId, long generation) {}

    /** A five-minute access token, issued at the given second-precision instant. */
    public String access(UUID operator, UUID session, long generation, Instant issued) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .subject(operator.toString())
                .issuedAt(issued)
                .expiresAt(issued.plusSeconds(300))
                .claim("kind", KIND)
                .claim("session", session.toString())
                .claim("generation", generation)
                .build();
        return encoder.encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    public Instant issuedNow() {
        return clock.instant().truncatedTo(ChronoUnit.SECONDS);
    }

    /**
     * Verifies signature, fixed algorithm, issuer, audience, kind and time, and that no tenant claim
     * is present.
     *
     * @throws ApplicationProblemException 401 {@code invalid_session}
     */
    public Claims verify(String token) {
        try {
            if (token == null || token.isBlank()) {
                throw new IllegalArgumentException();
            }
            Jwt jwt = decoder.decode(token);
            Instant now = clock.instant();
            if (!"HS256".equals(String.valueOf(jwt.getHeaders().get("alg")))
                    || !ISSUER.equals(jwt.getClaimAsString("iss"))
                    || !List.of(AUDIENCE).equals(jwt.getAudience())
                    || !KIND.equals(jwt.getClaimAsString("kind"))
                    || jwt.hasClaim("tenant")
                    || jwt.getExpiresAt() == null
                    || !now.isBefore(jwt.getExpiresAt())
                    || jwt.getIssuedAt() == null
                    || jwt.getIssuedAt().isAfter(now)) {
                throw new IllegalArgumentException();
            }
            return new Claims(
                    UUID.fromString(jwt.getSubject()),
                    UUID.fromString(jwt.getClaimAsString("session")),
                    ((Number) jwt.getClaim("generation")).longValue());
        } catch (Exception ex) {
            throw new ApplicationProblemException(401, ErrorCode.INVALID_SESSION);
        }
    }

    /** A random 20-byte TOTP secret in base32. */
    public String newTotpSecret() {
        byte[] bytes = new byte[20];
        random.nextBytes(bytes);
        return base32(bytes);
    }

    public String totpCode(String secret, long step) {
        try {
            return totp.generateOneTimePasswordString(
                    new SecretKeySpec(unbase32(secret), "HmacSHA1"), Instant.ofEpochSecond(step * 30));
        } catch (InvalidKeyException ex) {
            throw new IllegalStateException("TOTP key unavailable", ex);
        }
    }

    /** The step a six-digit code matches within one adjacent step, or null. */
    public Long matchingStep(String secret, String code) {
        if (code == null || !code.matches("[0-9]{6}")) {
            return null;
        }
        long current = clock.instant().getEpochSecond() / 30;
        for (long step = current - 1; step <= current + 1; step++) {
            if (MessageDigest.isEqual(
                    code.getBytes(StandardCharsets.US_ASCII),
                    totpCode(secret, step).getBytes(StandardCharsets.US_ASCII))) {
                return step;
            }
        }
        return null;
    }

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    static String base32(byte[] data) {
        StringBuilder out = new StringBuilder();
        int bits = 0;
        int value = 0;
        for (byte b : data) {
            value = (value << 8) | (b & 255);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                out.append(ALPHABET.charAt((value >> bits) & 31));
            }
        }
        if (bits > 0) {
            out.append(ALPHABET.charAt((value << (5 - bits)) & 31));
        }
        return out.toString();
    }

    static byte[] unbase32(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int bits = 0;
        int value = 0;
        for (char ch : text.toUpperCase(Locale.ROOT).toCharArray()) {
            int n = ALPHABET.indexOf(ch);
            if (n < 0) {
                throw new IllegalArgumentException("not base32");
            }
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
