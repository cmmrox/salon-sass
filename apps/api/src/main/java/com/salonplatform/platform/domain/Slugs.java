package com.salonplatform.platform.domain;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The database's slug grammar and reserved names (docs/architecture/multi-tenancy.md), applied
 * before the database so a caller gets a field error; the database's own checks still decide. Plan
 * keys use the same grammar without the hostname reservation.
 */
public final class Slugs {

    public static final Pattern GRAMMAR = Pattern.compile("^[a-z0-9][a-z0-9-]{1,28}[a-z0-9]$");

    public static final Set<String> RESERVED = Set.of("admin", "api", "app", "apps", "assets", "auth", "cdn",
            "console", "docs", "help", "login", "mail", "staging", "static", "status", "support", "www");

    private Slugs() {
    }

    /** Trimmed and lower case: the form uniqueness is decided on. Null stays null. */
    public static String normalize(String value) {
        return value == null ? null : value.strip().toLowerCase(Locale.ROOT);
    }

    public static boolean wellFormed(String normalized) {
        return normalized != null && GRAMMAR.matcher(normalized).matches();
    }
}
