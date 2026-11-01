package com.salonplatform.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.salonplatform.platform.adapter.OperatorCrypto;
import com.salonplatform.platform.service.OperatorAccountService;
import com.salonplatform.testing.Http;
import com.salonplatform.testing.IntegrationTest;
import com.salonplatform.testing.OpenApiContract;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import com.salonplatform.testing.TestSalons;
import com.salonplatform.testing.TestSalons.TestSalon;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * The operator's holiday calendar (F04-S03, AC-3 and AC-6, the platform side): a CSV import replaces a year's
 * draft all or nothing; single days are added, changed and removed; Support reads and is refused every change;
 * publication names its version and refuses a stale one or one that changes nothing, and the console shows what
 * a revision adds, changes and withdraws; every import, edit and publication is in the platform audit. The
 * tenant role reaches only the published days, through the one function, and the routes answer on the console
 * host alone. The calendar is shared by every test, so each test works on a year of its own.
 */
class HolidayCalendarIntegrationTest extends IntegrationTest {

    private static final String BASE = "/api/v1/operator/holiday-calendars";

    @Autowired
    OperatorAccountService operators;

    @Autowired
    OperatorCrypto crypto;

    @Autowired
    JsonMapper json;

    @Autowired
    Environment environment;

    @Autowired
    Clock clock;

    @Autowired
    JdbcClient applicationJdbc;

    private final JdbcTemplate owner = TestDatabase.of(TestPostgres.shared()).asOwner();
    private final OpenApiContract contract = OpenApiContract.committed();
    private OperatorTestSupport console;
    private String admin;

    @BeforeEach
    void console() {
        console = new OperatorTestSupport(http(), port(), operators, crypto, json, environment, clock);
        admin = console.token(console.create("OPERATOR_ADMIN"));
    }

    private Http.Exchange call(String method, String path, Object body, String token) {
        Http.Exchange exchange = console.call(method, "/holiday-calendars" + path, body, token);
        contract.assertMatches(method, BASE + template(path), exchange);
        return exchange;
    }

    /** The path as the contract names it: the year and the date as their parameters. */
    private static String template(String path) {
        return path.replaceAll("/\\d{4}-\\d{2}-\\d{2}", "/{date}").replaceFirst("^/\\d{4}", "/{year}");
    }

    private Http.Exchange importCsv(int year, String csv, String token) {
        String boundary = "----salon" + UUID.randomUUID().toString().replace("-", "");
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + year
                + ".csv\"\r\nContent-Type: text/csv\r\n\r\n" + csv + "\r\n--" + boundary + "--\r\n";
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "multipart/form-data; boundary=" + boundary);
        headers.put("Accept", "application/json");
        headers.put("Authorization", "Bearer " + token);
        Http.Exchange exchange = http().sendBytesOnHost(
                        "POST",
                        console.host(),
                        BASE + "/" + year + "/import",
                        headers,
                        body.getBytes(StandardCharsets.UTF_8))
                .asText();
        contract.assertMatches("POST", BASE + "/{year}/import", exchange);
        return exchange;
    }

    private Map<String, Object> body(Http.Exchange exchange) {
        return console.body(exchange);
    }

    // The year's days as the untyped JSON maps the console answers.
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> days(Map<String, Object> year) {
        return (List<Map<String, Object>>) year.get("days");
    }

    private Map<String, Object> year(int year) {
        Http.Exchange detail = call("GET", "/" + year, null, admin);
        assertThat(detail.status()).withFailMessage("%s", detail.body()).isEqualTo(200);
        return body(detail);
    }

    private static Map<String, Object> day(String date, String name, String kind) {
        return Map.of("date", date, "name", name, "kind", kind);
    }

    private long version(int year) {
        return ((Number) year(year).get("version")).longValue();
    }

    private int auditCount(int year, String action) {
        return owner.queryForObject(
                "SELECT count(*) FROM platform_audit_entry WHERE subject_kind = 'holiday_year' AND subject_id = ?"
                        + " AND action = ?",
                Integer.class,
                String.valueOf(year),
                action);
    }

    @Test
    void aValidFileReplacesTheDraftAndIsAudited() {
        Http.Exchange first = importCsv(2095, """
                date,name,kind
                2095-01-14,Tamil Thai Pongal Day,public
                2095-02-04,Independence Day,public
                2095-02-10,Navam Full Moon Poya Day,poya
                """, admin);
        assertThat(first.status()).withFailMessage("%s", first.body()).isEqualTo(200);
        assertThat(body(first)).containsEntry("state", "draft").containsEntry("revision", 0);
        assertThat(days(body(first)))
                .extracting(d -> d.get("change"))
                .containsOnly("added")
                .hasSize(3);

        Http.Exchange second =
                importCsv(2095, "2095-05-01,May Day,mercantile\n2095-12-25,Christmas Day,public\n", admin);

        assertThat(second.status()).isEqualTo(200);
        assertThat(days(body(second)))
                .extracting(d -> d.get("date"), d -> d.get("name"), d -> d.get("kind"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("2095-05-01", "May Day", "mercantile"),
                        org.assertj.core.groups.Tuple.tuple("2095-12-25", "Christmas Day", "public"));
        assertThat(auditCount(2095, "holiday_calendar.imported")).isEqualTo(2);
        Http.Exchange list = call("GET", "", null, admin);
        assertThat(list.status()).isEqualTo(200);
    }

    // The errors of a refusal as the untyped JSON maps the problem body holds.
    @SuppressWarnings("unchecked")
    @Test
    void aFileWithAnyBadLineIsRefusedWholeNamingEachLineAndChangesNothing() {
        assertThat(importCsv(2096, "2096-01-14,Tamil Thai Pongal Day,public\n", admin)
                        .status())
                .isEqualTo(200);
        long before = version(2096);

        Http.Exchange refused = importCsv(2096, """
                date,name,kind
                2095-12-25,Christmas Day,public
                2096-02-04,Independence Day,public
                2096-02-04,Independence Day again,public
                2096-03-01,Unknown,festival
                2096-03-02,,poya
                2096-03-03,two fields
                """, admin);

        assertThat(refused.status()).isEqualTo(400);
        assertThat(body(refused)).containsEntry("code", "bad_request");
        assertThat((List<Map<String, Object>>) body(refused).get("errors"))
                .extracting(e -> e.get("field"), e -> e.get("line"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("file", 2),
                        org.assertj.core.groups.Tuple.tuple("file", 4),
                        org.assertj.core.groups.Tuple.tuple("file", 5),
                        org.assertj.core.groups.Tuple.tuple("file", 6),
                        org.assertj.core.groups.Tuple.tuple("file", 7));
        assertThat(version(2096)).isEqualTo(before);
        assertThat(days(year(2096))).extracting(d -> d.get("date")).containsExactly("2096-01-14");
    }

    @Test
    void singleDaysAreAddedChangedAndRemoved() {
        Http.Exchange added = call("POST", "/2097/days", day("2097-04-13", "New Year Eve", "mercantile"), admin);
        assertThat(added.status()).withFailMessage("%s", added.body()).isEqualTo(200);
        assertThat(call("POST", "/2097/days", day("2097-04-13", "Again", "public"), admin)
                        .status())
                .isEqualTo(409);
        Http.Exchange bad = call("POST", "/2097/days", day("2098-01-01", " ", "festival"), admin);
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.body()).contains("\"field\":\"date\"", "\"field\":\"name\"", "\"field\":\"kind\"");

        Http.Exchange changed =
                call("PUT", "/2097/days/2097-04-13", Map.of("name", "Day prior to New Year", "kind", "public"), admin);
        assertThat(changed.status()).isEqualTo(200);
        assertThat(days(body(changed)))
                .singleElement()
                .satisfies(d -> assertThat(d)
                        .containsEntry("name", "Day prior to New Year")
                        .containsEntry("kind", "public"));
        assertThat(call("PUT", "/2097/days/2097-05-01", Map.of("name", "x", "kind", "public"), admin)
                        .status())
                .isEqualTo(404);

        assertThat(call("DELETE", "/2097/days/2097-04-13", null, admin).status())
                .isEqualTo(200);
        assertThat(call("DELETE", "/2097/days/2097-04-13", null, admin).status())
                .isEqualTo(404);
        assertThat(auditCount(2097, "holiday_calendar.day_added")).isEqualTo(1);
        assertThat(auditCount(2097, "holiday_calendar.day_changed")).isEqualTo(1);
        assertThat(auditCount(2097, "holiday_calendar.day_removed")).isEqualTo(1);
        assertThat(call("GET", "/1999", null, admin).status()).isEqualTo(404);
    }

    @Test
    void supportReadsTheCalendarAndIsRefusedEveryChange() {
        assertThat(call("POST", "/2093/days", day("2093-01-14", "Pongal", "public"), admin)
                        .status())
                .isEqualTo(200);
        String support = console.token(console.create("OPERATOR_SUPPORT"));
        long before = version(2093);

        assertThat(call("GET", "", null, support).status()).isEqualTo(200);
        assertThat(call("GET", "/2093", null, support).status()).isEqualTo(200);
        for (Http.Exchange refused : List.of(
                importCsv(2093, "2093-02-04,Independence Day,public\n", support),
                call("POST", "/2093/days", day("2093-02-04", "Independence Day", "public"), support),
                call("PUT", "/2093/days/2093-01-14", Map.of("name", "x", "kind", "public"), support),
                call("DELETE", "/2093/days/2093-01-14", null, support),
                call("POST", "/2093/publication", Map.of("version", before), support))) {
            assertThat(refused.status()).isEqualTo(403);
            assertThat(body(refused)).containsEntry("code", "permission_denied");
        }
        assertThat(version(2093)).isEqualTo(before);
    }

    @Test
    void publicationNamesItsVersionAndTheConsoleCountsWhatARevisionChanges() {
        call("POST", "/2098/days", day("2098-01-14", "Pongal", "public"), admin);
        call("POST", "/2098/days", day("2098-02-04", "Independence Day", "public"), admin);
        call("POST", "/2098/days", day("2098-02-10", "Navam Poya", "poya"), admin);
        long version = version(2098);

        assertThat(call("POST", "/2098/publication", Map.of("version", version - 1), admin)
                        .status())
                .isEqualTo(409);
        Http.Exchange published = call("POST", "/2098/publication", Map.of("version", version), admin);
        assertThat(published.status()).withFailMessage("%s", published.body()).isEqualTo(200);
        assertThat(body(published)).containsEntry("state", "published").containsEntry("revision", 1);
        Http.Exchange again = call("POST", "/2098/publication", Map.of("version", version(2098)), admin);
        assertThat(again.status()).isEqualTo(409);
        assertThat(body(again)).containsEntry("code", "invalid_state");

        call("PUT", "/2098/days/2098-02-10", Map.of("name", "Navam Full Moon Poya Day", "kind", "poya"), admin);
        call("DELETE", "/2098/days/2098-01-14", null, admin);
        call("POST", "/2098/days", day("2098-03-11", "Medin Poya", "poya"), admin);
        Map<String, Object> pending = year(2098);
        assertThat(pending).containsEntry("state", "changed");
        assertThat(pending.get("changes")).isEqualTo(Map.of("added", 1, "changed", 1, "withdrawn", 1));
        assertThat(days(pending))
                .extracting(d -> d.get("date"), d -> d.get("change"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("2098-02-04", "unchanged"),
                        org.assertj.core.groups.Tuple.tuple("2098-02-10", "changed"),
                        org.assertj.core.groups.Tuple.tuple("2098-03-11", "added"));

        Http.Exchange revision = call("POST", "/2098/publication", Map.of("version", pending.get("version")), admin);
        assertThat(revision.status()).isEqualTo(200);
        assertThat(body(revision)).containsEntry("revision", 2).containsEntry("state", "published");
        assertThat(owner.queryForList(
                        "SELECT holiday_date::text || ':' || revision FROM platform_holiday_published_day"
                                + " WHERE year = 2098 ORDER BY holiday_date",
                        String.class))
                .containsExactly("2098-02-04:1", "2098-02-10:2", "2098-03-11:2");
        assertThat(auditCount(2098, "holiday_calendar.published")).isEqualTo(2);
    }

    @Test
    void twoAdminsPublishingTheSameVersionAtOnceGiveOnePublicationAndOneConflict() throws Exception {
        call("POST", "/2099/days", day("2099-01-14", "Pongal", "public"), admin);
        long version = version(2099);
        String other = console.token(console.create("OPERATOR_ADMIN"));
        List<Callable<Integer>> both = List.of(
                () -> call("POST", "/2099/publication", Map.of("version", version), admin)
                        .status(),
                () -> call("POST", "/2099/publication", Map.of("version", version), other)
                        .status());

        List<Integer> statuses = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Future<Integer> result : executor.invokeAll(both)) {
                statuses.add(result.get());
            }
        }

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(owner.queryForObject("SELECT revision FROM platform_holiday_year WHERE year = 2099", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void theTenantRoleReadsOnlyPublishedDaysThroughItsOneFunction() {
        call("POST", "/2094/days", day("2094-01-14", "Draft only", "public"), admin);

        for (String table :
                List.of("platform_holiday_year", "platform_holiday_draft_day", "platform_holiday_published_day")) {
            assertThatExceptionOfType(DataAccessException.class)
                    .as(table)
                    .isThrownBy(() -> applicationJdbc
                            .sql("SELECT count(*) FROM " + table)
                            .query(Long.class)
                            .single())
                    .satisfies(
                            refused -> assertThat(refused.getMostSpecificCause().getMessage())
                                    .contains("permission denied"));
        }
        assertThat(applicationJdbc
                        .sql("SELECT count(*) FROM holiday_calendar_published(2094, 2094)")
                        .query(Long.class)
                        .single())
                .as("a year never published")
                .isZero();
    }

    @Test
    void theCalendarRoutesAnswerOnTheConsoleHostAloneAndNotOnASalons() {
        TestSalon salon = new TestSalons(owner).create("Holiday Host", "#123456");
        Http.Exchange onSalon = console.callOn(salon.host(port()), "GET", "/holiday-calendars", null, admin, Map.of());
        assertThat(onSalon.status()).isEqualTo(404);
        assertThat(body(onSalon)).containsEntry("code", "not_found");
        assertThat(call("GET", "", null, null).status()).isEqualTo(401);
    }
}
