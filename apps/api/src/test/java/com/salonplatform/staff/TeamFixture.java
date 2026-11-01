package com.salonplatform.staff;

import com.salonplatform.branding.SettingsTestSupport;
import com.salonplatform.testing.Http;
import com.salonplatform.testing.OpenApiContract;
import com.salonplatform.testing.TestSalons.TestSalon;
import java.time.DayOfWeek;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Requests to the F06-S01 team endpoints, each answer held to the committed contract, and the bodies they send. Paths
 * with an id are matched against the contract by their template.
 */
final class TeamFixture {

    static final String MEMBERS = "/api/v1/team/members";
    static final String ACCOUNTS = "/api/v1/team/sign-in-accounts";
    static final String PUBLIC = "/api/v1/team/public";
    static final String PHOTOS = "/api/v1/team/photos/";
    static final String IDENTITY_STAFF = "/api/v1/identity/staff";
    static final String ENTITLEMENTS = "/api/v1/salon/entitlements";

    private final SettingsTestSupport settings;
    private final OpenApiContract contract = OpenApiContract.committed();

    TeamFixture(SettingsTestSupport settings) {
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

    /** A person with this name and no sign-in, shown online, performing nothing. */
    static Map<String, Object> person(String name) {
        Map<String, Object> person = new LinkedHashMap<>();
        person.put("name", name);
        return person;
    }

    /** The same person, linked to an account. */
    static Map<String, Object> linked(String name, Object accountId) {
        Map<String, Object> person = person(name);
        person.put("signInAccountId", accountId);
        return person;
    }

    /** What a read member sends back to save it, with the version it was read at. */
    static Map<String, Object> saveOf(Map<String, Object> member) {
        Map<String, Object> save = new LinkedHashMap<>();
        for (String field : List.of(
                "name", "title", "highlights", "bio", "shownOnline", "services", "signInAccountId", "version")) {
            save.put(field, member.get(field));
        }
        return save;
    }

    static Map<String, Object> services(List<UUID> categoryIds, List<UUID> serviceIds) {
        Map<String, Object> services = new LinkedHashMap<>();
        services.put("categoryIds", categoryIds);
        services.put("serviceIds", serviceIds);
        return services;
    }

    static Map<String, Object> account(String email, String role) {
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("email", email);
        account.put("password", "synthetic initial password");
        account.put("role", role);
        return account;
    }

    /** Where a member's schedule is. */
    static String schedule(Object memberId) {
        return MEMBERS + "/" + memberId + "/schedule";
    }

    static String weekly(Object memberId) {
        return schedule(memberId) + "/weekly";
    }

    static String dateChanges(Object memberId) {
        return schedule(memberId) + "/date-changes";
    }

    static Map<String, Object> pause(String starts, String finishes) {
        Map<String, Object> pause = new LinkedHashMap<>();
        pause.put("starts", starts);
        pause.put("finishes", finishes);
        return pause;
    }

    /** A working weekday as a save sends it. The array is only read. */
    @SafeVarargs
    @SuppressWarnings("varargs")
    static Map<String, Object> workday(String day, String starts, String finishes, Map<String, Object>... breaks) {
        Map<String, Object> workday = new LinkedHashMap<>();
        workday.put("day", day);
        workday.put("working", true);
        workday.put("starts", starts);
        workday.put("finishes", finishes);
        workday.put("breaks", Arrays.stream(breaks).toList());
        return workday;
    }

    /** The seven weekdays as a save sends them: not working, but for the days given. The array is only read. */
    @SafeVarargs
    @SuppressWarnings("varargs")
    static List<Map<String, Object>> week(Map<String, Object>... working) {
        return Arrays.stream(DayOfWeek.values())
                .map(day -> day.name().toLowerCase(Locale.ROOT))
                .map(name -> Arrays.stream(working)
                        .filter(each -> name.equals(each.get("day")))
                        .findFirst()
                        .orElseGet(() -> offDay(name)))
                .toList();
    }

    static Map<String, Object> offDay(String day) {
        Map<String, Object> off = new LinkedHashMap<>();
        off.put("day", day);
        off.put("working", false);
        off.put("starts", null);
        off.put("finishes", null);
        off.put("breaks", List.of());
        return off;
    }

    /** A save of a week from the version read. */
    static Map<String, Object> weekSave(long version, List<Map<String, Object>> week) {
        Map<String, Object> save = new LinkedHashMap<>();
        save.put("version", version);
        save.put("weekly", week);
        return save;
    }

    /** A date with different hours, as a request sends it. The array is only read. */
    @SafeVarargs
    @SuppressWarnings("varargs")
    static Map<String, Object> differentHours(
            Object date, String starts, String finishes, String note, Map<String, Object>... breaks) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("date", date.toString());
        change.put("working", true);
        change.put("starts", starts);
        change.put("finishes", finishes);
        change.put("breaks", Arrays.stream(breaks).toList());
        change.put("note", note);
        return change;
    }

    /** A date not worked, as a request sends it. */
    static Map<String, Object> notWorking(Object date, String note) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("date", date.toString());
        change.put("working", false);
        change.put("note", note);
        return change;
    }

    static String email(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
    }

    private static String template(String path) {
        String bare = path.replaceFirst("\\?.*$", "");
        return bare.replaceAll(
                        "/team/members/[^/]+/schedule/date-changes/[^/]+$",
                        "/team/members/{id}/schedule/date-changes/{changeId}")
                .replaceAll("/team/members/[^/]+/schedule/date-changes$", "/team/members/{id}/schedule/date-changes")
                .replaceAll("/team/members/[^/]+/schedule/weekly$", "/team/members/{id}/schedule/weekly")
                .replaceAll("/team/members/[^/]+/schedule$", "/team/members/{id}/schedule")
                .replaceAll("/team/members/[^/]+/photo$", "/team/members/{id}/photo")
                .replaceAll("/team/members/[^/]+$", "/team/members/{id}")
                .replaceAll("/team/photos/[^/]+$", "/team/photos/{id}")
                .replaceAll("/identity/staff/[^/]+$", "/identity/staff/{id}");
    }
}
