package com.salonplatform.staff;

import com.salonplatform.branding.SettingsTestSupport;
import com.salonplatform.testing.Http;
import com.salonplatform.testing.OpenApiContract;
import com.salonplatform.testing.TestSalons.TestSalon;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Requests to the F04-S03 salon endpoints, each answer held to the committed contract, and the bodies they
 * send. Paths with an id, a year or a date are matched against the contract by their template.
 */
final class StaffSettingsFixture {

    static final String HOURS = "/api/v1/salon/opening-hours";
    static final String HOURS_SETTINGS = HOURS + "/settings";
    static final String HOLIDAYS = "/api/v1/salon/holidays";
    static final String CLOSURES = "/api/v1/salon/closures";

    static final List<String> DAYS =
            List.of("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday");

    private final SettingsTestSupport settings;
    private final OpenApiContract contract = OpenApiContract.committed();

    StaffSettingsFixture(SettingsTestSupport settings) {
        this.settings = settings;
    }

    Http.Exchange call(TestSalon salon, String method, String path, Object body, String token) {
        Http.Exchange exchange = settings.call(salon, method, path, body, token);
        contract.assertMatches(method, template(path), exchange);
        return exchange;
    }

    Map<String, Object> body(Http.Exchange exchange) {
        return settings.body(exchange);
    }

    static String template(String path) {
        String bare = path.replaceFirst("\\?.*$", "");
        return bare.replaceAll("/holidays/\\d{4}", "/holidays/{year}")
                .replaceAll("/days/\\d{4}-\\d{2}-\\d{2}$", "/days/{date}")
                .replaceAll("/closures/(?!impact)[^/]+$", "/closures/{id}");
    }

    /** A week every day of which is open between the two times, but for the closed days given. */
    static List<Map<String, Object>> week(String opens, String closes, String... closed) {
        List<Map<String, Object>> week = new ArrayList<>();
        List<String> shut = List.of(closed);
        for (String day : DAYS) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("day", day);
            boolean open = !shut.contains(day);
            entry.put("open", open);
            entry.put("opens", open ? opens : null);
            entry.put("closes", open ? closes : null);
            week.add(entry);
        }
        return week;
    }

    static Map<String, Object> save(long version, List<Map<String, Object>> week) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", version);
        body.put("weekly", week);
        return body;
    }

    static Map<String, Object> closure(String date, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("date", date);
        body.put("reason", reason);
        return body;
    }

    static Map<String, Object> choice(String date, String choice, String opens, String closes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("date", date);
        body.put("choice", choice);
        body.put("opens", opens);
        body.put("closes", closes);
        return body;
    }

    static Map<String, Object> accept(long version, List<Map<String, Object>> choices) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", version);
        body.put("choices", choices);
        return body;
    }
}
