package com.salonplatform.platform.infrastructure;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The support credential and entry codes (F03-S04): kind, issuer, salon, expiry and an independent key. */
class SupportCryptoTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
    private static final String KEY = "ab".repeat(32);

    private static SupportCrypto crypto(String key, Instant now) {
        return new SupportCrypto(new MockEnvironment().withProperty("salon.support.signing-key", key),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void aCredentialCarriesItsSalonSessionOperatorAndExpiryAndIsCurrentUntilThen() {
        SupportCrypto crypto = crypto(KEY, NOW);
        UUID salon = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        UUID operator = UUID.randomUUID();
        String token = crypto.credential(salon, session, operator, NOW, NOW.plusSeconds(1800));
        SupportCrypto.Claims claims = crypto.claims(token).orElseThrow();
        assertThat(claims.salonId()).isEqualTo(salon);
        assertThat(claims.sessionId()).isEqualTo(session);
        assertThat(claims.operatorId()).isEqualTo(operator);
        assertThat(claims.expiresAt()).isEqualTo(NOW.plusSeconds(1800));
        assertThat(crypto.current(claims)).isTrue();
        assertThat(crypto(KEY, NOW.plusSeconds(1800)).current(claims)).isFalse();
        assertThat(crypto(KEY, NOW.minusSeconds(1)).current(claims)).as("issued in the future").isFalse();
    }

    @Test
    void anotherKeysOrAnAlteredOrMalformedTokenIsNoSupportCredential() {
        SupportCrypto crypto = crypto(KEY, NOW);
        String token = crypto.credential(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), NOW, NOW.plusSeconds(60));
        assertThat(crypto(KEY.replace('a', 'c'), NOW).claims(token)).isEmpty();
        assertThat(crypto.claims(token.substring(0, token.length() - 3) + "abc")).isEmpty();
        assertThat(crypto.claims("not-a-token")).isEmpty();
        assertThat(crypto.claims("")).isEmpty();
        assertThat(crypto.claims(null)).isEmpty();
    }

    @Test
    void theKeyIsRequiredAndIndependentOfEveryOtherKey() {
        assertThatThrownBy(() -> new SupportCrypto(new MockEnvironment(), Clock.systemUTC()))
                .hasMessageContaining("SALON_SUPPORT_SIGNING_KEY");
        assertThatThrownBy(() -> crypto("short", NOW)).hasMessageContaining("SALON_SUPPORT_SIGNING_KEY");
        for (String other : new String[] {"salon.identity.signing-key", "salon.operator.signing-key", "salon.export.key"}) {
            assertThatThrownBy(() -> new SupportCrypto(new MockEnvironment().withProperty("salon.support.signing-key", KEY)
                    .withProperty(other, KEY.toUpperCase()), Clock.systemUTC())).hasMessageContaining("independent");
        }
    }

    @Test
    void entryCodesAre256RandomBitsStoredOnlyAsTheirHash() {
        SupportCrypto crypto = crypto(KEY, NOW);
        String code = crypto.newCode();
        assertThat(SupportCrypto.codeShaped(code)).isTrue();
        assertThat(crypto.newCode()).isNotEqualTo(code);
        assertThat(SupportCrypto.codeHash(code)).matches("[0-9a-f]{64}").doesNotContain(code);
        assertThat(SupportCrypto.codeShaped(code + "x")).isFalse();
        assertThat(SupportCrypto.codeShaped(code.substring(1) + "=")).isFalse();
    }
}
