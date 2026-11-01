package com.salonplatform.booking;

import static com.salonplatform.booking.BookingRulesFixture.HISTORY;
import static com.salonplatform.booking.BookingRulesFixture.RULES;
import static com.salonplatform.booking.BookingRulesFixture.SETTINGS;
import static com.salonplatform.booking.BookingRulesFixture.save;
import static com.salonplatform.booking.BookingRulesFixture.suggested;
import static com.salonplatform.booking.BookingRulesFixture.with;
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
import java.util.List;
import java.util.Map;
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
 * Who reaches the booking rules (F04-S04 AC-7, AC-8): roles without settings are refused the settings endpoints
 * and still read the public rules; one salon's settings user acts only on the hostname's salon and never reads or
 * changes another's; a support session reads the settings and the history, with the author named as the Owner
 * and Manager see it, and is refused saving; a suspended or read-only salon answers as its other settings
 * endpoints do. Held to the committed contract.
 */
class BookingRulesAccessIntegrationTest extends IntegrationTest {

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
    private BookingRulesFixture api;
    private TestSalon alpha;
    private TestSalon beta;

    @BeforeEach
    void twoSalons() {
        salons = new TestSalons(owner);
        OperatorTestSupport console =
                new OperatorTestSupport(http(), port(), operators, operatorCrypto, json, environment, clock);
        settings = new SettingsTestSupport(
                http(), port(), salons, identityCrypto, json, transactions, entities, clock, console);
        api = new BookingRulesFixture(settings);
        alpha = salons.create("Rules Access Alpha", "#D6086A");
        beta = salons.create("Rules Access Beta", "#1F2937");
    }

    private void refused(Http.Exchange exchange, int status, String code) {
        assertThat(exchange.status()).withFailMessage("%s", exchange.body()).isEqualTo(status);
        assertThat(api.body(exchange).get("code")).isEqualTo(code);
    }

    private List<Map<String, Object>> rows(TestSalon salon) {
        return owner.queryForList(
                "SELECT id, version, slot_interval_minutes FROM booking_rules_version WHERE tenant_id = ?"
                        + " ORDER BY version",
                salon.id());
    }

    /** Alpha with its rules saved by its Owner: the Owner. */
    private SettingsTestSupport.Staff alphaWithRules() {
        SettingsTestSupport.Staff alphasOwner = settings.signedIn(alpha, StaffRole.OWNER);
        assertThat(api.call(alpha, "PUT", SETTINGS, save(0, suggested()), alphasOwner.token())
                        .status())
                .isEqualTo(200);
        return alphasOwner;
    }

    private void settingsRefused(TestSalon salon, String token, int status, String code) {
        refused(api.call(salon, "GET", SETTINGS, null, token), status, code);
        refused(api.call(salon, "GET", HISTORY, null, token), status, code);
        refused(api.call(salon, "PUT", SETTINGS, save(1, with("slotIntervalMinutes", 30)), token), status, code);
    }

    @ParameterizedTest
    @EnumSource(
            value = StaffRole.class,
            names = {"RECEPTIONIST", "PROFESSIONAL", "ACCOUNTANT"})
    void rolesWithoutSettingsAreRefusedTheSettingsEndpointsAndStillReadThePublicRules(StaffRole role) {
        alphaWithRules();
        String token = settings.signedIn(alpha, role).token();
        List<Map<String, Object>> before = rows(alpha);

        settingsRefused(alpha, token, 403, "permission_denied");
        Http.Exchange published = api.call(alpha, "GET", RULES, null, token);
        assertThat(published.status()).isEqualTo(200);
        assertThat(api.body(published)).containsKey("versionId");

        assertThat(rows(alpha)).isEqualTo(before);
    }

    @Test
    void ownerAndManagerBothKeepTheRules() {
        alphaWithRules();
        String managers = settings.signedIn(alpha, StaffRole.MANAGER).token();
        assertThat(api.call(alpha, "PUT", SETTINGS, save(1, with("slotIntervalMinutes", 30)), managers)
                        .status())
                .isEqualTo(200);
    }

    @Test
    void anotherSalonsRulesAreNeverReadableOrChangeable() {
        alphaWithRules();
        String betasManager = settings.signedIn(beta, StaffRole.MANAGER).token();
        List<Map<String, Object>> before = rows(alpha);

        // Beta's session on alpha's hostname is a session of another salon.
        settingsRefused(alpha, betasManager, 404, "not_found");
        // On beta's own hostname, beta sees only its own (none) and saves only its own.
        assertThat(api.call(beta, "GET", RULES, null, null).body()).isEqualTo("null");
        assertThat(api.body(api.call(beta, "GET", SETTINGS, null, betasManager)))
                .containsEntry("version", 0)
                .containsEntry("rules", null);
        assertThat(api.body(api.call(beta, "GET", HISTORY, null, betasManager))).containsEntry("total", 0);
        assertThat(api.call(beta, "PUT", SETTINGS, save(0, with("slotIntervalMinutes", 10)), betasManager)
                        .status())
                .isEqualTo(200);

        assertThat(rows(alpha)).isEqualTo(before);
        assertThat(rows(beta)).hasSize(1);
        assertThat(rows(beta).getFirst()).containsEntry("slot_interval_minutes", 10);
    }

    @Test
    void aSupportSessionReadsTheSettingsAndHistoryAndIsRefusedSaving() {
        SettingsTestSupport.Staff alphasOwner = alphaWithRules();
        String support = settings.supportSession(alpha);
        List<Map<String, Object>> before = rows(alpha);

        assertThat(api.body(api.call(alpha, "GET", SETTINGS, null, support))).containsEntry("version", 1);
        Map<?, ?> entry = (Map<?, ?>) ((List<?>)
                        api.body(api.call(alpha, "GET", HISTORY, null, support)).get("items"))
                .getFirst();
        assertThat(entry.get("createdByLabel")).isEqualTo(alphasOwner.account().getEmail());
        refused(
                api.call(alpha, "PUT", SETTINGS, save(1, with("slotIntervalMinutes", 30)), support),
                403,
                "support_read_only");

        assertThat(rows(alpha)).isEqualTo(before);
    }

    @Test
    void aSuspendedSalonAnswersSalonSuspendedOnEveryEndpoint() {
        alphaWithRules();
        String token = settings.signedIn(alpha, StaffRole.MANAGER).token();
        salons.suspend(alpha);
        try {
            refused(api.call(alpha, "GET", RULES, null, null), 403, "salon_suspended");
            settingsRefused(alpha, token, 403, "salon_suspended");
        } finally {
            salons.activate(alpha);
        }
    }

    @Test
    void aReadOnlySalonReadsAndRefusesSavingWithSalonReadOnly() {
        alphaWithRules();
        String token = settings.signedIn(alpha, StaffRole.MANAGER).token();
        owner.update("UPDATE tenant SET access_state = 'read_only' WHERE id = ?", alpha.id());
        List<Map<String, Object>> before = rows(alpha);

        assertThat(api.call(alpha, "GET", SETTINGS, null, token).status()).isEqualTo(200);
        assertThat(api.call(alpha, "GET", HISTORY, null, token).status()).isEqualTo(200);
        assertThat(api.call(alpha, "GET", RULES, null, null).status()).isEqualTo(200);
        refused(
                api.call(alpha, "PUT", SETTINGS, save(1, with("slotIntervalMinutes", 30)), token),
                403,
                "salon_read_only");

        assertThat(rows(alpha)).isEqualTo(before);
    }
}
