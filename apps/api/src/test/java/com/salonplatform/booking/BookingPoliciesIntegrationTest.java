package com.salonplatform.booking;

import static com.salonplatform.booking.BookingRulesFixture.SETTINGS;
import static com.salonplatform.booking.BookingRulesFixture.map;
import static com.salonplatform.booking.BookingRulesFixture.save;
import static com.salonplatform.booking.BookingRulesFixture.suggested;
import static com.salonplatform.booking.BookingRulesFixture.with;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.salonplatform.branding.SettingsTestSupport;
import com.salonplatform.identity.StaffLabels;
import com.salonplatform.identity.adapter.IdentityCrypto;
import com.salonplatform.identity.domain.StaffRole;
import com.salonplatform.platform.OperatorTestSupport;
import com.salonplatform.platform.SalonCurrency;
import com.salonplatform.platform.adapter.OperatorCrypto;
import com.salonplatform.platform.service.OperatorAccountService;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.testing.IntegrationTest;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import com.salonplatform.testing.TestSalons;
import com.salonplatform.testing.TestSalons.TestSalon;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link BookingPolicies} as F07, F08 and F09 will call it (F04-S04 AC-6): the current version of the caller's
 * salon, a version by id for that salon only and unchanged after later saves, and the per-service deposit
 * resolution. Also the two contracts it is built on: {@link SalonCurrency} (LKR when the salon has none) and
 * {@link StaffLabels} (never another salon's account), and that no role can change a saved version.
 */
class BookingPoliciesIntegrationTest extends IntegrationTest {

    @Autowired
    BookingPolicies policies;

    @Autowired
    SalonCurrency currency;

    @Autowired
    StaffLabels labels;

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
    private SettingsTestSupport.Staff alphasManager;

    @BeforeEach
    void twoSalons() {
        salons = new TestSalons(owner);
        OperatorTestSupport console =
                new OperatorTestSupport(http(), port(), operators, operatorCrypto, json, environment, clock);
        settings = new SettingsTestSupport(
                http(), port(), salons, identityCrypto, json, transactions, entities, clock, console);
        api = new BookingRulesFixture(settings);
        alpha = salons.create("Policies Alpha", "#D6086A");
        beta = salons.create("Policies Beta", "#1F2937");
        alphasManager = settings.signedIn(alpha, StaffRole.MANAGER);
    }

    private <T> T as(TestSalon salon, Callable<T> work) throws Exception {
        return TenantContext.callAs(salons.asCurrent(salon), work);
    }

    private void saved(TestSalon salon, String token, long version, Map<String, Object> rules) {
        assertThat(api.call(salon, "PUT", SETTINGS, save(version, rules), token).status())
                .isEqualTo(200);
    }

    @Test
    void theCurrentRulesAreEmptyBeforeTheFirstSaveAndThenTheNewestVersion() throws Exception {
        assertThat(as(alpha, policies::current)).isEmpty();
        saved(alpha, alphasManager.token(), 0, suggested());
        saved(alpha, alphasManager.token(), 1, with("bookingHorizonDays", 180));

        BookingRules current = as(alpha, policies::current).orElseThrow();
        assertThat(current.version()).isEqualTo(2);
        assertThat(current.currency()).isEqualTo("LKR");
        assertThat(current.branchId()).isEqualTo(salons.defaultBranch(alpha));
        assertThat(current.terms().bookingHorizonDays()).isEqualTo(180);
        assertThat(as(beta, policies::current)).isEmpty();
    }

    @Test
    void aVersionReadByIdAfterThreeLaterSavesStillHasItsOriginalValues() throws Exception {
        saved(alpha, alphasManager.token(), 0, suggested());
        BookingRules first = as(alpha, policies::current).orElseThrow();
        assertThat(first.terms())
                .isEqualTo(new BookingTerms(
                        15,
                        60,
                        60,
                        new DepositRule.Percentage(20, 200_000),
                        24,
                        new Retention.Deposit(),
                        new Retention.Deposit(),
                        new Rescheduling.Limited(2, 12)));

        saved(alpha, alphasManager.token(), 1, with("deposit", map("kind", "amount", "amountMinor", 250_000L)));
        saved(alpha, alphasManager.token(), 2, with("reschedule", map("limit", 0, "untilHours", null)));
        Map<String, Object> third = with("lateCancellation", map("kind", "share", "percent", 40));
        third.put("noShow", map("kind", "share", "percent", 90));
        saved(alpha, alphasManager.token(), 3, third);

        assertThat(as(alpha, () -> policies.version(first.versionId()))).contains(first);
        assertThat(as(alpha, policies::current).orElseThrow().version()).isEqualTo(4);
    }

    @Test
    void anotherSalonsVersionIdIsNotFoundExactlyAsAnUnknownId() throws Exception {
        saved(alpha, alphasManager.token(), 0, suggested());
        UUID alphas = as(alpha, policies::current).orElseThrow().versionId();

        assertThat(as(beta, () -> policies.version(alphas))).isEmpty();
        assertThat(as(beta, () -> policies.version(UUID.randomUUID()))).isEmpty();
        assertThat(as(alpha, () -> policies.version(alphas))).isPresent();
    }

    @Test
    void aServicesOwnDepositRuleWinsAndOtherwiseTheSalonsApplies() throws Exception {
        saved(alpha, alphasManager.token(), 0, suggested());
        BookingRules rules = as(alpha, policies::current).orElseThrow();

        assertThat(policies.depositFor(rules, Optional.empty())).isEqualTo(new DepositRule.Percentage(20, 200_000));
        assertThat(policies.depositFor(rules, Optional.of(new DepositRule.Amount(500_000))))
                .isEqualTo(new DepositRule.Amount(500_000));
        assertThat(policies.depositFor(rules, Optional.of(new DepositRule.None())))
                .isEqualTo(new DepositRule.None());
    }

    @Test
    void theSalonsCurrencyIsLkrWhenUnsetAndItsOwnOtherwise() throws Exception {
        assertThat(as(alpha, currency::current)).isEqualTo("LKR");
        owner.update("UPDATE tenant SET currency = 'USD' WHERE id = ?", beta.id());
        assertThat(as(beta, currency::current)).isEqualTo("USD");
        assertThat(as(alpha, currency::current)).isEqualTo("LKR");
        try (Connection connection = TestDatabase.of(TestPostgres.shared()).connectAsApplication();
                Statement statement = connection.createStatement();
                ResultSet answer = statement.executeQuery("SELECT salon_currency()")) {
            assertThat(answer.next()).isTrue();
            assertThat(answer.getString(1)).as("with no salon set").isNull();
        }
    }

    @Test
    void anAuthorsLabelIsTheirEmailAndAnotherSalonsStaffIdNeverResolves() throws Exception {
        SettingsTestSupport.Staff betasOwner = settings.signedIn(beta, StaffRole.OWNER);
        UUID alphas = alphasManager.account().getId();
        UUID betas = betasOwner.account().getId();

        assertThat(as(alpha, () -> labels.labels(List.of(alphas, betas, UUID.randomUUID()))))
                .isEqualTo(Map.of(alphas, alphasManager.account().getEmail()));
        assertThat(as(beta, () -> labels.labels(List.of(alphas)))).isEmpty();
    }

    @Test
    void noRoleChangesASavedVersion() {
        saved(alpha, alphasManager.token(), 0, suggested());
        assertThatExceptionOfType(DataAccessException.class)
                .isThrownBy(() -> owner.update(
                        "UPDATE booking_rules_version SET slot_interval_minutes = 30 WHERE tenant_id = ?", alpha.id()))
                .havingRootCause()
                .withMessageContaining("a booking rules version never changes");
        assertThat(owner.queryForObject(
                        "SELECT slot_interval_minutes FROM booking_rules_version WHERE tenant_id = ?",
                        Integer.class,
                        alpha.id()))
                .isEqualTo(15);
    }
}
