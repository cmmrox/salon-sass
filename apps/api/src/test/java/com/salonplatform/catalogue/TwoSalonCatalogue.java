package com.salonplatform.catalogue;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.branding.SettingsTestSupport;
import com.salonplatform.identity.adapter.IdentityCrypto;
import com.salonplatform.identity.domain.StaffRole;
import com.salonplatform.platform.OperatorTestSupport;
import com.salonplatform.platform.adapter.OperatorCrypto;
import com.salonplatform.platform.service.OperatorAccountService;
import com.salonplatform.shared.tenancy.TenantContext;
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
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * What F05-S02's integration tests share: two salons, alpha's Manager and its Colour and Nails categories, calls held
 * to the committed contract, and the database read as its owner.
 */
abstract class TwoSalonCatalogue extends IntegrationTest {

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

    final JdbcTemplate owner = TestDatabase.of(TestPostgres.shared()).asOwner();
    TestSalons salons;
    SettingsTestSupport settings;
    CatalogueFixture api;
    TestSalon alpha;
    TestSalon beta;
    String manager;
    UUID colour;
    UUID nails;

    @BeforeEach
    void twoSalonsAndAlphasManager() {
        salons = new TestSalons(owner);
        OperatorTestSupport console =
                new OperatorTestSupport(http(), port(), operators, operatorCrypto, json, environment, clock);
        settings = new SettingsTestSupport(
                http(), port(), salons, identityCrypto, json, transactions, entities, clock, console);
        api = new CatalogueFixture(settings, http(), port());
        alpha = salons.create("Variants Alpha", "#D6086A");
        beta = salons.create("Variants Beta", "#1F2937");
        manager = settings.signedIn(alpha, StaffRole.MANAGER).token();
        colour = api.category(alpha, manager, "Colour");
        nails = api.category(alpha, manager, "Nails");
    }

    void refused(Http.Exchange exchange, int status, String code) {
        assertThat(exchange.status()).withFailMessage("%s", exchange.body()).isEqualTo(status);
        assertThat(api.body(exchange).get("code")).isEqualTo(code);
    }

    /** A 400 naming exactly these fields. */
    void fieldErrors(Http.Exchange exchange, String... fields) {
        refused(exchange, 400, "bad_request");
        assertThat(CatalogueFixture.maps(api.body(exchange).get("errors")).stream()
                        .map(error -> error.get("field")))
                .containsExactlyInAnyOrder((Object[]) fields);
    }

    long count(String sql) {
        return owner.queryForObject(sql, Long.class, alpha.id());
    }

    /** Everything of alpha's catalogue rows that a refusal must leave as it was. */
    List<Map<String, Object>> snapshot() {
        return owner.queryForList(
                "SELECT s.id, s.version, s.popular, s.current_terms_id, (SELECT count(*) FROM catalogue_terms_version v"
                        + " WHERE v.tenant_id = s.tenant_id) AS versions, (SELECT count(*) FROM catalogue_terms_variant"
                        + " r WHERE r.tenant_id = s.tenant_id) AS variants FROM catalogue_service s"
                        + " WHERE s.tenant_id = ? ORDER BY s.id",
                alpha.id());
    }

    static Map<String, Object> terms(Map<String, Object> service) {
        return CatalogueFixture.map(service.get("terms"));
    }

    static List<Map<String, Object>> variants(Map<String, Object> service) {
        return CatalogueFixture.maps(terms(service).get("variants"));
    }

    Http.Exchange put(Map<String, Object> service, Map<String, Object> body) {
        return api.call(alpha, "PUT", CatalogueFixture.SERVICES + "/" + service.get("id"), body, manager);
    }

    <T> T as(TestSalon salon, Callable<T> work) throws Exception {
        return TenantContext.callAs(salons.asCurrent(salon), work);
    }
}
