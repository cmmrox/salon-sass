package com.salonplatform.catalogue;

import static com.salonplatform.catalogue.CatalogueFixture.SERVICES;
import static com.salonplatform.catalogue.CatalogueFixture.save;
import static com.salonplatform.catalogue.CatalogueFixture.service;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link ServiceTerms}, the contract F07 books against (F05-S01 AC-5, AC-6): a service's current terms or a version
 * by id, with its segments, for the caller's salon only. A version read by id after three later saves, and after
 * the service is deleted, still has its original values. Another salon's ids are "not found". No HTTP route reads a
 * version by id in this stage, so the contract is called as F07 will call it, with the salon of a request.
 */
class ServiceTermsIntegrationTest extends IntegrationTest {

    @Autowired
    ServiceTerms serviceTerms;

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

    private <T> T as(TestSalon salon, Callable<T> work) throws Exception {
        return TenantContext.callAs(salons.asCurrent(salon), work);
    }

    @Test
    void aVersionReadByIdKeepsItsOriginalValuesAfterThreeLaterSavesAndAfterTheServiceIsDeleted() throws Exception {
        Map<String, Object> first = api.created(alpha, manager, service(colour, "Root touch-up", 4500, 60, 15));
        UUID serviceId = UUID.fromString((String) first.get("id"));
        UUID firstVersion = UUID.fromString((String) terms(first).get("versionId"));
        Map<String, Object> second = api.body(saved(first, "priceMinor", 500_000));
        Map<String, Object> third = api.body(saved(second, "durationMinutes", 90));
        Map<String, Object> fourth = api.body(saved(third, "bufferMinutes", 0));
        assertThat(terms(fourth)).containsEntry("number", 4);

        ServiceTermsVersion original =
                as(alpha, () -> serviceTerms.version(firstVersion)).orElseThrow();
        assertThat(original)
                .isEqualTo(new ServiceTermsVersion(
                        serviceId,
                        firstVersion,
                        1,
                        "Root touch-up",
                        450_000,
                        "LKR",
                        List.of(new ServiceSegment(SegmentKind.ACTIVE, 60), new ServiceSegment(SegmentKind.BUFFER, 15)),
                        true));
        assertThat(as(alpha, () -> serviceTerms.current(serviceId)).orElseThrow())
                .satisfies(current -> {
                    assertThat(current.versionId())
                            .isEqualTo(UUID.fromString((String) terms(fourth).get("versionId")));
                    assertThat(current.number()).isEqualTo(4);
                    assertThat(current.priceMinor()).isEqualTo(500_000);
                    assertThat(current.segments()).containsExactly(new ServiceSegment(SegmentKind.ACTIVE, 90));
                });

        assertThat(api.call(alpha, "DELETE", SERVICES + "/" + serviceId + "?version=3", null, manager)
                        .status())
                .isEqualTo(204);

        assertThat(as(alpha, () -> serviceTerms.current(serviceId))).isEmpty();
        ServiceTermsVersion afterDelete =
                as(alpha, () -> serviceTerms.version(firstVersion)).orElseThrow();
        assertThat(afterDelete.priceMinor()).isEqualTo(450_000);
        assertThat(afterDelete.segments()).isEqualTo(original.segments());
        assertThat(afterDelete.bookableOnline()).isFalse();
    }

    @Test
    void aHiddenServicesTermsSayItIsNotBookableOnline() throws Exception {
        Map<String, Object> service = api.created(alpha, manager, service(colour, "Root touch-up", 4500, 60, 0));
        api.call(
                alpha,
                "PUT",
                SERVICES + "/" + service.get("id") + "/online",
                Map.of("bookableOnline", false, "version", 0),
                manager);

        assertThat(as(alpha, () -> serviceTerms.current(UUID.fromString((String) service.get("id"))))
                        .orElseThrow()
                        .bookableOnline())
                .isFalse();
    }

    @Test
    void anotherSalonsServiceAndVersionAreNotFoundAndAnUnknownIdIsTheSame() throws Exception {
        Map<String, Object> service = api.created(alpha, manager, service(colour, "Root touch-up", 4500, 60, 0));
        UUID serviceId = UUID.fromString((String) service.get("id"));
        UUID versionId = UUID.fromString((String) terms(service).get("versionId"));

        assertThat(as(beta, () -> serviceTerms.current(serviceId))).isEmpty();
        assertThat(as(beta, () -> serviceTerms.version(versionId))).isEmpty();
        assertThat(as(alpha, () -> serviceTerms.current(UUID.randomUUID()))).isEmpty();
        assertThat(as(alpha, () -> serviceTerms.version(UUID.randomUUID()))).isEmpty();
    }

    @Test
    void aTermsVersionIsNeverChangedByAnyRole() {
        Map<String, Object> service = api.created(alpha, manager, service(colour, "Root touch-up", 4500, 60, 0));
        UUID versionId = UUID.fromString((String) terms(service).get("versionId"));

        assertThatThrownBy(() ->
                        owner.update("UPDATE catalogue_terms_version SET price_minor = 1 WHERE id = ?", versionId))
                .rootCause()
                .hasMessageContaining("a terms version never changes");
        assertThat(owner.queryForObject(
                        "SELECT has_table_privilege(?, 'catalogue_terms_version', 'UPDATE')"
                                + " OR has_table_privilege(?, 'catalogue_terms_version', 'DELETE')",
                        Boolean.class,
                        TestPostgres.APP_USER,
                        TestPostgres.APP_USER))
                .isFalse();
    }
}
