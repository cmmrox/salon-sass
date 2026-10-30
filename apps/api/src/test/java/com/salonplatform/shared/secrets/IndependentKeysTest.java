package com.salonplatform.shared.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.salonplatform.identity.adapter.IdentitySecrets;
import com.salonplatform.platform.adapter.BillingProviderKey;
import com.salonplatform.platform.adapter.ExportKey;
import com.salonplatform.platform.adapter.OperatorSecrets;
import com.salonplatform.platform.adapter.SupportCrypto;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * No secret key is used for two purposes, whichever modules hold them (F02-S01, F03-S01 to S04). Before
 * F23-S04 the platform's keys were compared with identity's by reading identity's settings; the comparison
 * now runs in {@code shared} over the keys each module declares, so that no module reads another's settings.
 */
class IndependentKeysTest {

    private static final List<String> KEY_SETTINGS = List.of(
            "salon.identity.signing-key",
            "salon.identity.challenge-key",
            "salon.identity.encryption-key",
            "salon.identity.proxy-key",
            "salon.operator.signing-key",
            "salon.operator.challenge-key",
            "salon.operator.encryption-key",
            "salon.operator.proxy-key",
            "salon.operator.fingerprint-key",
            "salon.billing.provider-key",
            "salon.export.key",
            "salon.support.signing-key");

    /** The beans that hold the application's keys, and the check, configured as an environment configures them. */
    private final ApplicationContextRunner keys = new ApplicationContextRunner()
            .withBean(Clock.class, Clock::systemUTC)
            .withUserConfiguration(
                    IdentitySecrets.class,
                    OperatorSecrets.class,
                    BillingProviderKey.class,
                    ExportKey.class,
                    SupportCrypto.class,
                    IndependentKeys.class);

    @Test
    void theApplicationStartsWhenEveryKeyIsIndependent() {
        keys.withPropertyValues(settings(Map.of()))
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(IndependentKeys.class));
    }

    /** A platform key equal to an identity key is refused, as it was when platform read identity's settings. */
    @ParameterizedTest(name = "{0} and {1}")
    @CsvSource({
        "salon.identity.signing-key, salon.operator.signing-key, identity signing, operator signing",
        "salon.identity.challenge-key, salon.billing.provider-key, identity challenge, billing provider",
        "salon.identity.encryption-key, salon.export.key, identity encryption, export",
        "salon.identity.proxy-key, salon.support.signing-key, identity proxy, support signing"
    })
    void aPlatformKeyEqualToAnIdentityKeyIsRefused(
            String identitySetting, String platformSetting, String identityPurpose, String platformPurpose) {
        String shared = key();

        keys.withPropertyValues(settings(Map.of(identitySetting, shared, platformSetting, shared.toUpperCase())))
                .run(context -> assertThat(context)
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining(identityPurpose)
                        .hasMessageContaining(platformPurpose)
                        .hasMessageContaining("independent")
                        .hasMessageNotContaining(shared));
    }

    @Test
    void twoHoldersOfTheSameKeyAreRefusedByPurposeAndNeverByValue() {
        byte[] same = HexFormat.of().parseHex(key());
        SecretKeys first = () -> Map.of("first", same.clone());
        SecretKeys second = () -> Map.of("other", HexFormat.of().parseHex(key()), "second", same.clone());

        assertThatIllegalStateException()
                .isThrownBy(() -> new IndependentKeys(List.of(first, second)))
                .withMessageContaining("first")
                .withMessageContaining("second")
                .withMessageNotContaining(HexFormat.of().formatHex(same));
    }

    @Test
    void holdersOfDifferentKeysAreAccepted() {
        SecretKeys first = () -> Map.of("first", HexFormat.of().parseHex(key()));
        SecretKeys second = () -> Map.of("second", HexFormat.of().parseHex(key()));

        new IndependentKeys(List.of(first, second));
    }

    /** Every key setting with an independent random value, but those given. */
    private static String[] settings(Map<String, String> given) {
        List<String> settings = new ArrayList<>();
        KEY_SETTINGS.forEach(name -> settings.add(name + "=" + given.getOrDefault(name, key())));
        return settings.toArray(String[]::new);
    }

    private static String key() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
