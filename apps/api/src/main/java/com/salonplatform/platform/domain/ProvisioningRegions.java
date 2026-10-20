package com.salonplatform.platform.domain;

import java.time.ZoneId;
import java.util.Currency;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * What a new salon's country, currency, default language and time zone may be: one place, so that
 * these remain configuration rather than rules scattered through code (NFR-13). The lead's decision
 * for F03-S01: countries are the ISO 3166-1 alpha-2 codes the JDK knows; currencies are the active
 * ISO 4217 codes (the currency of at least one of those countries) with exactly two minor digits,
 * like LKR; languages are those the product is delivered in; time zones are IANA names that both the
 * JDK and the database's own check accept. The console defaults to LK, LKR, {@code Asia/Colombo} and
 * English.
 */
public final class ProvisioningRegions {

    public static final String DEFAULT_COUNTRY = "LK";
    public static final String DEFAULT_CURRENCY = "LKR";
    public static final String DEFAULT_TIME_ZONE = "Asia/Colombo";
    public static final String DEFAULT_LANGUAGE = "en";

    public static final Set<String> COUNTRIES = Set.copyOf(Set.of(Locale.getISOCountries()));

    public static final Set<String> CURRENCIES = activeTwoDigitCurrencies();

    public static final Set<String> LANGUAGES = Set.of("en", "si", "ta");

    /** The database's {@code is_iana_time_zone} form; abbreviations and private names are refused. */
    private static final Pattern IANA = Pattern.compile("^(UTC|[A-Z][A-Za-z_]+(/[A-Za-z0-9_+-]+){1,2})$");

    private ProvisioningRegions() {
    }

    public static boolean timeZone(String zone) {
        return zone != null && IANA.matcher(zone).matches() && ZoneId.getAvailableZoneIds().contains(zone);
    }

    private static Set<String> activeTwoDigitCurrencies() {
        Set<String> codes = new TreeSet<>();
        for (String country : Locale.getISOCountries()) {
            try {
                Currency currency = Currency.getInstance(Locale.of("", country));
                if (currency != null && currency.getDefaultFractionDigits() == 2) {
                    codes.add(currency.getCurrencyCode());
                }
            }
            catch (IllegalArgumentException ex) {
                // A country the JDK knows no currency for contributes none.
            }
        }
        return Set.copyOf(codes);
    }
}
