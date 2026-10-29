package com.salonplatform.platform.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class OperatorCryptoTest {

    /**
     * A hash written by the encoder as configured before F23-S01 (PBKDF2 with HMAC-SHA256, 16-byte salt,
     * 600000 iterations, 256-bit hash) for a synthetic password. Every stored operator password depends on it
     * still verifying.
     */
    @Test
    void aPasswordHashStoredEarlierStillVerifies() {
        OperatorCrypto crypto = new OperatorCrypto(new OperatorSecrets(configured()), Clock.systemUTC());
        String stored = "{pbkdf2-sha256-600k}49aedb7ab90d4121766bb213f8e06b4c4d7434bea8b04319e3e623c0febf25b2"
                + "4abed20b5cadaf46122f5c3c007d73f7";

        assertThat(crypto.matchesPassword("a stored password of 2026", stored)).isTrue();
        assertThat(crypto.matchesPassword("another password of 2026", stored)).isFalse();
    }

    private static MockEnvironment configured() {
        MockEnvironment environment = new MockEnvironment();
        for (String name : new String[] {"signing", "challenge", "encryption", "proxy", "fingerprint"}) {
            environment.setProperty("salon.operator." + name + "-key", key());
        }
        return environment;
    }

    private static String key() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
