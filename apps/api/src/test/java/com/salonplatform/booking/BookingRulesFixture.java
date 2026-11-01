package com.salonplatform.booking;

import com.salonplatform.branding.SettingsTestSupport;
import com.salonplatform.testing.Http;
import com.salonplatform.testing.OpenApiContract;
import com.salonplatform.testing.TestSalons.TestSalon;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Requests to the F04-S04 booking-rules endpoints, each answer held to the committed contract, and the bodies
 * they send. The suggested rules are the prototype's (AC-1).
 */
final class BookingRulesFixture {

    static final String RULES = "/api/v1/salon/booking-rules";
    static final String SETTINGS = RULES + "/settings";
    static final String HISTORY = RULES + "/history";

    private final SettingsTestSupport settings;
    private final OpenApiContract contract = OpenApiContract.committed();

    BookingRulesFixture(SettingsTestSupport settings) {
        this.settings = settings;
    }

    Http.Exchange call(TestSalon salon, String method, String path, Object body, String token) {
        Http.Exchange exchange = settings.call(salon, method, path, body, token);
        contract.assertMatches(method, path.replaceFirst("\\?.*$", ""), exchange);
        return exchange;
    }

    Map<String, Object> body(Http.Exchange exchange) {
        return settings.body(exchange);
    }

    /** The prototype's suggested rules: 15 minutes, 1 hour, 2 months, 20% up to Rs. 2,000, 24 hours, ... */
    static Map<String, Object> suggested() {
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("slotIntervalMinutes", 15);
        rules.put("minimumNoticeMinutes", 60);
        rules.put("bookingHorizonDays", 60);
        rules.put("deposit", map("kind", "percentage", "percent", 20, "capMinor", 200_000L));
        rules.put("freeCancellationHours", 24);
        rules.put("lateCancellation", map("kind", "deposit"));
        rules.put("noShow", map("kind", "deposit"));
        rules.put("reschedule", map("limit", 2, "untilHours", 12));
        return rules;
    }

    /** The suggested rules with one rule replaced. */
    static Map<String, Object> with(String rule, Object value) {
        Map<String, Object> rules = suggested();
        rules.put(rule, value);
        return rules;
    }

    static Map<String, Object> save(long version, Map<String, Object> rules) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", version);
        body.put("rules", rules);
        return body;
    }

    /** A map in the order given, which may hold nulls (Map.of may not). */
    static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }
}
