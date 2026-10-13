package com.salonplatform.identity.domain;

import java.util.Locale;

/**
 * R1 Sri Lankan mobile and email normalization, without accepting caller-chosen tenant identifiers.
 */
public final class Identifiers {
    private Identifiers() {}

    public static String email(String value) {
        if (value == null || value.isBlank()) return null;
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (v.length() > 254 || !v.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))
            throw new IllegalArgumentException("email");
        return v;
    }

    public static String mobile(String value) {
        if (value == null || value.isBlank()) return null;
        String v = value.trim().replaceAll("[ ()-]", "");
        if (v.matches("0[7][0-9]{8}")) v = "+94" + v.substring(1);
        else if (v.matches("94[7][0-9]{8}")) v = "+" + v;
        if (!v.matches("\\+947[0-9]{8}")) throw new IllegalArgumentException("mobile");
        return v;
    }

    public static String identifier(String value) {
        return value != null && value.contains("@") ? email(value) : mobile(value);
    }
}
