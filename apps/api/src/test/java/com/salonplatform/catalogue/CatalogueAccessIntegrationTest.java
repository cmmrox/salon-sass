package com.salonplatform.catalogue;

import static com.salonplatform.catalogue.CatalogueFixture.CATEGORIES;
import static com.salonplatform.catalogue.CatalogueFixture.MENU;
import static com.salonplatform.catalogue.CatalogueFixture.POPULAR_SETTING;
import static com.salonplatform.catalogue.CatalogueFixture.SERVICES;
import static com.salonplatform.catalogue.CatalogueFixture.service;
import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.branding.SettingsTestSupport;
import com.salonplatform.branding.domain.BrandImagesTest;
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
import java.util.ArrayList;
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
 * Who reaches the catalogue (F05-S01 AC-13, AC-14): every role and a support session read both lists; only the
 * Owner and Manager change anything, the other roles are refused 403 {@code permission_denied} on every change, a
 * support session {@code support_read_only}, a suspended salon {@code salon_suspended} and a read-only salon's
 * changes {@code salon_read_only}, with nothing changed. Another salon's categories, services and photos are never
 * readable or changeable and answer "not found"; a service cannot be put in another salon's category. Held to the
 * committed contract.
 */
class CatalogueAccessIntegrationTest extends IntegrationTest {

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
    private String alphasOwner;
    private UUID colour;
    private Map<String, Object> service;

    @BeforeEach
    void twoSalonsAndAlphasService() {
        salons = new TestSalons(owner);
        OperatorTestSupport console =
                new OperatorTestSupport(http(), port(), operators, operatorCrypto, json, environment, clock);
        settings = new SettingsTestSupport(
                http(), port(), salons, identityCrypto, json, transactions, entities, clock, console);
        api = new CatalogueFixture(settings, http(), port());
        alpha = salons.create("Access Alpha", "#D6086A");
        beta = salons.create("Access Beta", "#1F2937");
        alphasOwner = settings.signedIn(alpha, StaffRole.OWNER).token();
        colour = api.category(alpha, alphasOwner, "Colour");
        service = api.created(alpha, alphasOwner, CatalogueFixture.service(colour, "Root touch-up", 4500, 60, 10));
        assertThat(api.upload(alpha, service.get("id"), "photo.png", photo(), alphasOwner)
                        .status())
                .isEqualTo(200);
        service = api.body(api.call(alpha, "GET", SERVICES + "/" + service.get("id"), null, alphasOwner));
    }

    private static byte[] photo() {
        try {
            return BrandImagesTest.image(400, 400, "png");
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void refused(Http.Exchange exchange, int status, String code) {
        assertThat(exchange.status()).withFailMessage("%s", exchange.body()).isEqualTo(status);
        assertThat(api.body(exchange).get("code")).isEqualTo(code);
    }

    private void refused(Http.BinaryExchange exchange, int status, String code) {
        refused(exchange.asText(), status, code);
    }

    /** Everything of a salon's catalogue, so that a refusal can be shown to have changed nothing. */
    private List<Map<String, Object>> everything(TestSalon salon) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String table : CatalogueRowLevelSecurityAloneIntegrationTest.TABLES) {
            rows.addAll(owner.queryForList(
                    "SELECT '" + table + "' AS t, id::text AS id, updated_at FROM " + table
                            + " WHERE tenant_id = ? ORDER BY id",
                    salon.id()));
        }
        return rows;
    }

    /** Every change endpoint, on this salon's hostname with these ids, each refused with this answer. */
    private void everyChangeRefused(
            TestSalon salon, String token, UUID category, Object serviceId, int status, String code) {
        String one = SERVICES + "/" + serviceId;
        refused(api.call(salon, "POST", CATEGORIES, Map.of("name", "Kids"), token), status, code);
        refused(
                api.call(salon, "PUT", CATEGORIES + "/" + category, Map.of("name", "Hair colour", "version", 0), token),
                status,
                code);
        refused(
                api.call(
                        salon,
                        "POST",
                        CATEGORIES + "/" + category + "/move",
                        Map.of("direction", "up", "listVersion", 0),
                        token),
                status,
                code);
        refused(api.call(salon, "DELETE", CATEGORIES + "/" + category + "?version=0", null, token), status, code);
        refused(
                api.call(salon, "POST", SERVICES, CatalogueFixture.service(category, "New one", 2500, 45, 0), token),
                status,
                code);
        refused(
                api.call(salon, "PUT", one, CatalogueFixture.save(service, "priceMinor", 500_000), token),
                status,
                code);
        refused(api.call(salon, "POST", one + "/duplicate", null, token), status, code);
        refused(
                api.call(
                        salon,
                        "PUT",
                        one + "/online",
                        Map.of("bookableOnline", false, "version", service.get("version")),
                        token),
                status,
                code);
        refused(api.call(salon, "DELETE", one + "?version=" + service.get("version"), null, token), status, code);
        refused(api.upload(salon, serviceId, "photo.png", photo(), token), status, code);
        refused(api.call(salon, "DELETE", one + "/photo", null, token), status, code);
        // F05-S02: Popular and its setting; variants and deposits travel in the service save above.
        refused(
                api.call(
                        salon,
                        "PUT",
                        one + "/popular",
                        Map.of("popular", true, "version", service.get("version")),
                        token),
                status,
                code);
        refused(
                api.call(salon, "PUT", POPULAR_SETTING, Map.of("mode", "most_booked", "version", 0), token),
                status,
                code);
        refused(
                api.call(
                        salon,
                        "POST",
                        SERVICES,
                        CatalogueFixture.withVariants(
                                CatalogueFixture.service(category, "Variant one", 2500, 45, 0),
                                "Hair length",
                                CatalogueFixture.variant(null, "Short", 2500, 45),
                                CatalogueFixture.variant(null, "Long", 4000, 75)),
                        token),
                status,
                code);
    }

    private void bothListsRead(TestSalon salon, String token) {
        assertThat(api.call(salon, "GET", CATEGORIES, null, token).status()).isEqualTo(200);
        assertThat(api.call(salon, "GET", SERVICES, null, token).status()).isEqualTo(200);
        assertThat(api.call(salon, "GET", SERVICES + "/" + service.get("id"), null, token)
                        .status())
                .isEqualTo(200);
        assertThat(api.call(salon, "GET", POPULAR_SETTING, null, token).status())
                .isEqualTo(200);
    }

    @ParameterizedTest
    @EnumSource(
            value = StaffRole.class,
            names = {"RECEPTIONIST", "PROFESSIONAL", "ACCOUNTANT"})
    void otherRolesReadBothListsAndAreRefusedEveryChange(StaffRole role) {
        String token = settings.signedIn(alpha, role).token();
        List<Map<String, Object>> before = everything(alpha);

        bothListsRead(alpha, token);
        everyChangeRefused(alpha, token, colour, service.get("id"), 403, "permission_denied");

        assertThat(everything(alpha)).isEqualTo(before);
    }

    @Test
    void theManagerChangesTheCatalogueAsTheOwnerDoes() {
        String manager = settings.signedIn(alpha, StaffRole.MANAGER).token();

        bothListsRead(alpha, manager);
        assertThat(api.call(alpha, "POST", SERVICES + "/" + service.get("id") + "/duplicate", null, manager)
                        .status())
                .isEqualTo(201);
    }

    @Test
    void aSupportSessionReadsBothListsAndIsRefusedEveryChange() {
        String support = settings.supportSession(alpha);
        List<Map<String, Object>> before = everything(alpha);

        bothListsRead(alpha, support);
        everyChangeRefused(alpha, support, colour, service.get("id"), 403, "support_read_only");

        assertThat(everything(alpha)).isEqualTo(before);
    }

    @Test
    void withoutASessionTheWorkspaceListsAndChangesAreRefused() {
        refused(api.call(alpha, "GET", CATEGORIES, null, null), 401, "invalid_session");
        refused(api.call(alpha, "GET", SERVICES, null, null), 401, "invalid_session");
        refused(api.call(alpha, "GET", POPULAR_SETTING, null, null), 401, "invalid_session");
        refused(api.call(alpha, "POST", CATEGORIES, Map.of("name", "Kids"), null), 401, "invalid_session");
    }

    @Test
    void aSuspendedSalonAnswersSalonSuspendedEverywhere() {
        String manager = settings.signedIn(alpha, StaffRole.MANAGER).token();
        salons.suspend(alpha);
        try {
            refused(api.call(alpha, "GET", CATEGORIES, null, manager), 403, "salon_suspended");
            refused(api.call(alpha, "GET", SERVICES, null, manager), 403, "salon_suspended");
            refused(api.call(alpha, "GET", MENU, null, null), 403, "salon_suspended");
            refused(api.photo(alpha, service.get("photoUrl")), 403, "salon_suspended");
            everyChangeRefused(alpha, manager, colour, service.get("id"), 403, "salon_suspended");
        } finally {
            salons.activate(alpha);
        }
    }

    @Test
    void aReadOnlySalonReadsAndRefusesEveryChangeWithSalonReadOnly() {
        String manager = settings.signedIn(alpha, StaffRole.MANAGER).token();
        owner.update("UPDATE tenant SET access_state = 'read_only' WHERE id = ?", alpha.id());
        List<Map<String, Object>> before = everything(alpha);

        bothListsRead(alpha, manager);
        assertThat(api.call(alpha, "GET", MENU, null, null).status()).isEqualTo(200);
        everyChangeRefused(alpha, manager, colour, service.get("id"), 403, "salon_read_only");

        assertThat(everything(alpha)).isEqualTo(before);
    }

    @Test
    void anotherSalonsStaffNeverReadOrChangeThisSalonsCatalogue() {
        String betasOwner = settings.signedIn(beta, StaffRole.OWNER).token();
        List<Map<String, Object>> before = everything(alpha);

        // Beta's session on alpha's hostname is a session of another salon.
        refused(api.call(alpha, "GET", CATEGORIES, null, betasOwner), 404, "not_found");
        refused(api.call(alpha, "GET", SERVICES, null, betasOwner), 404, "not_found");
        refused(api.call(alpha, "GET", POPULAR_SETTING, null, betasOwner), 404, "not_found");
        everyChangeRefused(alpha, betasOwner, colour, service.get("id"), 404, "not_found");

        assertThat(everything(alpha)).isEqualTo(before);
    }

    @Test
    void onItsOwnHostnameAnotherSalonFindsNoneOfThisSalonsIdsAndCannotUseItsCategory() {
        String betasOwner = settings.signedIn(beta, StaffRole.OWNER).token();
        List<Map<String, Object>> before = everything(alpha);
        Object alphasService = service.get("id");

        refused(api.call(beta, "GET", SERVICES + "/" + alphasService, null, betasOwner), 404, "not_found");
        refused(
                api.call(beta, "PUT", CATEGORIES + "/" + colour, Map.of("name", "Taken", "version", 0), betasOwner),
                404,
                "not_found");
        refused(
                api.call(
                        beta,
                        "POST",
                        CATEGORIES + "/" + colour + "/move",
                        Map.of("direction", "up", "listVersion", api.listVersion(beta, betasOwner)),
                        betasOwner),
                404,
                "not_found");
        refused(api.call(beta, "DELETE", CATEGORIES + "/" + colour + "?version=0", null, betasOwner), 404, "not_found");
        refused(
                api.call(
                        beta,
                        "PUT",
                        SERVICES + "/" + alphasService,
                        CatalogueFixture.save(service, "priceMinor", 500_000),
                        betasOwner),
                404,
                "not_found");
        refused(
                api.call(beta, "POST", SERVICES + "/" + alphasService + "/duplicate", null, betasOwner),
                404,
                "not_found");
        refused(
                api.call(
                        beta,
                        "PUT",
                        SERVICES + "/" + alphasService + "/online",
                        Map.of("bookableOnline", false, "version", 0),
                        betasOwner),
                404,
                "not_found");
        refused(
                api.call(beta, "DELETE", SERVICES + "/" + alphasService + "?version=0", null, betasOwner),
                404,
                "not_found");
        refused(api.upload(beta, alphasService, "photo.png", photo(), betasOwner), 404, "not_found");
        refused(
                api.call(beta, "DELETE", SERVICES + "/" + alphasService + "/photo", null, betasOwner),
                404,
                "not_found");
        refused(api.photo(beta, service.get("photoUrl")), 404, "not_found");
        refused(
                api.call(
                        beta,
                        "PUT",
                        SERVICES + "/" + alphasService + "/popular",
                        Map.of("popular", true, "version", 0),
                        betasOwner),
                404,
                "not_found");
        // Each salon's Popular setting is its own: beta's switch leaves alpha's as it was.
        assertThat(api.call(beta, "PUT", POPULAR_SETTING, Map.of("mode", "most_booked", "version", 0), betasOwner)
                        .status())
                .isEqualTo(200);
        assertThat(api.body(api.call(alpha, "GET", POPULAR_SETTING, null, alphasOwner)))
                .containsEntry("mode", "chosen")
                .containsEntry("version", 0);
        // A service of beta's cannot be put in alpha's category: the field error of a category that is not beta's.
        Http.Exchange foreign =
                api.call(beta, "POST", SERVICES, CatalogueFixture.service(colour, "Stolen", 2500, 45, 0), betasOwner);
        refused(foreign, 400, "bad_request");
        assertThat(api.body(foreign).get("errors").toString()).contains("field=categoryId");

        assertThat(CatalogueFixture.maps(api.body(api.call(beta, "GET", SERVICES, null, betasOwner))
                        .get("items")))
                .isEmpty();
        assertThat(api.categories(beta, betasOwner))
                .extracting(category -> category.get("id"))
                .doesNotContain(colour.toString());
        assertThat(CatalogueFixture.maps(
                        api.body(api.call(beta, "GET", MENU, null, null)).get("categories")))
                .isEmpty();
        assertThat(everything(alpha)).isEqualTo(before);
    }

    @Test
    void anotherSalonsVariantIdIsNotOneOfThisServicesVariants() {
        String betasOwner = settings.signedIn(beta, StaffRole.OWNER).token();
        UUID betasColour = api.category(beta, betasOwner, "Colour");
        Map<String, Object> betas = api.created(
                beta,
                betasOwner,
                CatalogueFixture.withVariants(
                        CatalogueFixture.service(betasColour, "Cut", 2500, 45, 0),
                        "Hair length",
                        CatalogueFixture.variant(null, "Short", 2500, 45),
                        CatalogueFixture.variant(null, "Long", 4000, 75)));
        Object betasVariant = CatalogueFixture.maps(
                        CatalogueFixture.map(betas.get("terms")).get("variants"))
                .getFirst()
                .get("id");

        Http.Exchange stolen = api.call(
                alpha,
                "PUT",
                SERVICES + "/" + service.get("id"),
                CatalogueFixture.saveWithVariants(
                        service,
                        "Hair length",
                        CatalogueFixture.variant(betasVariant, "Short", 2500, 45),
                        CatalogueFixture.variant(null, "Long", 4000, 75)),
                alphasOwner);

        refused(stolen, 400, "bad_request");
        assertThat(api.body(stolen).get("errors").toString()).contains("field=variants.items[0].id");
    }
}
