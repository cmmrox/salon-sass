package com.salonplatform.shared.tenancy;

import java.util.Locale;
import java.util.Optional;

/**
 * Reads the salon's slug out of a request's {@code Host} header: {@code <slug>.<platform domain>},
 * the port ignored, compared without regard to case. Only exactly one label in front of the
 * platform domain names a salon; the platform domain itself, two labels in front of it, and a
 * hostname that merely contains it name none (docs/architecture/multi-tenancy.md).
 *
 * <p>Whether the label is a salon at all is the registry's to say. This only reads the hostname.
 */
public final class PlatformHostname {

    private final String suffix;

    /**
     * @param platformDomain the domain salons are served under, for example {@code localhost}
     */
    public PlatformHostname(String platformDomain) {
        String domain = platformDomain == null ? "" : platformDomain.trim().toLowerCase(Locale.ROOT);
        if (domain.isEmpty() || domain.startsWith(".") || domain.endsWith(".") || domain.contains(":")) {
            throw new IllegalArgumentException("the platform domain must be a hostname such as 'localhost', not '"
                    + platformDomain + "'");
        }
        this.suffix = "." + domain;
    }

    /**
     * @param hostHeader the {@code Host} header exactly as received, or null when there was none
     * @return the candidate slug in lower case, or nothing when the hostname cannot name a salon
     */
    public Optional<String> slugOf(String hostHeader) {
        if (hostHeader == null) {
            return Optional.empty();
        }
        String host = withoutPort(hostHeader.trim()).toLowerCase(Locale.ROOT);
        if (!host.endsWith(suffix)) {
            return Optional.empty();
        }
        String label = host.substring(0, host.length() - suffix.length());
        if (label.isEmpty() || label.indexOf('.') >= 0) {
            return Optional.empty();
        }
        return Optional.of(label);
    }

    private static String withoutPort(String host) {
        if (host.startsWith("[")) {
            // An IPv6 literal is never a salon's hostname; keep it whole so that it cannot match.
            return host;
        }
        int colon = host.indexOf(':');
        return colon < 0 ? host : host.substring(0, colon);
    }
}
