package com.salonplatform.identity;

import static org.assertj.core.api.Assertions.*;

import com.salonplatform.identity.infrastructure.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;

class IdentityCryptoTest {
    @TempDir Path directory;

    private static String key() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private static MockEnvironment configured() {
        return new MockEnvironment()
                .withProperty("salon.identity.signing-key", key())
                .withProperty("salon.identity.challenge-key", key())
                .withProperty("salon.identity.encryption-key", key());
    }

    @Test
    void nonlocalRequiresIndependentKeys() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new IdentitySecrets(new MockEnvironment()))
                .withMessageContaining("SALON_IDENTITY_SIGNING_KEY");
        String same = key();
        assertThatIllegalStateException()
                .isThrownBy(
                        () ->
                                new IdentitySecrets(
                                        configured()
                                                .withProperty("salon.identity.signing-key", same)
                                                .withProperty(
                                                        "salon.identity.challenge-key", same)))
                .withMessageContaining("independent");
    }

    @Test
    void localRetainsKeysAndCredentialsAcrossRestart() throws Exception {
        MockEnvironment env =
                new MockEnvironment()
                        .withProperty(
                                "salon.identity.local-key-file",
                                directory.resolve("keys.properties").toString());
        env.setActiveProfiles("local");
        Clock now = Clock.fixed(Instant.parse("2026-10-13T10:00:00Z"), ZoneOffset.UTC);
        IdentityCrypto first = new IdentityCrypto(new IdentitySecrets(env), now),
                restart = new IdentityCrypto(new IdentitySecrets(env), now);
        UUID tenant = UUID.randomUUID();
        String token = first.access(tenant, UUID.randomUUID(), UUID.randomUUID(), "staff", 1);
        assertThat(restart.tenantOf(token)).isEqualTo(tenant);
        assertThat(Files.getPosixFilePermissions(directory.resolve("keys.properties")))
                .isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    void passwordsHaveIdentifiedSaltedEncodingAndEncryptionIsPurposeBound() {
        IdentityCrypto crypto =
                new IdentityCrypto(new IdentitySecrets(configured()), Clock.systemUTC());
        String password = UUID.randomUUID() + " synthetic";
        String first = crypto.encodePassword(password), second = crypto.encodePassword(password);
        assertThat(first)
                .startsWith("{pbkdf2-sha256-600k}")
                .isNotEqualTo(second)
                .doesNotContain(password);
        assertThat(crypto.matchesPassword(password, first)).isTrue();
        String plain = UUID.randomUUID().toString(), encrypted = crypto.encrypt("tenant-a", plain);
        assertThat(encrypted).doesNotContain(plain);
        assertThat(crypto.decrypt("tenant-a", encrypted)).isEqualTo(plain);
        assertThatIllegalStateException().isThrownBy(() -> crypto.decrypt("tenant-b", encrypted));
    }

    @Test
    void totpLibraryMatchesRfc6238VectorAndAdjacentSteps() {
        Clock now = Clock.fixed(Instant.ofEpochSecond(59), ZoneOffset.UTC);
        IdentityCrypto crypto = new IdentityCrypto(new IdentitySecrets(configured()), now);
        // RFC's public test key encodes ASCII 12345678901234567890, not a runtime credential.
        String publicVector = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
        assertThat(crypto.totpCode(publicVector, 1)).isEqualTo("287082");
        assertThat(crypto.matchingStep(publicVector, "287082")).isEqualTo(1L);
        assertThat(crypto.matchingStep(publicVector, crypto.totpCode(publicVector, 2)))
                .isEqualTo(2L);
        assertThat(crypto.matchingStep(publicVector, crypto.totpCode(publicVector, 3))).isNull();
    }
}
