package com.salonplatform.staff;

import static com.salonplatform.staff.StaffSettingsFixture.CLOSURES;
import static com.salonplatform.staff.StaffSettingsFixture.HOLIDAYS;
import static com.salonplatform.staff.StaffSettingsFixture.HOURS;
import static com.salonplatform.staff.StaffSettingsFixture.HOURS_SETTINGS;
import static com.salonplatform.staff.StaffSettingsFixture.accept;
import static com.salonplatform.staff.StaffSettingsFixture.choice;
import static com.salonplatform.staff.StaffSettingsFixture.closure;
import static com.salonplatform.staff.StaffSettingsFixture.save;
import static com.salonplatform.staff.StaffSettingsFixture.week;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.groups.Tuple.tuple;

import com.salonplatform.branding.SettingsTestSupport;
import com.salonplatform.identity.adapter.IdentityCrypto;
import com.salonplatform.identity.domain.StaffRole;
import com.salonplatform.platform.OperatorTestSupport;
import com.salonplatform.platform.adapter.OperatorCrypto;
import com.salonplatform.platform.service.OperatorAccountService;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.shared.web.NotFoundException;
import com.salonplatform.testing.Http;
import com.salonplatform.testing.IntegrationTest;
import com.salonplatform.testing.MovableClock;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import com.salonplatform.testing.TestSalons;
import com.salonplatform.testing.TestSalons.TestSalon;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * The operator's published holidays reaching salons, and when a branch is open, with the test clock (F04-S03):
 * AC-4 (a published year is to review and changes nothing until accepted), AC-5 (accepted choices apply at once
 * and only for that salon; one day changes on its own afterwards), AC-6 (a revision puts back to review only the
 * days it added or changed; a withdrawn day's choice stops applying), AC-8 ({@link OpeningSchedule}'s priority,
 * and the bookings a change affects through a test {@link BookingsOnDate}, all kept), and AC-9 (today and past in
 * the salon's time zone). The calendar is shared platform data, so each test works on a year of its own.
 */
// Its movable clock and test bookings make a context of its own, closed after this class to free its pools.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HolidaysAndScheduleIntegrationTest extends IntegrationTest {

    static final MovableClock CLOCK = new MovableClock(Instant.parse("2081-03-10T06:00:00Z"));
    static final RecordedBookings BOOKINGS = new RecordedBookings();

    @TestConfiguration
    static class Overrides {
        @Bean
        @Primary
        Clock movableClock() {
            return CLOCK;
        }

        @Bean
        @Primary
        BookingsOnDate recordedBookings() {
            return BOOKINGS;
        }
    }

    /** Bookings a test sets for a branch, as F07's implementation would answer them. Never changed by staff. */
    static final class RecordedBookings implements BookingsOnDate {
        final Map<UUID, Map<LocalDate, List<AffectedBooking>>> onDate = new ConcurrentHashMap<>();
        final Map<UUID, List<AffectedBooking>> outside = new ConcurrentHashMap<>();

        @Override
        public List<AffectedBooking> on(UUID branchId, LocalDate date) {
            return onDate.getOrDefault(branchId, Map.of()).getOrDefault(date, List.of());
        }

        @Override
        public List<AffectedBooking> outsideHours(UUID branchId, List<WeekdayHours> week, LocalDate from) {
            return outside.getOrDefault(branchId, List.of());
        }
    }

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
    OpeningSchedule schedule;

    @PersistenceContext
    EntityManager entities;

    private final JdbcTemplate owner = TestDatabase.of(TestPostgres.shared()).asOwner();
    private TestSalons salons;
    private OperatorTestSupport console;
    private SettingsTestSupport settings;
    private StaffSettingsFixture api;
    private TestSalon alpha;
    private TestSalon beta;

    @BeforeEach
    void twoSalons() {
        salons = new TestSalons(owner);
        console = new OperatorTestSupport(http(), port(), operators, operatorCrypto, json, environment, CLOCK);
        settings = new SettingsTestSupport(
                http(), port(), salons, identityCrypto, json, transactions, entities, CLOCK, console);
        api = new StaffSettingsFixture(settings);
        alpha = salons.create("Calendar Alpha", "#D6086A");
        beta = salons.create("Calendar Beta", "#1F2937");
    }

    // --- the operator's side ----------------------------------------------------------------------------------

    private String admin() {
        return console.token(console.create("OPERATOR_ADMIN"));
    }

    private void addDay(String admin, String date, String name, String kind) {
        int year = LocalDate.parse(date).getYear();
        Http.Exchange added = console.call(
                "POST",
                "/holiday-calendars/" + year + "/days",
                Map.of("date", date, "name", name, "kind", kind),
                admin);
        assertThat(added.status()).withFailMessage("%s", added.body()).isEqualTo(200);
    }

    private Map<String, Object> publish(String admin, int year) {
        Map<String, Object> detail = console.body(console.call("GET", "/holiday-calendars/" + year, null, admin));
        Http.Exchange published = console.call(
                "POST", "/holiday-calendars/" + year + "/publication", Map.of("version", detail.get("version")), admin);
        assertThat(published.status()).withFailMessage("%s", published.body()).isEqualTo(200);
        return detail;
    }

    // --- the salon's side -------------------------------------------------------------------------------------

    private String manager(TestSalon salon) {
        return settings.signedIn(salon, StaffRole.MANAGER).token();
    }

    // The year as the untyped JSON maps the API answers.
    @SuppressWarnings("unchecked")
    private Map<String, Object> holidayYear(TestSalon salon, String token, int year) {
        Http.Exchange years = api.call(salon, "GET", HOLIDAYS, null, token);
        assertThat(years.status()).withFailMessage("%s", years.body()).isEqualTo(200);
        return ((List<Map<String, Object>>) api.body(years).get("years"))
                .stream()
                        .filter(each -> ((Number) each.get("year")).intValue() == year)
                        .findFirst()
                        .orElseThrow();
    }

    // The days as the untyped JSON maps the API answers.
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> days(Map<String, Object> year) {
        return (List<Map<String, Object>>) year.get("days");
    }

    // The exceptions as the untyped JSON maps the API answers.
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> exceptions(TestSalon salon) {
        return (List<Map<String, Object>>)
                api.body(api.call(salon, "GET", HOURS, null, null)).get("exceptions");
    }

    private OpeningDay openingOn(TestSalon salon, LocalDate date) throws Exception {
        return TenantContext.callAs(salons.asCurrent(salon), () -> schedule.on(salons.defaultBranch(salon), date));
    }

    private static long version(Map<String, Object> year) {
        return ((Number) year.get("version")).longValue();
    }

    // --- AC-4 ------------------------------------------------------------------------------------------------

    @Test
    void aPublishedYearIsToReviewWithItsComingDaysAndChangesNothingUntilAccepted() throws Exception {
        CLOCK.set(Instant.parse("2081-03-10T06:00:00Z"));
        String admin = admin();
        addDay(admin, "2081-01-14", "Tamil Thai Pongal Day", "public");
        addDay(admin, "2081-03-20", "Medin Full Moon Poya Day", "poya");
        addDay(admin, "2081-04-14", "Sinhala and Tamil New Year Day", "public");
        publish(admin, 2081);
        String token = manager(alpha);
        api.call(alpha, "PUT", HOURS_SETTINGS, save(0, week("09:00", "18:00")), token);

        Map<String, Object> year = holidayYear(alpha, token, 2081);

        assertThat(year)
                .containsEntry("reviewed", false)
                .containsEntry("version", 0)
                .containsEntry("revision", 1);
        assertThat(days(year))
                .extracting(
                        d -> d.get("date"),
                        d -> d.get("kind"),
                        d -> d.get("choice"),
                        d -> d.get("toReview"),
                        d -> d.get("past"))
                .containsExactly(
                        tuple("2081-01-14", "public", null, true, true),
                        tuple("2081-03-20", "poya", null, true, false),
                        tuple("2081-04-14", "public", null, true, false));
        assertThat(exceptions(alpha)).isEmpty();
        assertThat(openingOn(alpha, LocalDate.of(2081, 3, 20)))
                .isEqualTo(new OpeningDay(
                        LocalDate.of(2081, 3, 20),
                        true,
                        LocalTime.of(9, 0),
                        LocalTime.of(18, 0),
                        OpeningReason.WEEKLY_HOURS,
                        "Medin Full Moon Poya Day"));
    }

    // --- AC-5 ------------------------------------------------------------------------------------------------

    @Test
    void acceptedChoicesApplyAtOnceForThatSalonOnlyAndOneDayChangesOnItsOwnAfterwards() throws Exception {
        CLOCK.set(Instant.parse("2082-03-10T06:00:00Z"));
        String admin = admin();
        addDay(admin, "2082-03-20", "Medin Full Moon Poya Day", "poya");
        addDay(admin, "2082-04-14", "Sinhala and Tamil New Year Day", "public");
        publish(admin, 2082);
        String alphas = manager(alpha);
        String betas = manager(beta);
        api.call(alpha, "PUT", HOURS_SETTINGS, save(0, week("09:00", "18:00")), alphas);

        Http.Exchange beforeAcceptance =
                api.call(alpha, "PUT", HOLIDAYS + "/2082/days/2082-04-14", Map.of("choice", "closed"), alphas);
        assertThat(beforeAcceptance.status()).isEqualTo(409);
        assertThat(api.body(beforeAcceptance)).containsEntry("code", "invalid_state");
        Http.Exchange missing = api.call(
                alpha,
                "POST",
                HOLIDAYS + "/2082/acceptance",
                accept(0, List.of(choice("2082-03-20", "closed", null, null))),
                alphas);
        assertThat(missing.status()).isEqualTo(400);
        assertThat(missing.body()).contains("\"field\":\"choices\"", "2082-04-14");
        Http.Exchange badTimes = api.call(
                alpha,
                "POST",
                HOLIDAYS + "/2082/acceptance",
                accept(
                        0,
                        List.of(
                                choice("2082-03-20", "closed", "09:00", null),
                                choice("2082-04-14", "special", "14:00", "10:00"))),
                alphas);
        assertThat(badTimes.status()).isEqualTo(400);
        assertThat(badTimes.body()).contains("\"field\":\"choices[0].opens\"", "\"field\":\"choices[1].closes\"");

        Http.Exchange accepted = api.call(
                alpha,
                "POST",
                HOLIDAYS + "/2082/acceptance",
                accept(
                        0,
                        List.of(
                                choice("2082-03-20", "closed", null, null),
                                choice("2082-04-14", "special", "10:00", "14:00"))),
                alphas);

        assertThat(accepted.status()).withFailMessage("%s", accepted.body()).isEqualTo(200);
        assertThat(api.body(accepted)).containsEntry("reviewed", true).containsEntry("version", 1);
        assertThat(exceptions(alpha))
                .extracting(
                        e -> e.get("date"),
                        e -> e.get("kind"),
                        e -> e.get("opens"),
                        e -> e.get("closes"),
                        e -> e.get("holidayName"))
                .containsExactly(
                        tuple("2082-03-20", "closed", null, null, "Medin Full Moon Poya Day"),
                        tuple("2082-04-14", "special", "10:00", "14:00", "Sinhala and Tamil New Year Day"));
        assertThat(openingOn(alpha, LocalDate.of(2082, 4, 14)).reason()).isEqualTo(OpeningReason.HOLIDAY);
        assertThat(holidayYear(beta, betas, 2082)).containsEntry("reviewed", false);
        assertThat(exceptions(beta)).isEmpty();
        Http.Exchange stale = api.call(alpha, "POST", HOLIDAYS + "/2082/acceptance", accept(0, List.of()), alphas);
        assertThat(stale.status()).isEqualTo(409);
        assertThat(api.body(stale)).containsEntry("code", "conflict");

        Http.Exchange changed =
                api.call(alpha, "PUT", HOLIDAYS + "/2082/days/2082-04-14", Map.of("choice", "open"), alphas);

        assertThat(changed.status()).withFailMessage("%s", changed.body()).isEqualTo(200);
        assertThat(api.body(changed)).containsEntry("version", 2);
        assertThat(exceptions(alpha)).extracting(e -> e.get("date")).containsExactly("2082-03-20");
        assertThat(openingOn(alpha, LocalDate.of(2082, 4, 14)).reason()).isEqualTo(OpeningReason.WEEKLY_HOURS);
        assertThat(api.call(alpha, "PUT", HOLIDAYS + "/2082/days/2082-05-01", Map.of("choice", "open"), alphas)
                        .status())
                .isEqualTo(404);
        assertThat(owner.queryForList(
                        "SELECT action || ' ' || after_values::text FROM audit_entry WHERE tenant_id = ?"
                                + " AND action LIKE 'staff.holiday%' ORDER BY created_at, action",
                        String.class, alpha.id()))
                .containsExactlyInAnyOrder(
                        "staff.holidays_accepted {\"year\": 2082, \"dates\": [\"2082-03-20\", \"2082-04-14\"],"
                                + " \"version\": 1}",
                        "staff.holiday_choice_changed {\"date\": \"2082-04-14\", \"year\": 2082, \"version\": 2}");
    }

    // --- AC-6 ------------------------------------------------------------------------------------------------

    @Test
    void aRevisionPutsBackToReviewOnlyTheDaysItAddsOrChangesAndAWithdrawnDayStopsApplying() throws Exception {
        CLOCK.set(Instant.parse("2083-01-05T06:00:00Z"));
        String admin = admin();
        addDay(admin, "2083-02-04", "Independence Day", "public");
        addDay(admin, "2083-02-10", "Navam Poya", "poya");
        addDay(admin, "2083-03-11", "Medin Poya", "poya");
        publish(admin, 2083);
        String token = manager(alpha);
        api.call(alpha, "PUT", HOURS_SETTINGS, save(0, week("09:00", "18:00")), token);
        api.call(
                alpha,
                "POST",
                HOLIDAYS + "/2083/acceptance",
                accept(
                        0,
                        List.of(
                                choice("2083-02-04", "closed", null, null),
                                choice("2083-02-10", "closed", null, null),
                                choice("2083-03-11", "closed", null, null))),
                token);

        console.call(
                "PUT",
                "/holiday-calendars/2083/days/2083-02-10",
                Map.of("name", "Navam Full Moon Poya Day", "kind", "poya"),
                admin);
        console.call("DELETE", "/holiday-calendars/2083/days/2083-03-11", null, admin);
        addDay(admin, "2083-04-14", "Sinhala and Tamil New Year Day", "public");
        Map<String, Object> pending = console.body(console.call("GET", "/holiday-calendars/2083", null, admin));
        assertThat(pending.get("changes")).isEqualTo(Map.of("added", 1, "changed", 1, "withdrawn", 1));
        publish(admin, 2083);

        Map<String, Object> year = holidayYear(alpha, token, 2083);

        assertThat(year).containsEntry("reviewed", false).containsEntry("revision", 2);
        assertThat(days(year))
                .extracting(d -> d.get("date"), d -> d.get("name"), d -> d.get("choice"), d -> d.get("toReview"))
                .containsExactly(
                        tuple("2083-02-04", "Independence Day", "closed", false),
                        tuple("2083-02-10", "Navam Full Moon Poya Day", "closed", true),
                        tuple("2083-04-14", "Sinhala and Tamil New Year Day", null, true));
        assertThat(exceptions(alpha)).extracting(e -> e.get("date")).containsExactly("2083-02-04");
        assertThat(openingOn(alpha, LocalDate.of(2083, 3, 11)))
                .isEqualTo(new OpeningDay(
                        LocalDate.of(2083, 3, 11),
                        true,
                        LocalTime.of(9, 0),
                        LocalTime.of(18, 0),
                        OpeningReason.WEEKLY_HOURS,
                        null));
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM staff_holiday_choice WHERE tenant_id = ? AND holiday_date = '2083-03-11'",
                        Long.class,
                        alpha.id()))
                .as("the withdrawn day's choice is kept")
                .isEqualTo(1L);

        Http.Exchange reviewed = api.call(
                alpha,
                "POST",
                HOLIDAYS + "/2083/acceptance",
                accept(
                        version(year),
                        List.of(choice("2083-02-10", "open", null, null), choice("2083-04-14", "closed", null, null))),
                token);
        assertThat(api.body(reviewed)).containsEntry("reviewed", true);
    }

    // --- AC-8 ------------------------------------------------------------------------------------------------

    @Test
    void theScheduleTakesAClosureOverAnAcceptedChoiceOverTheWeeklyHours() throws Exception {
        CLOCK.set(Instant.parse("2084-01-02T06:00:00Z"));
        String admin = admin();
        addDay(admin, "2084-01-17", "Duruthu Poya", "poya");
        addDay(admin, "2084-02-04", "Independence Day", "public");
        addDay(admin, "2084-02-15", "Navam Poya", "poya");
        publish(admin, 2084);
        String token = manager(alpha);
        api.call(alpha, "PUT", HOURS_SETTINGS, save(0, week("09:00", "18:00", "sunday")), token);
        api.call(
                alpha,
                "POST",
                HOLIDAYS + "/2084/acceptance",
                accept(
                        0,
                        List.of(
                                choice("2084-01-17", "closed", null, null),
                                choice("2084-02-04", "special", "10:00", "13:30"),
                                choice("2084-02-15", "special", "10:00", "13:30"))),
                token);
        addDay(admin, "2084-03-14", "Medin Poya", "poya");
        publish(admin, 2084);
        api.call(alpha, "POST", CLOSURES, closure("2084-02-15", "Stocktake"), token);
        LocalDate weekday = LocalDate.of(2084, 1, 10);
        LocalDate sunday = LocalDate.of(2084, 1, 9);
        assertThat(sunday.getDayOfWeek()).isEqualTo(java.time.DayOfWeek.SUNDAY);
        UUID branch = salons.defaultBranch(alpha);

        List<OpeningDay> days = TenantContext.callAs(
                salons.asCurrent(alpha),
                () -> List.of(
                        schedule.on(branch, weekday),
                        schedule.on(branch, sunday),
                        schedule.on(branch, LocalDate.of(2084, 1, 17)),
                        schedule.on(branch, LocalDate.of(2084, 2, 4)),
                        schedule.on(branch, LocalDate.of(2084, 3, 14)),
                        schedule.on(branch, LocalDate.of(2084, 2, 15))));

        assertThat(days)
                .extracting(OpeningDay::open, OpeningDay::opens, OpeningDay::closes, OpeningDay::reason)
                .containsExactly(
                        tuple(true, LocalTime.of(9, 0), LocalTime.of(18, 0), OpeningReason.WEEKLY_HOURS),
                        tuple(false, null, null, OpeningReason.WEEKLY_HOURS),
                        tuple(false, null, null, OpeningReason.HOLIDAY),
                        tuple(true, LocalTime.of(10, 0), LocalTime.of(13, 30), OpeningReason.HOLIDAY),
                        tuple(true, LocalTime.of(9, 0), LocalTime.of(18, 0), OpeningReason.WEEKLY_HOURS),
                        tuple(false, null, null, OpeningReason.CLOSURE));
        assertThat(TenantContext.callAs(
                        salons.asCurrent(alpha),
                        () -> schedule.between(branch, LocalDate.of(2084, 1, 1), LocalDate.of(2084, 1, 31))))
                .hasSize(31);
        assertThat(openingOn(beta, weekday).reason()).isEqualTo(OpeningReason.NO_WEEKLY_HOURS);
        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> TenantContext.callAs(salons.asCurrent(beta), () -> schedule.on(branch, weekday)));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> TenantContext.callAs(
                        salons.asCurrent(alpha),
                        () -> schedule.between(branch, LocalDate.of(2084, 1, 1), LocalDate.of(2085, 3, 1))));
    }

    @Test
    void theBookingsAChangeAffectsAreListedAndEveryOneIsKept() {
        CLOCK.set(Instant.parse("2086-05-04T06:00:00Z"));
        UUID branch = salons.defaultBranch(alpha);
        LocalDate day = LocalDate.of(2086, 5, 20);
        List<AffectedBooking> onTheDay = List.of(
                new AffectedBooking(day, LocalTime.of(10, 30), "Nimali Perera", "Haircut"),
                new AffectedBooking(day, LocalTime.of(15, 0), "Kasun Silva", "Beard trim"));
        List<AffectedBooking> outside = List.of(
                new AffectedBooking(LocalDate.of(2086, 5, 9), LocalTime.of(18, 30), "Ishara Fernando", "Colour"));
        BOOKINGS.onDate.put(branch, Map.of(day, onTheDay));
        BOOKINGS.outside.put(branch, outside);
        String token = manager(alpha);

        Http.Exchange impact = api.call(alpha, "GET", CLOSURES + "/impact?date=" + day, null, token);
        assertThat(api.body(impact).get("bookings"))
                .isEqualTo(List.of(
                        Map.of("time", "10:30", "customerName", "Nimali Perera", "serviceName", "Haircut"),
                        Map.of("time", "15:00", "customerName", "Kasun Silva", "serviceName", "Beard trim")));
        Http.Exchange closed = api.call(alpha, "POST", CLOSURES, closure(day.toString(), null), token);
        assertThat(api.body(closed)).containsEntry("affectedBookings", 2);
        assertThat((List<?>) json.readValue(
                        api.call(alpha, "GET", CLOSURES, null, token).body(), List.class))
                .singleElement()
                .satisfies(item ->
                        assertThat(((Map<?, ?>) item).get("affectedBookings")).isEqualTo(2));
        Http.Exchange saved = api.call(alpha, "PUT", HOURS_SETTINGS, save(0, week("09:00", "18:00")), token);
        assertThat(api.body(saved).get("outsideHours"))
                .isEqualTo(
                        List.of(Map.of("time", "18:30", "customerName", "Ishara Fernando", "serviceName", "Colour")));
        assertThat(api.body(api.call(alpha, "GET", HOURS_SETTINGS, null, token)).get("outsideHours"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .hasSize(1);

        assertThat(BOOKINGS.on(branch, day)).as("every booking is kept").isEqualTo(onTheDay);
        assertThat(BOOKINGS.outsideHours(branch, List.of(), day)).isEqualTo(outside);
    }

    // --- AC-9 ------------------------------------------------------------------------------------------------

    @Test
    void todayAndPastFollowTheSalonsTimeZone() {
        // 18:00 UTC is 23:30 in Asia/Colombo: still the 14th there.
        CLOCK.set(Instant.parse("2085-06-14T18:00:00Z"));
        String admin = admin();
        addDay(admin, "2085-06-14", "Poson Full Moon Poya Day", "poya");
        addDay(admin, "2085-07-13", "Esala Full Moon Poya Day", "poya");
        publish(admin, 2085);
        String token = manager(alpha);

        Http.Exchange yesterday = api.call(alpha, "POST", CLOSURES, closure("2085-06-13", null), token);
        assertThat(yesterday.status()).isEqualTo(400);
        assertThat(yesterday.body()).contains("\"field\":\"date\"", "today or later");
        Http.Exchange today = api.call(alpha, "POST", CLOSURES, closure("2085-06-14", null), token);
        assertThat(today.status()).withFailMessage("%s", today.body()).isEqualTo(201);
        assertThat(days(holidayYear(alpha, token, 2085)))
                .extracting(d -> d.get("date"), d -> d.get("past"))
                .containsExactly(tuple("2085-06-14", false), tuple("2085-07-13", false));

        // 18:31 UTC is 00:01 on the 15th in Colombo: the 14th has ended there.
        CLOCK.set(Instant.parse("2085-06-14T18:31:00Z"));
        String later = manager(alpha);
        Map<String, Object> year = holidayYear(alpha, later, 2085);
        assertThat(days(year))
                .extracting(d -> d.get("date"), d -> d.get("past"))
                .containsExactly(tuple("2085-06-14", true), tuple("2085-07-13", false));
        Http.Exchange passedChoice = api.call(
                alpha,
                "POST",
                HOLIDAYS + "/2085/acceptance",
                accept(
                        0,
                        List.of(
                                choice("2085-06-14", "closed", null, null),
                                choice("2085-07-13", "closed", null, null))),
                later);
        assertThat(passedChoice.status()).isEqualTo(400);
        assertThat(passedChoice.body()).contains("\"field\":\"choices[0].date\"", "has passed");
        Http.Exchange accepted = api.call(
                alpha,
                "POST",
                HOLIDAYS + "/2085/acceptance",
                accept(0, List.of(choice("2085-07-13", "closed", null, null))),
                later);
        assertThat(api.body(accepted)).containsEntry("reviewed", true);
        Http.Exchange reopenPassed =
                api.call(alpha, "DELETE", CLOSURES + "/" + api.body(today).get("id"), null, later);
        assertThat(reopenPassed.status()).isEqualTo(409);
        assertThat(api.body(reopenPassed)).containsEntry("code", "invalid_state");
        assertThat(exceptions(alpha)).extracting(e -> e.get("date")).containsExactly("2085-07-13");
        List<?> upcoming = (List<?>)
                json.readValue(api.call(alpha, "GET", CLOSURES, null, later).body(), List.class);
        assertThat(upcoming).isEmpty();
    }
}
