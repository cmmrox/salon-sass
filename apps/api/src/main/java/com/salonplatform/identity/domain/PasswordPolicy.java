package com.salonplatform.identity.domain;

import java.util.Locale;
import java.util.Set;

public final class PasswordPolicy {
    private PasswordPolicy() {}

    public static final Set<String> COMMON = Set.of(
            "passwordpassword",
            "password123456789",
            "123456789012345",
            "qwertyuiopasdfgh",
            "letmeinletmeinletmein",
            "welcome123456789");

    public static boolean valid(String value) {
        if (value == null) return false;
        int n = value.codePointCount(0, value.length());
        return n >= 15 && n <= 128 && !COMMON.contains(value.toLowerCase(Locale.ROOT));
    }
}
