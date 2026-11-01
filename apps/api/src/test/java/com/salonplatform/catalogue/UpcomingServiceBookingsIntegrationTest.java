package com.salonplatform.catalogue;

import static com.salonplatform.catalogue.CatalogueFixture.SERVICES;
import static com.salonplatform.catalogue.CatalogueFixture.save;
import static com.salonplatform.catalogue.CatalogueFixture.service;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * The delete guard (F05-S01 AC-9, decision 093): when {@link UpcomingServiceBookings} reports an upcoming booking
 * for a service, its delete answers 409 {@code in_use} and nothing changes. Here an implementation that reports a
 * booking for the services this test names stands in for {@code booking}'s (F07).
 */
class UpcomingServiceBookingsIntegrationTest extends IntegrationTest {

    /** The services with an upcoming booking, set per test. */
    static final Set<UUID> BOOKED = ConcurrentHashMap.newKeySet();

    @TestConfiguration
    static class Bookings {
        @Bean
        @Primary
        UpcomingServiceBookings reportedBookings() {
            return BOOKED::contains;
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
    Clock clock;

    @PersistenceContext
    EntityManager entities;

    private final JdbcTemplate owner = TestDatabase.of(TestPostgres.shared()).asOwner();
    private TestSalons salons;
    private SettingsTestSupport settings;
    private CatalogueFixture api;
    private TestSalon alpha;
    private TestSalon beta;
    private String manager;
    private UUID colour;

    @BeforeEach
    void twoSalonsAndAlphasManager() {
        salons = new TestSalons(owner);
        OperatorTestSupport console =
                new OperatorTestSupport(http(), port(), operators, operatorCrypto, json, environment, clock);
        settings = new SettingsTestSupport(
                http(), port(), salons, identityCrypto, json, transactions, entities, clock, console);
        api = new CatalogueFixture(settings, http(), port());
        alpha = salons.create("Catalogue Alpha", "#D6086A");
        beta = salons.create("Catalogue Beta", "#1F2937");
        manager = settings.signedIn(alpha, StaffRole.MANAGER).token();
        colour = api.category(alpha, manager, "Colour");
    }

    private void refused(Http.Exchange exchange, int status, String code) {
        assertThat(exchange.status()).withFailMessage("%s", exchange.body()).isEqualTo(status);
        assertThat(api.body(exchange).get("code")).isEqualTo(code);
    }

    @SuppressWarnings("unchecked") // JSON read as an untyped map.
    private static Map<String, Object> terms(Map<String, Object> service) {
        return (Map<String, Object>) service.get("terms");
    }

    private Http.Exchange saved(Map<String, Object> service, String field, Object value) {
        return api.call(alpha, "PUT", SERVICES + "/" + service.get("id"), save(service, field, value), manager);
    }

    @AfterEach
    void noBookings() {
        BOOKED.clear();
    }

    @Test
    void aServiceThatUpcomingBookingsUseIsInUseAndNothingChanges() {
        Map<String, Object> service = api.created(alpha, manager, service(colour, "Balayage", 12000, 180, 0));
        Map<String, Object> other = api.created(alpha, manager, service(colour, "Root touch-up", 4500, 60, 0));
        BOOKED.add(UUID.fromString((String) service.get("id")));
        List<Map<String, Object>> before = owner.queryForList(
                "SELECT id, deleted_at, version FROM catalogue_service WHERE tenant_id = ? ORDER BY id", alpha.id());

        refused(
                api.call(alpha, "DELETE", SERVICES + "/" + service.get("id") + "?version=0", null, manager),
                409,
                "in_use");

        assertThat(owner.queryForList(
                        "SELECT id, deleted_at, version FROM catalogue_service WHERE tenant_id = ? ORDER BY id",
                        alpha.id()))
                .isEqualTo(before);
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM audit_entry WHERE tenant_id = ? AND action = 'catalogue.service_deleted'",
                        Long.class,
                        alpha.id()))
                .isZero();
        assertThat(api.call(alpha, "DELETE", SERVICES + "/" + other.get("id") + "?version=0", null, manager)
                        .status())
                .isEqualTo(204);
    }
}
