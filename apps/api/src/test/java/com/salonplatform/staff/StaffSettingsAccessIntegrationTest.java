package com.salonplatform.staff;

import static com.salonplatform.staff.StaffSettingsFixture.CLOSURES;
import static com.salonplatform.staff.StaffSettingsFixture.HOLIDAYS;
import static com.salonplatform.staff.StaffSettingsFixture.HOURS;
import static com.salonplatform.staff.StaffSettingsFixture.HOURS_SETTINGS;
import static com.salonplatform.staff.StaffSettingsFixture.accept;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Who reaches the hours, holidays and closures (F04-S03 AC-10): roles without settings are refused every
 * endpoint but the public one; one salon's settings user acts only on the hostname's salon, and another salon's
 * closure id is not found exactly as an id that names none; a support session reads and is refused every change;
 * a suspended or read-only salon answers as its other settings endpoints do; and the operator's calendar routes
 * are not found on a salon's hostname. Held to the committed contract.
 */
class StaffSettingsAccessIntegrationTest extends IntegrationTest {

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
    private TestSalons salons;
    private SettingsTestSupport settings;
    private StaffSettingsFixture api;
    private TestSalon alpha;
    private TestSalon beta;
    private LocalDate today;

    @BeforeEach
    void twoSalons() {
        salons = new TestSalons(owner);
        OperatorTestSupport console =
                new OperatorTestSupport(http(), port(), operators, operatorCrypto, json, environment, clock);
        settings = new SettingsTestSupport(
                http(), port(), salons, identityCrypto, json, transactions, entities, clock, console);
        api = new StaffSettingsFixture(settings);
        alpha = salons.create("Access Alpha", "#D6086A");
        beta = salons.create("Access Beta", "#1F2937");
        today = LocalDate.ofInstant(clock.instant(), TestSalons.DEFAULT_TIME_ZONE);
    }

    private void refused(Http.Exchange exchange, int status, String code) {
        assertThat(exchange.status()).withFailMessage("%s", exchange.body()).isEqualTo(status);
        assertThat(api.body(exchange).get("code")).isEqualTo(code);
    }

    /** Every row the salon has in staff's tables, as the owner sees them. */
    private List<Map<String, Object>> staffRows(TestSalon salon) {
        return owner.queryForList("""
                SELECT 'hours' AS kind, version::text AS a FROM staff_opening_hours WHERE tenant_id = ?
                UNION ALL SELECT 'review', version::text FROM staff_holiday_review WHERE tenant_id = ?
                UNION ALL SELECT 'choice', holiday_date::text FROM staff_holiday_choice WHERE tenant_id = ?
                UNION ALL SELECT 'closure', closure_date::text FROM staff_closure WHERE tenant_id = ?
                ORDER BY 1, 2
                """, salon.id(), salon.id(), salon.id(), salon.id());
    }

    private void everyReadRefused(TestSalon salon, String token, int status, String code) {
        refused(api.call(salon, "GET", HOURS_SETTINGS, null, token), status, code);
        refused(api.call(salon, "GET", HOLIDAYS, null, token), status, code);
        refused(api.call(salon, "GET", CLOSURES, null, token), status, code);
        refused(api.call(salon, "GET", CLOSURES + "/impact?date=" + today, null, token), status, code);
    }

    private void everyChangeRefused(TestSalon salon, String token, int status, String code, String closureId) {
        refused(api.call(salon, "PUT", HOURS_SETTINGS, save(0, week("09:00", "18:00")), token), status, code);
        refused(
                api.call(salon, "POST", HOLIDAYS + "/" + today.getYear() + "/acceptance", accept(0, List.of()), token),
                status,
                code);
        refused(
                api.call(
                        salon,
                        "PUT",
                        HOLIDAYS + "/" + today.getYear() + "/days/" + today,
                        Map.of("choice", "closed"),
                        token),
                status,
                code);
        refused(api.call(salon, "POST", CLOSURES, closure(today.plusDays(2).toString(), null), token), status, code);
        refused(api.call(salon, "DELETE", CLOSURES + "/" + closureId, null, token), status, code);
    }

    /** Alpha with saved hours and a closure, made by its Owner: the closure's id. */
    private String alphaWithHoursAndAClosure() {
        String ownersToken = settings.signedIn(alpha, StaffRole.OWNER).token();
        assertThat(api.call(alpha, "PUT", HOURS_SETTINGS, save(0, week("09:00", "18:00")), ownersToken)
                        .status())
                .isEqualTo(200);
        Http.Exchange closed =
                api.call(alpha, "POST", CLOSURES, closure(today.plusDays(5).toString(), "Repairs"), ownersToken);
        assertThat(closed.status()).isEqualTo(201);
        return (String) api.body(closed).get("id");
    }

    @ParameterizedTest
    @EnumSource(
            value = StaffRole.class,
            names = {"RECEPTIONIST", "PROFESSIONAL", "ACCOUNTANT"})
    void rolesWithoutSettingsAreRefusedEveryEndpointButThePublicOneAndChangeNothing(StaffRole role) {
        String closure = alphaWithHoursAndAClosure();
        String token = settings.signedIn(alpha, role).token();
        List<Map<String, Object>> before = staffRows(alpha);

        everyReadRefused(alpha, token, 403, "permission_denied");
        everyChangeRefused(alpha, token, 403, "permission_denied", closure);
        assertThat(api.call(alpha, "GET", HOURS, null, token).status()).isEqualTo(200);

        assertThat(staffRows(alpha)).isEqualTo(before);
    }

    @Test
    void aSettingsUserActsOnlyOnTheHostnamesSalonAndAnotherSalonsClosureIsNotFound() {
        String closure = alphaWithHoursAndAClosure();
        String betasManager = settings.signedIn(beta, StaffRole.MANAGER).token();
        List<Map<String, Object>> before = staffRows(alpha);

        // Beta's session on alpha's hostname is a session of another salon.
        everyReadRefused(alpha, betasManager, 404, "not_found");
        everyChangeRefused(alpha, betasManager, 404, "not_found", closure);
        // On beta's own hostname, alpha's closure is the same answer as an id that names nothing.
        Http.Exchange foreign = api.call(beta, "DELETE", CLOSURES + "/" + closure, null, betasManager);
        Http.Exchange unknown = api.call(beta, "DELETE", CLOSURES + "/" + UUID.randomUUID(), null, betasManager);
        refused(foreign, 404, "not_found");
        assertThat(foreign.body().replaceAll("\"traceId\":\"[0-9a-f]+\"", "").replaceAll("/closures/[^\"]+", ""))
                .isEqualTo(unknown.body()
                        .replaceAll("\"traceId\":\"[0-9a-f]+\"", "")
                        .replaceAll("/closures/[^\"]+", ""));
        assertThat(api.body(api.call(beta, "GET", HOURS, null, null))).containsEntry("weekly", null);
        assertThat((List<?>) json.readValue(
                        api.call(beta, "GET", CLOSURES, null, betasManager).body(), List.class))
                .isEmpty();

        assertThat(staffRows(alpha)).isEqualTo(before);
    }

    @Test
    void aSupportSessionReadsAndIsRefusedEveryChange() {
        String closure = alphaWithHoursAndAClosure();
        String support = settings.supportSession(alpha);
        List<Map<String, Object>> before = staffRows(alpha);

        assertThat(api.call(alpha, "GET", HOURS_SETTINGS, null, support).status())
                .isEqualTo(200);
        assertThat(api.call(alpha, "GET", HOLIDAYS, null, support).status()).isEqualTo(200);
        assertThat(api.call(alpha, "GET", CLOSURES, null, support).status()).isEqualTo(200);
        everyChangeRefused(alpha, support, 403, "support_read_only", closure);

        assertThat(staffRows(alpha)).isEqualTo(before);
    }

    @Test
    void aSuspendedSalonAnswersSalonSuspendedOnEveryEndpoint() {
        String closure = alphaWithHoursAndAClosure();
        String token = settings.signedIn(alpha, StaffRole.MANAGER).token();
        salons.suspend(alpha);
        try {
            refused(api.call(alpha, "GET", HOURS, null, null), 403, "salon_suspended");
            everyReadRefused(alpha, token, 403, "salon_suspended");
            everyChangeRefused(alpha, token, 403, "salon_suspended", closure);
        } finally {
            salons.activate(alpha);
        }
    }

    @Test
    void aReadOnlySalonReadsAndRefusesEveryChangeWithSalonReadOnly() {
        String closure = alphaWithHoursAndAClosure();
        String token = settings.signedIn(alpha, StaffRole.MANAGER).token();
        owner.update("UPDATE tenant SET access_state = 'read_only' WHERE id = ?", alpha.id());
        List<Map<String, Object>> before = staffRows(alpha);

        assertThat(api.call(alpha, "GET", HOURS_SETTINGS, null, token).status()).isEqualTo(200);
        assertThat(api.call(alpha, "GET", HOURS, null, null).status()).isEqualTo(200);
        everyChangeRefused(alpha, token, 403, "salon_read_only", closure);

        assertThat(staffRows(alpha)).isEqualTo(before);
    }

    @Test
    void theOperatorsCalendarRoutesAreNotFoundOnASalonsHostname() {
        String token = settings.signedIn(alpha, StaffRole.OWNER).token();
        for (String path : List.of(
                "/api/v1/operator/holiday-calendars", "/api/v1/operator/holiday-calendars/" + today.getYear())) {
            Http.Exchange answer = settings.call(alpha, "GET", path, null, token);
            assertThat(answer.status()).as(path).isEqualTo(404);
            assertThat(settings.body(answer)).containsEntry("code", "not_found");
        }
    }
}
