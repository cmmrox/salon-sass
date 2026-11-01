package com.salonplatform.catalogue;

import static com.salonplatform.catalogue.CatalogueFixture.CATEGORIES;
import static com.salonplatform.catalogue.CatalogueFixture.MENU;
import static com.salonplatform.catalogue.CatalogueFixture.SERVICES;
import static com.salonplatform.catalogue.CatalogueFixture.save;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * The public menu (F05-S01 AC-11): no sign-in; the salon's categories in its order, each with its services bookable
 * online by name, the customer's time without the buffer, the photo's URL or null and the current terms version; a
 * category without such services left out; never another salon's. Held to the committed contract.
 */
class CatalogueMenuIntegrationTest extends IntegrationTest {

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

    @SuppressWarnings("unchecked") // JSON read as an untyped map.
    private List<Map<String, Object>> menuOf(TestSalon salon) {
        Http.Exchange exchange = api.call(salon, "GET", MENU, null, null);
        assertThat(exchange.status()).isEqualTo(200);
        assertThat(exchange.headers()).containsEntry("cache-control", "no-store");
        return (List<Map<String, Object>>) api.body(exchange).get("categories");
    }

    @Test
    void theMenuListsBookableServicesByCategoryInTheSalonsOrderAndByName() throws Exception {
        UUID nails = api.category(alpha, manager, "Nails");
        UUID waxing = api.category(alpha, manager, "Waxing");
        Map<String, Object> zebra = api.created(alpha, manager, service(colour, "zebra highlights", 9000, 120, 15));
        api.created(alpha, manager, service(colour, "Balayage", 12000, 180, 0));
        Map<String, Object> hidden = api.created(alpha, manager, service(nails, "Gel polish", 3000, 45, 0));
        api.call(
                alpha,
                "PUT",
                SERVICES + "/" + hidden.get("id") + "/online",
                Map.of("bookableOnline", false, "version", 0),
                manager);
        Map<String, Object> wax = api.created(alpha, manager, service(waxing, "Full legs", 4000, 30, 10));
        // Waxing moves above Colour: customers see the new order straight away.
        for (int i = 0; i < 4; i++) {
            api.call(
                    alpha,
                    "POST",
                    CATEGORIES + "/" + waxing + "/move",
                    Map.of("direction", "up", "listVersion", api.listVersion(alpha, manager)),
                    manager);
        }
        Map<String, Object> withPhoto =
                api.body(api.upload(alpha, zebra.get("id"), "z.png", BrandImagesTest.image(400, 400, "png"), manager)
                        .asText());

        List<Map<String, Object>> menu = menuOf(alpha);

        assertThat(menu).extracting(category -> category.get("name")).containsExactly("Waxing", "Colour");
        assertThat(CatalogueFixture.maps(menu.get(1).get("services")))
                .extracting(item -> item.get("name"))
                .containsExactly("Balayage", "zebra highlights");
        Map<String, Object> zebraItem =
                CatalogueFixture.maps(menu.get(1).get("services")).get(1);
        assertThat(zebraItem)
                .containsEntry("id", zebra.get("id"))
                .containsEntry("priceMinor", 900_000)
                .containsEntry("currency", "LKR")
                .containsEntry("durationMinutes", 120)
                .containsEntry("photoUrl", withPhoto.get("photoUrl"))
                .containsEntry(
                        "termsVersionId",
                        CatalogueFixture.map(zebra.get("terms")).get("versionId"))
                .containsEntry("description", null);
        assertThat(zebraItem.keySet())
                .containsExactlyInAnyOrder(
                        "id",
                        "name",
                        "description",
                        "priceMinor",
                        "currency",
                        "durationMinutes",
                        "photoUrl",
                        "termsVersionId",
                        // F05-S02: without variants, an empty list and the service's own values as the range.
                        "variantSetName",
                        "variants",
                        "priceMinMinor",
                        "priceMaxMinor",
                        "durationMinMinutes",
                        "durationMaxMinutes",
                        "deposit");
        assertThat(zebraItem)
                .containsEntry("variantSetName", null)
                .containsEntry("variants", List.of())
                .containsEntry("priceMinMinor", 900_000)
                .containsEntry("priceMaxMinor", 900_000)
                .containsEntry("durationMinMinutes", 120)
                .containsEntry("durationMaxMinutes", 120)
                .containsEntry("deposit", Map.of("kind", "salon_rule"));
        Map<String, Object> waxItem =
                CatalogueFixture.maps(menu.getFirst().get("services")).getFirst();
        assertThat(waxItem)
                .containsEntry("photoUrl", null)
                .containsEntry("durationMinutes", 30)
                .containsEntry("id", wax.get("id"));
    }

    @Test
    void aDeletedOrHiddenServiceLeavesTheMenuAndAPriceChangeShowsItsNewVersion() {
        Map<String, Object> service = api.created(alpha, manager, service(colour, "Balayage", 12000, 180, 0));
        Map<String, Object> repriced = api.body(api.call(
                alpha,
                "PUT",
                SERVICES + "/" + service.get("id"),
                CatalogueFixture.save(service, "priceMinor", 1_300_000),
                manager));

        Map<String, Object> item =
                CatalogueFixture.maps(menuOf(alpha).getFirst().get("services")).getFirst();
        assertThat(item)
                .containsEntry("priceMinor", 1_300_000)
                .containsEntry(
                        "termsVersionId",
                        CatalogueFixture.map(repriced.get("terms")).get("versionId"));

        api.call(alpha, "DELETE", SERVICES + "/" + service.get("id") + "?version=1", null, manager);
        assertThat(menuOf(alpha)).isEmpty();
    }

    @Test
    void eachSalonsMenuIsItsOwn() {
        api.created(alpha, manager, service(colour, "Balayage", 12000, 180, 0));

        assertThat(menuOf(alpha)).hasSize(1);
        assertThat(menuOf(beta)).isEmpty();
        Http.Exchange none = http().getOnHost("no-such-salon.localhost:" + port(), MENU);
        assertThat(none.status()).isEqualTo(404);
    }
}
