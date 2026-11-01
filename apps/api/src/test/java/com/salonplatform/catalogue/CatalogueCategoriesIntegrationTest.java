package com.salonplatform.catalogue;

import static com.salonplatform.catalogue.CatalogueFixture.CATEGORIES;
import static com.salonplatform.catalogue.CatalogueFixture.DEFAULT_CATEGORIES;
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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * The salon's categories (F05-S01 AC-1, AC-2, AC-15): the ten defaults in order, then added, renamed, moved and
 * deleted by an Owner or Manager under the prototype's rules, every refusal a 400 field error or a 409 with
 * nothing changed, and every change in the audit trail. Held to the committed contract.
 */
class CatalogueCategoriesIntegrationTest extends IntegrationTest {

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
    private CatalogueFixture api;
    private TestSalon alpha;
    private String manager;

    @BeforeEach
    void aSalonAndItsManager() {
        salons = new TestSalons(owner);
        OperatorTestSupport console =
                new OperatorTestSupport(http(), port(), operators, operatorCrypto, json, environment, clock);
        SettingsTestSupport settings = new SettingsTestSupport(
                http(), port(), salons, identityCrypto, json, transactions, entities, clock, console);
        api = new CatalogueFixture(settings, http(), port());
        alpha = salons.create("Categories Alpha", "#D6086A");
        manager = settings.signedIn(alpha, StaffRole.MANAGER).token();
    }

    private List<String> names() {
        return api.categories(alpha, manager).stream()
                .map(category -> (String) category.get("name"))
                .toList();
    }

    private List<Map<String, Object>> rows() {
        return owner.queryForList(
                "SELECT id, name, position, version FROM catalogue_category WHERE tenant_id = ? ORDER BY position",
                alpha.id());
    }

    private void refused(Http.Exchange exchange, int status, String code) {
        assertThat(exchange.status()).withFailMessage("%s", exchange.body()).isEqualTo(status);
        assertThat(api.body(exchange).get("code")).isEqualTo(code);
    }

    private void fieldError(Http.Exchange exchange, String field) {
        refused(exchange, 400, "bad_request");
        assertThat(api.body(exchange).get("errors").toString()).contains("field=" + field);
    }

    private static Map<String, Object> name(String name) {
        return Map.of("name", name);
    }

    private Map<String, Object> category(String name) {
        return api.categories(alpha, manager).stream()
                .filter(category -> name.equals(category.get("name")))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void aSalonStartsWithThePrototypesTenCategoriesInOrderOnItsDefaultBranch() {
        List<Map<String, Object>> categories = api.categories(alpha, manager);

        assertThat(categories)
                .extracting(category -> category.get("name"))
                .containsExactlyElementsOf(DEFAULT_CATEGORIES);
        assertThat(categories)
                .extracting(category -> category.get("serviceCount"))
                .containsOnly(0);
        assertThat(owner.queryForList(
                        "SELECT DISTINCT branch_id FROM catalogue_category WHERE tenant_id = ?",
                        UUID.class,
                        alpha.id()))
                .containsExactly(salons.defaultBranch(alpha));
    }

    @Test
    void givingTheDefaultsAgainChangesNothingASalonDidToThem() {
        UUID colour = api.category(alpha, manager, "Colour");
        long version = ((Number) category("Colour").get("version")).longValue();
        assertThat(api.call(
                                alpha,
                                "PUT",
                                CATEGORIES + "/" + colour,
                                Map.of("name", "Hair colour", "version", version),
                                manager)
                        .status())
                .isEqualTo(200);
        UUID waxing = api.category(alpha, manager, "Waxing");
        assertThat(api.call(alpha, "DELETE", CATEGORIES + "/" + waxing + "?version=0", null, manager)
                        .status())
                .isEqualTo(204);
        List<Map<String, Object>> before = rows();

        owner.queryForList("SELECT catalogue_add_default_categories(?, now())::text", alpha.id());

        assertThat(rows()).isEqualTo(before);
        assertThat(names())
                .contains("Hair colour")
                .doesNotContain("Colour", "Waxing")
                .hasSize(9);
    }

    @Test
    void anAddedCategoryGoesToTheEndTrimmedAndIsAudited() {
        Http.Exchange added = api.call(alpha, "POST", CATEGORIES, name("  Kids’ cuts  "), manager);

        assertThat(added.status()).isEqualTo(201);
        assertThat(api.body(added)).containsEntry("name", "Kids’ cuts").containsEntry("position", 11);
        assertThat(names()).last().isEqualTo("Kids’ cuts");
        String id = (String) api.body(added).get("id");
        assertThat(owner.queryForList(
                        "SELECT action, after_values::text AS after FROM audit_entry WHERE tenant_id = ? AND subject_id = ?",
                        alpha.id(),
                        id))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry).containsEntry("action", "catalogue.category_added");
                    assertThat((String) entry.get("after")).contains("Kids’ cuts");
                });
    }

    @Test
    void aNameOutsideTwoToThirtyCharactersOrTakenInAnyCaseIsAFieldErrorAndNothingChanges() {
        List<Map<String, Object>> before = rows();

        fieldError(api.call(alpha, "POST", CATEGORIES, name("K"), manager), "name");
        fieldError(api.call(alpha, "POST", CATEGORIES, name("   "), manager), "name");
        fieldError(api.call(alpha, "POST", CATEGORIES, name("x".repeat(31)), manager), "name");
        fieldError(api.call(alpha, "POST", CATEGORIES, name("colour"), manager), "name");
        fieldError(api.call(alpha, "POST", CATEGORIES, Map.of("name", "Ok name", "extra", 1), manager), "extra");
        UUID nails = api.category(alpha, manager, "Nails");
        fieldError(
                api.call(alpha, "PUT", CATEGORIES + "/" + nails, Map.of("name", "WAXING", "version", 0), manager),
                "name");

        assertThat(rows()).isEqualTo(before);
        assertThat(api.call(alpha, "POST", CATEGORIES, name("Ab"), manager).status())
                .isEqualTo(201);
        assertThat(api.call(alpha, "POST", CATEGORIES, name("y".repeat(30)), manager)
                        .status())
                .isEqualTo(201);
    }

    @Test
    void aRenameNeedsTheCurrentVersionAndAChangedName() {
        UUID nails = api.category(alpha, manager, "Nails");

        refused(
                api.call(alpha, "PUT", CATEGORIES + "/" + nails, Map.of("name", "Nails", "version", 0), manager),
                409,
                "invalid_state");
        Http.Exchange renamed = api.call(
                alpha, "PUT", CATEGORIES + "/" + nails, Map.of("name", "nails & hands", "version", 0), manager);
        assertThat(renamed.status()).isEqualTo(200);
        assertThat(api.body(renamed)).containsEntry("name", "nails & hands").containsEntry("version", 1);
        refused(
                api.call(alpha, "PUT", CATEGORIES + "/" + nails, Map.of("name", "Hands", "version", 0), manager),
                409,
                "conflict");
        refused(
                api.call(
                        alpha,
                        "PUT",
                        CATEGORIES + "/" + UUID.randomUUID(),
                        Map.of("name", "Hands", "version", 0),
                        manager),
                404,
                "not_found");
        assertThat(category("nails & hands")).containsEntry("position", 4);
    }

    @Test
    void aMoveSwapsWithItsNeighbourAtOnceAndAStaleListIsAConflict() {
        long listVersion = api.listVersion(alpha, manager);
        UUID colour = api.category(alpha, manager, "Colour");

        Http.Exchange moved = api.call(
                alpha,
                "POST",
                CATEGORIES + "/" + colour + "/move",
                Map.of("direction", "up", "listVersion", listVersion),
                manager);

        assertThat(moved.status()).isEqualTo(200);
        assertThat(api.body(moved)).containsEntry("listVersion", (int) listVersion + 1);
        assertThat(names()).startsWith("Colour", "Cuts & styling", "Facials & skin");
        List<Map<String, Object>> before = rows();
        refused(
                api.call(
                        alpha,
                        "POST",
                        CATEGORIES + "/" + colour + "/move",
                        Map.of("direction", "down", "listVersion", listVersion),
                        manager),
                409,
                "conflict");
        assertThat(rows()).isEqualTo(before);
    }

    @Test
    void theFirstCannotMoveUpNorTheLastDownAndABadDirectionIsAFieldError() {
        long listVersion = api.listVersion(alpha, manager);
        UUID first = api.category(alpha, manager, "Cuts & styling");
        UUID last = api.category(alpha, manager, "Consultations");
        List<Map<String, Object>> before = rows();

        refused(
                api.call(
                        alpha,
                        "POST",
                        CATEGORIES + "/" + first + "/move",
                        Map.of("direction", "up", "listVersion", listVersion),
                        manager),
                409,
                "invalid_state");
        refused(
                api.call(
                        alpha,
                        "POST",
                        CATEGORIES + "/" + last + "/move",
                        Map.of("direction", "down", "listVersion", listVersion),
                        manager),
                409,
                "invalid_state");
        fieldError(
                api.call(
                        alpha,
                        "POST",
                        CATEGORIES + "/" + last + "/move",
                        Map.of("direction", "left", "listVersion", listVersion),
                        manager),
                "direction");

        assertThat(rows()).isEqualTo(before);
        assertThat(api.listVersion(alpha, manager)).isEqualTo(listVersion);
    }

    @Test
    void ofTwoMovesFromTheSameListExactlyOneIsKept() throws Exception {
        long listVersion = api.listVersion(alpha, manager);
        UUID nails = api.category(alpha, manager, "Nails");
        UUID waxing = api.category(alpha, manager, "Waxing");

        List<Integer> statuses = CatalogueFixture.together(
                () -> api.call(
                                alpha,
                                "POST",
                                CATEGORIES + "/" + nails + "/move",
                                Map.of("direction", "up", "listVersion", listVersion),
                                manager)
                        .status(),
                () -> api.call(
                                alpha,
                                "POST",
                                CATEGORIES + "/" + waxing + "/move",
                                Map.of("direction", "down", "listVersion", listVersion),
                                manager)
                        .status());

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(api.listVersion(alpha, manager)).isEqualTo(listVersion + 1);
        assertThat(rows()).extracting(row -> row.get("position")).doesNotHaveDuplicates();
    }

    @Test
    void aCategoryWithServicesIsInUseAndTheLastCategoryCannotBeDeleted() {
        UUID colour = api.category(alpha, manager, "Colour");
        api.created(alpha, manager, service(colour, "Root touch-up", 4500, 60, 0));
        List<Map<String, Object>> before = rows();

        refused(api.call(alpha, "DELETE", CATEGORIES + "/" + colour + "?version=0", null, manager), 409, "in_use");
        assertThat(category("Colour")).containsEntry("serviceCount", 1);
        assertThat(rows()).isEqualTo(before);

        for (Map<String, Object> category : api.categories(alpha, manager)) {
            if (!"Colour".equals(category.get("name"))) {
                assertThat(api.call(
                                        alpha,
                                        "DELETE",
                                        CATEGORIES + "/" + category.get("id") + "?version=" + category.get("version"),
                                        null,
                                        manager)
                                .status())
                        .isEqualTo(204);
            }
        }
        assertThat(names()).containsExactly("Colour");
    }

    @Test
    void theLastCategoryIsNeverDeleted() {
        List<Map<String, Object>> categories = api.categories(alpha, manager);
        for (Map<String, Object> category : categories.subList(1, categories.size())) {
            api.call(alpha, "DELETE", CATEGORIES + "/" + category.get("id") + "?version=0", null, manager);
        }
        Map<String, Object> last = categories.getFirst();

        refused(
                api.call(alpha, "DELETE", CATEGORIES + "/" + last.get("id") + "?version=0", null, manager),
                409,
                "invalid_state");
        assertThat(names()).containsExactly((String) last.get("name"));
    }

    @Test
    void aDeleteNeedsTheCurrentVersionAndIsAudited() {
        UUID waxing = api.category(alpha, manager, "Waxing");

        refused(api.call(alpha, "DELETE", CATEGORIES + "/" + waxing + "?version=3", null, manager), 409, "conflict");
        fieldError(api.call(alpha, "DELETE", CATEGORIES + "/" + waxing, null, manager), "version");
        assertThat(api.call(alpha, "DELETE", CATEGORIES + "/" + waxing + "?version=0", null, manager)
                        .status())
                .isEqualTo(204);

        assertThat(names()).doesNotContain("Waxing");
        assertThat(owner.queryForObject(
                        "SELECT before_values::text FROM audit_entry WHERE tenant_id = ? AND subject_id = ?"
                                + " AND action = 'catalogue.category_deleted'",
                        String.class,
                        alpha.id(),
                        waxing.toString()))
                .contains("Waxing");
    }

    @Test
    void aSalonHasAtMostFiftyCategories() {
        for (int i = 11; i <= 50; i++) {
            assertThat(api.call(alpha, "POST", CATEGORIES, name("Category " + i), manager)
                            .status())
                    .isEqualTo(201);
        }
        long listVersion = api.listVersion(alpha, manager);

        refused(api.call(alpha, "POST", CATEGORIES, name("Category 51"), manager), 409, "limit_reached");
        assertThat(rows()).hasSize(50);
        assertThat(api.listVersion(alpha, manager)).isEqualTo(listVersion);
    }

    @Test
    void renamingAndMovingAreAuditedWithBeforeAndAfter() {
        UUID nails = api.category(alpha, manager, "Nails");
        api.call(alpha, "PUT", CATEGORIES + "/" + nails, Map.of("name", "Hands", "version", 0), manager);
        api.call(
                alpha,
                "POST",
                CATEGORIES + "/" + nails + "/move",
                Map.of("direction", "down", "listVersion", api.listVersion(alpha, manager)),
                manager);

        assertThat(owner.queryForList(
                        "SELECT action, actor_kind, before_values::text AS before, after_values::text AS after FROM audit_entry"
                                + " WHERE tenant_id = ? AND subject_id = ? ORDER BY created_at, action",
                        alpha.id(),
                        nails.toString()))
                .satisfiesExactlyInAnyOrder(
                        renamed -> {
                            assertThat(renamed).containsEntry("action", "catalogue.category_renamed");
                            assertThat((String) renamed.get("before")).contains("Nails");
                            assertThat((String) renamed.get("after")).contains("Hands");
                        },
                        moved -> {
                            assertThat(moved).containsEntry("action", "catalogue.category_moved");
                            assertThat((String) moved.get("before")).contains("4");
                            assertThat((String) moved.get("after")).contains("5");
                        });
    }
}
