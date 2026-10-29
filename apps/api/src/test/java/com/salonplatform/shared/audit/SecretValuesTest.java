package com.salonplatform.shared.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SecretValuesTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "password",
                "newPassword",
                "password_hash",
                "PASSWORD",
                "otp",
                "otpCode",
                "oneTimeCode",
                "one_time_code",
                "verificationCode",
                "token",
                "accessToken",
                "refresh_token",
                "apiKey",
                "api-key",
                "secret",
                "clientSecret",
                "pin",
                "cardNumber",
                "card_number",
                "cvv",
                "authorization",
                "Cookie",
                "sessionId",
                "privateKey"
            })
    void aKeyThatNamesASecretIsRecognised(String key) {
        assertThat(SecretValues.namesASecret(key)).as(key).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "name",
                "displayName",
                "shipping",
                "spinner",
                "postalCode",
                "currencyCode",
                "code",
                "status",
                "phoneCountry",
                "tokenizedAt2",
                "",
                "pinned",
                "opinion",
                "keys"
            })
    void anOrdinaryKeyIsLeftAlone(String key) {
        assertThat(SecretValues.namesASecret(key)).as(key).isFalse();
    }

    @Test
    void aSecretsValueIsReplacedAtAnyDepthAndEverythingElseIsKept() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", "Ann");
        values.put("password", "hunter2");
        values.put("gateway", Map.of("merchantId", "M-1", "apiKey", "sk_live_123"));
        values.put("contacts", List.of(Map.of("phone", "0771234567", "otp", "908172")));

        Map<String, Object> stored = SecretValues.redact(values);

        assertThat(stored).containsEntry("name", "Ann").containsEntry("password", SecretValues.REDACTED);
        assertThat(stored.get("gateway")).isEqualTo(Map.of("merchantId", "M-1", "apiKey", SecretValues.REDACTED));
        assertThat(stored.get("contacts"))
                .isEqualTo(List.of(Map.of("phone", "0771234567", "otp", SecretValues.REDACTED)));
        assertThat(stored.toString()).doesNotContain("hunter2", "sk_live_123", "908172");
        assertThat(values).as("the caller's values are not changed").containsEntry("password", "hunter2");
    }

    @Test
    void noValuesStayNoValues() {
        assertThat(SecretValues.redact(null)).isNull();
    }
}
