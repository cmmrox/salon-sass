package com.salonplatform.staff;

import static com.salonplatform.staff.StaffSettingsFixture.CLOSURES;
import static com.salonplatform.staff.StaffSettingsFixture.HOURS;
import static com.salonplatform.staff.StaffSettingsFixture.HOURS_SETTINGS;
import static com.salonplatform.staff.StaffSettingsFixture.closure;
import static com.salonplatform.staff.StaffSettingsFixture.save;
import static com.salonplatform.staff.StaffSettingsFixture.week;
import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.branding.SettingsTestSupport;
import com.salonplatform.identity.adapter.IdentityCrypto;
import com.salonplatform.identity.domain.StaffRole;
import com.salonplatform.platform.OperatorTestSupport;
import com.salonplatform.platform.adapter.OperatorCrypto;
import com.salonplatform.platform.service.OperatorAccountService;
import com.salonplatform.testing.CapturedLogs;
import com.salonplatform.testing.Http;
import com.salonplatform.testing.IntegrationTest;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import com.salonplatform.testing.TestSalons;
import com.salonplatform.testing.TestSalons.TestSalon;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Weekly hours and special closures on the running API (F04-S03): AC-1 (save at once, nothing saved before the
 * first save, a closing time at or before the opening a field error), AC-2 (the public opening information, its
 * 180-day window, no closure reason, no other salon's hours), AC-7 (the closure window, one closure per date even
 * when two arrive at once, reopening) and AC-11 (stale versions, the audit trail, no reason in it or in the
 * logs). Every answer is held to the committed contract.
 */
class OpeningHoursAndClosuresIntegrationTest extends IntegrationTest {

    @Autowired
    IdentityCrypto identityCrypto;

    @Autowired
    OperatorAccountService operators;

    @Autowired
    OperatorCrypto operatorCrypto;

    @Autowired
    JsonMapper json;

    @Autowired
    Environment environment;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    Clock clock;

    @PersistenceContext
    EntityManager entities;

    private final JdbcTemplate owner = TestDatabase.of(TestPostgres.shared()).asOwner();
    private StaffSettingsFixture api;
    private SettingsTestSupport settings;
    private TestSalon alpha;
    private TestSalon beta;
    private String manager;
    private LocalDate today;

    @BeforeEach
    void twoSalons() {
        TestSalons salons = new TestSalons(owner);
        OperatorTestSupport console =
                new OperatorTestSupport(http(), port(), operators, operatorCrypto, json, environment, clock);
        settings = new SettingsTestSupport(
                http(), port(), salons, identityCrypto, json, transactions, entities, clock, console);
        api = new StaffSettingsFixture(settings);
        alpha = salons.create("Hours Alpha", "#D6086A");
        beta = salons.create("Hours Beta", "#1F2937");
        manager = settings.signedIn(alpha, StaffRole.MANAGER).token();
        today = LocalDate.ofInstant(clock.instant(), TestSalons.DEFAULT_TIME_ZONE);
    }

    private Map<String, Object> body(Http.Exchange exchange) {
        return api.body(exchange);
    }

    private long hoursRows(TestSalon salon) {
        return owner.queryForObject(
                "SELECT count(*) FROM staff_opening_hours WHERE tenant_id = ?", Long.class, salon.id());
    }

    private List<String> audit(TestSalon salon) {
        return owner.queryForList(
                "SELECT action || ' ' || coalesce(after_values::text, '') FROM audit_entry WHERE tenant_id = ?"
                        + " AND action LIKE 'staff.%' ORDER BY created_at, action",
                String.class, salon.id());
    }

    // --- AC-1 ------------------------------------------------------------------------------------------------

    @Test
    void beforeTheFirstSaveThereAreNoWeeklyHoursAnywhere() {
        Http.Exchange settingsBefore = api.call(alpha, "GET", HOURS_SETTINGS, null, manager);
        Http.Exchange publicBefore = api.call(alpha, "GET", HOURS, null, null);

        assertThat(settingsBefore.status()).isEqualTo(200);
        assertThat(body(settingsBefore))
                .containsEntry("version", 0)
                .containsEntry("weekly", null)
                .containsEntry("outsideHours", List.of());
        assertThat(publicBefore.status()).isEqualTo(200);
        assertThat(body(publicBefore)).containsEntry("weekly", null).containsEntry("exceptions", List.of());
        assertThat(publicBefore.headers()).containsEntry("cache-control", "no-store");
    }

    @Test
    void savingTheWeekAppliesAtOnceAndIsPublicOnTheNextRequest() {
        Http.Exchange saved =
                api.call(alpha, "PUT", HOURS_SETTINGS, save(0, week("09:00", "18:00", "sunday")), manager);

        assertThat(saved.status()).withFailMessage("%s", saved.body()).isEqualTo(200);
        assertThat(body(saved)).containsEntry("version", 1);
        Http.Exchange publicHours = api.call(alpha, "GET", HOURS, null, null);
        assertThat(body(publicHours).get("weekly")).isEqualTo(week("09:00", "18:00", "sunday"));

        Http.Exchange changed =
                api.call(alpha, "PUT", HOURS_SETTINGS, save(1, week("09:00", "19:00", "sunday", "saturday")), manager);
        assertThat(body(changed)).containsEntry("version", 2);
        assertThat(body(api.call(alpha, "GET", HOURS, null, null)).get("weekly"))
                .isEqualTo(week("09:00", "19:00", "sunday", "saturday"));
        assertThat(audit(alpha))
                .containsExactly(
                        "staff.opening_hours_saved {\"version\": 1, \"changedDays\": [\"monday\", \"tuesday\","
                                + " \"wednesday\", \"thursday\", \"friday\", \"saturday\", \"sunday\"]}",
                        "staff.opening_hours_saved {\"version\": 2, \"changedDays\": [\"monday\", \"tuesday\","
                                + " \"wednesday\", \"thursday\", \"friday\", \"saturday\"]}");
        assertThat(body(api.call(beta, "GET", HOURS, null, null))).containsEntry("weekly", null);
    }

    @Test
    void aClosingTimeAtOrBeforeTheOpeningIsAFieldErrorOnThatDayAndNothingIsSaved() {
        List<Map<String, Object>> week = new ArrayList<>(week("09:00", "18:00"));
        week.set(1, Map.of("day", "tuesday", "open", true, "opens", "10:00", "closes", "10:00"));
        week.set(2, Map.of("day", "wednesday", "open", true, "opens", "12:00", "closes", "11:45"));
        week.set(3, Map.of("day", "thursday", "open", true, "opens", "09:10", "closes", "18:00"));

        Http.Exchange refused = api.call(alpha, "PUT", HOURS_SETTINGS, save(0, week), manager);

        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body())
                .contains(
                        "\"field\":\"weekly[1].closes\"",
                        "\"field\":\"weekly[2].closes\"",
                        "\"field\":\"weekly[3].opens\"");
        Http.Exchange shortWeek = api.call(
                alpha, "PUT", HOURS_SETTINGS, save(0, week("09:00", "18:00").subList(0, 6)), manager);
        assertThat(shortWeek.status()).isEqualTo(400);
        assertThat(shortWeek.body()).contains("\"field\":\"weekly\"");
        assertThat(hoursRows(alpha)).isZero();
        assertThat(audit(alpha)).isEmpty();
    }

    // --- AC-11 -----------------------------------------------------------------------------------------------

    @Test
    void aStaleVersionIsAConflictAndOfManyFirstSavesAtOnceExactlyOneSucceeds() throws Exception {
        List<Callable<Integer>> saves = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            saves.add(() -> api.call(alpha, "PUT", HOURS_SETTINGS, save(0, week("09:00", "18:00")), manager)
                    .status());
        }
        List<Integer> statuses = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Future<Integer> result : executor.invokeAll(saves)) {
                statuses.add(result.get());
            }
        }

        assertThat(statuses).filteredOn(status -> status == 200).hasSize(1);
        assertThat(statuses).filteredOn(status -> status == 409).hasSize(7);
        Http.Exchange stale = api.call(alpha, "PUT", HOURS_SETTINGS, save(0, week("08:00", "17:00")), manager);
        assertThat(stale.status()).isEqualTo(409);
        assertThat(body(stale)).containsEntry("code", "conflict");
        assertThat(body(api.call(alpha, "GET", HOURS_SETTINGS, null, manager))).containsEntry("version", 1);
    }

    // --- AC-2, AC-7, AC-11 -----------------------------------------------------------------------------------

    @Test
    void aClosureClosesTheDayPubliclyWithoutItsReasonAndReopeningRemovesIt() {
        String reason =
                "Staff training " + java.util.UUID.randomUUID().toString().substring(0, 8);
        LocalDate day = today.plusDays(3);
        Http.Exchange added;
        try (CapturedLogs logs = CapturedLogs.ofApplication()) {
            added = api.call(alpha, "POST", CLOSURES, closure(day.toString(), "  " + reason + " "), manager);
            assertThat(logs.events())
                    .noneMatch(event -> event.getFormattedMessage().contains(reason));
        }

        assertThat(added.status()).withFailMessage("%s", added.body()).isEqualTo(201);
        assertThat(body(added))
                .containsEntry("date", day.toString())
                .containsEntry("reason", reason)
                .containsEntry("affectedBookings", 0);
        Http.Exchange publicHours = api.call(alpha, "GET", HOURS, null, null);
        assertThat(publicHours.body()).doesNotContain(reason);
        Map<String, Object> closed = new java.util.LinkedHashMap<>();
        closed.put("date", day.toString());
        closed.put("kind", "closed");
        closed.put("opens", null);
        closed.put("closes", null);
        closed.put("holidayName", null);
        assertThat(body(publicHours).get("exceptions")).isEqualTo(List.of(closed));
        assertThat(body(api.call(beta, "GET", HOURS, null, null))).containsEntry("exceptions", List.of());
        List<?> upcoming = (List<?>)
                json.readValue(api.call(alpha, "GET", CLOSURES, null, manager).body(), List.class);
        assertThat(upcoming).hasSize(1);

        Http.Exchange reopened =
                api.call(alpha, "DELETE", CLOSURES + "/" + body(added).get("id"), null, manager);
        assertThat(reopened.status()).isEqualTo(204);
        assertThat(body(api.call(alpha, "GET", HOURS, null, null))).containsEntry("exceptions", List.of());
        assertThat(audit(alpha))
                .containsExactly(
                        "staff.closure_added {\"date\": \"" + day + "\"}",
                        "staff.closure_reopened {\"date\": \"" + day + "\"}");
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM audit_entry WHERE tenant_id = ? AND (after_values::text LIKE ?"
                                + " OR coalesce(reason, '') LIKE ?)",
                        Long.class,
                        alpha.id(),
                        "%" + reason + "%",
                        "%" + reason + "%"))
                .isZero();
    }

    @Test
    void aClosureIsTodayToAYearAheadWithAReasonOfAtMostSixtyCharacters() {
        for (String date : List.of(
                today.minusDays(1).toString(), today.plusYears(1).plusDays(1).toString(), "2026-02-30")) {
            Http.Exchange refused = api.call(alpha, "POST", CLOSURES, closure(date, null), manager);
            assertThat(refused.status()).as(date).isEqualTo(400);
            assertThat(refused.body()).contains("\"field\":\"date\"");
        }
        Http.Exchange longReason =
                api.call(alpha, "POST", CLOSURES, closure(today.toString(), "x".repeat(61)), manager);
        assertThat(longReason.status()).isEqualTo(400);
        assertThat(longReason.body()).contains("\"field\":\"reason\"");
        Http.Exchange badImpact =
                api.call(alpha, "GET", CLOSURES + "/impact?date=" + today.minusDays(1), null, manager);
        assertThat(badImpact.status()).isEqualTo(400);

        assertThat(api.call(alpha, "POST", CLOSURES, closure(today.toString(), ""), manager)
                        .status())
                .isEqualTo(201);
        assertThat(api.call(alpha, "POST", CLOSURES, closure(today.plusYears(1).toString(), null), manager)
                        .status())
                .isEqualTo(201);
        Http.Exchange impact = api.call(alpha, "GET", CLOSURES + "/impact?date=" + today.plusDays(9), null, manager);
        assertThat(body(impact)).isEqualTo(Map.of("date", today.plusDays(9).toString(), "bookings", List.of()));
    }

    @Test
    void ofTwoClosuresForOneDateSentAtOnceExactlyOneIsKeptAndTheOtherIsAConflict() throws Exception {
        LocalDate day = today.plusDays(20);
        List<Callable<Integer>> both = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            both.add(() -> api.call(alpha, "POST", CLOSURES, closure(day.toString(), null), manager)
                    .status());
        }
        List<Integer> statuses = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Future<Integer> result : executor.invokeAll(both)) {
                statuses.add(result.get());
            }
        }

        assertThat(statuses).filteredOn(status -> status == 201).hasSize(1);
        assertThat(statuses).filteredOn(status -> status == 409).hasSize(5);
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM staff_closure WHERE tenant_id = ? AND closure_date = ?",
                        Long.class,
                        alpha.id(),
                        java.sql.Date.valueOf(day)))
                .isEqualTo(1L);
    }

    @Test
    void thePublicExceptionsReachExactly180DaysAhead() {
        api.call(alpha, "POST", CLOSURES, closure(today.plusDays(180).toString(), null), manager);
        api.call(alpha, "POST", CLOSURES, closure(today.plusDays(181).toString(), null), manager);

        List<?> exceptions =
                (List<?>) api.body(api.call(alpha, "GET", HOURS, null, null)).get("exceptions");
        assertThat(exceptions.stream()
                        .map(exception -> (Object) ((Map<?, ?>) exception).get("date"))
                        .toList())
                .containsExactly((Object) today.plusDays(180).toString());
    }
}
