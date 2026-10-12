package com.salonplatform.shared.audit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Removes every value under a key that names a secret, at any depth, before values are stored in
 * the audit trail: a password, a one-time code, a token, an API key, a card number. The key stays,
 * so the entry still shows that the value changed; the value becomes {@value #REDACTED}.
 *
 * <p>A key names a secret when one of its words is a secret's word ({@code password},
 * {@code newPassword}, {@code otp}, {@code access_token}), or when its words joined together are one
 * ({@code apiKey}, {@code cardNumber}). Words are split at case changes, digits and anything that is
 * not a letter, so {@code shipping} or {@code postalCode} are left alone.
 */
public final class SecretValues {

    public static final String REDACTED = "[redacted]";

    private static final Set<String> SECRET_WORDS = Set.of("password", "passwd", "pwd", "passcode", "passphrase",
            "secret", "token", "otp", "pin", "cvv", "cvc", "credential", "credentials", "authorization", "cookie",
            "signature", "salt", "hash");

    private static final Set<String> SECRET_COMPOUNDS = Set.of("apikey", "privatekey", "secretkey", "accesskey",
            "clientsecret", "onetimecode", "onetimepassword", "verificationcode", "otpcode", "cardnumber", "pan",
            "securitycode", "sessionid", "refreshtoken", "accesstoken", "idtoken");

    private static final Pattern WORD_BOUNDARY = Pattern.compile("(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|[^A-Za-z]+");

    private SecretValues() {
    }

    /** A copy of the values with every secret's value replaced; null stays null. */
    public static Map<String, Object> redact(Map<String, ?> values) {
        if (values == null) {
            return null;
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> copy.put(key, namesASecret(key) ? REDACTED : redactValue(value)));
        return copy;
    }

    public static boolean namesASecret(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        List<String> words = new ArrayList<>();
        for (String word : WORD_BOUNDARY.split(key)) {
            if (!word.isEmpty()) {
                words.add(word.toLowerCase(Locale.ROOT));
            }
        }
        if (words.stream().anyMatch(SECRET_WORDS::contains)) {
            return true;
        }
        String joined = String.join("", words);
        return SECRET_COMPOUNDS.contains(joined)
                || SECRET_COMPOUNDS.stream().anyMatch(compound -> joined.endsWith(compound) && compound.length() > 3);
    }

    @SuppressWarnings("unchecked")
    private static Object redactValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            map.forEach((key, inner) -> nested.put(String.valueOf(key), inner));
            return redact(nested);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            list.forEach(item -> copy.add(redactValue(item)));
            return copy;
        }
        return value;
    }
}
