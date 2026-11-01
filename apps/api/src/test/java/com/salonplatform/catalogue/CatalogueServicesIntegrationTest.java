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
import java.util.LinkedHashMap;
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
 * The salon's services (F05-S01 AC-3, AC-4, AC-5, AC-7, AC-8, AC-9, AC-12, AC-15): every field's limits, terms
 * versions made only by a change of price, time or buffer and never changed after, segments in order, duplicates
 * hidden and named "(copy)" upward, the online setting alone, deletes that free the name and keep the versions, and
 * every change in the audit trail. Held to the committed contract.
 */
class CatalogueServicesIntegrationTest extends IntegrationTest {

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
    private UUID colour;
    private UUID nails;

    @BeforeEach
    void aSalonAndItsManager() {
        salons = new TestSalons(owner);
        OperatorTestSupport console =
                new OperatorTestSupport(http(), port(), operators, operatorCrypto, json, environment, clock);
        SettingsTestSupport settings = new SettingsTestSupport(
                http(), port(), salons, identityCrypto, json, transactions, entities, clock, console);
        api = new CatalogueFixture(settings, http(), port());
        alpha = salons.create("Services Alpha", "#D6086A");
        manager = settings.signedIn(alpha, StaffRole.MANAGER).token();
        colour = api.category(alpha, manager, "Colour");
        nails = api.category(alpha, manager, "Nails");
    }

    private void refused(Http.Exchange exchange, int status, String code) {
        assertThat(exchange.status()).withFailMessage("%s", exchange.body()).isEqualTo(status);
        assertThat(api.body(exchange).get("code")).isEqualTo(code);
    }

    /** A 400 naming exactly these fields. */
    @SuppressWarnings("unchecked") // JSON read as an untyped map.
    private void fieldErrors(Http.Exchange exchange, String... fields) {
        refused(exchange, 400, "bad_request");
        assertThat(((List<Map<String, Object>>) api.body(exchange).get("errors"))
                        .stream().map(error -> error.get("field")))
                .containsExactlyInAnyOrder((Object[]) fields);
    }

    private long services() {
        return count("SELECT count(*) FROM catalogue_service WHERE tenant_id = ?");
    }

    private long versions() {
        return count("SELECT count(*) FROM catalogue_terms_version WHERE tenant_id = ?");
    }

    private long count(String sql) {
        return owner.queryForObject(sql, Long.class, alpha.id());
    }

    private static Map<String, Object> with(Map<String, Object> body, String field, Object value) {
        Map<String, Object> changed = new LinkedHashMap<>(body);
        changed.put(field, value);
        return changed;
    }

    private Map<String, Object> created(String name) {
        return api.created(alpha, manager, service(colour, name, 2500, 45, 10));
    }

    private Http.Exchange saved(Map<String, Object> service, String field, Object value) {
        return api.call(alpha, "PUT", SERVICES + "/" + service.get("id"), save(service, field, value), manager);
    }

    @SuppressWarnings("unchecked") // JSON read as an untyped map.
    private static Map<String, Object> terms(Map<String, Object> service) {
        return (Map<String, Object>) service.get("terms");
    }

    @Test
    void aServiceIsCreatedWithItsFirstTermsVersionAndSegmentsInOrder() {
        Map<String, Object> body = service(colour, "  Root touch-up  ", 4500, 60, 15);
        body.put("description", "  Regrowth only  ");

        Map<String, Object> service = api.created(alpha, manager, body);

        assertThat(service)
                .containsEntry("name", "Root touch-up")
                .containsEntry("description", "Regrowth only")
                .containsEntry("categoryId", colour.toString())
                .containsEntry("bookableOnline", true)
                .containsEntry("photoId", null)
                .containsEntry("photoUrl", null);
        assertThat(terms(service))
                .containsEntry("number", 1)
                .containsEntry("priceMinor", 450_000)
                .containsEntry("currency", "LKR")
                .containsEntry("durationMinutes", 60)
                .containsEntry("bufferMinutes", 15)
                .containsEntry(
                        "segments",
                        List.of(Map.of("kind", "active", "minutes", 60), Map.of("kind", "buffer", "minutes", 15)));
        assertThat(owner.queryForMap(
                        "SELECT price_minor, currency, array_to_string(segment_kinds, ',') AS kinds,"
                                + " array_to_string(segment_minutes, ',') AS minutes FROM catalogue_terms_version"
                                + " WHERE tenant_id = ? AND id = ?",
                        alpha.id(),
                        UUID.fromString((String) terms(service).get("versionId"))))
                .containsEntry("price_minor", 450_000L)
                .containsEntry("kinds", "active,buffer")
                .containsEntry("minutes", "60,15");
    }

    @Test
    void withNoBufferTheTimeIsOneActiveSegmentAndOnlineIsOnWhenNotSent() {
        Map<String, Object> body = service(nails, "Gel polish", 3000, 45, 0);
        body.remove("bookableOnline");
        body.remove("description");

        Map<String, Object> service = api.created(alpha, manager, body);

        assertThat(service).containsEntry("bookableOnline", true).containsEntry("description", null);
        assertThat(terms(service))
                .containsEntry("segments", List.of(Map.of("kind", "active", "minutes", 45)))
                .containsEntry("bufferMinutes", 0);
    }

    @Test
    void everyFieldBoundaryIsAFieldErrorOnThatFieldAndNothingIsSaved() {
        Map<String, Object> good = service(colour, "Boundary", 2500, 45, 10);
        long before = services();

        fieldErrors(api.call(alpha, "POST", SERVICES, with(good, "name", "B"), manager), "name");
        fieldErrors(api.call(alpha, "POST", SERVICES, with(good, "name", "n".repeat(41)), manager), "name");
        fieldErrors(
                api.call(alpha, "POST", SERVICES, with(good, "description", "d".repeat(91)), manager), "description");
        fieldErrors(api.call(alpha, "POST", SERVICES, with(good, "priceMinor", 9_900), manager), "priceMinor");
        fieldErrors(api.call(alpha, "POST", SERVICES, with(good, "priceMinor", 50_000_100), manager), "priceMinor");
        fieldErrors(api.call(alpha, "POST", SERVICES, with(good, "priceMinor", 250_050), manager), "priceMinor");
        fieldErrors(api.call(alpha, "POST", SERVICES, with(good, "durationMinutes", 0), manager), "durationMinutes");
        fieldErrors(api.call(alpha, "POST", SERVICES, with(good, "durationMinutes", 7), manager), "durationMinutes");
        fieldErrors(api.call(alpha, "POST", SERVICES, with(good, "durationMinutes", 485), manager), "durationMinutes");
        fieldErrors(api.call(alpha, "POST", SERVICES, with(good, "bufferMinutes", 65), manager), "bufferMinutes");
        fieldErrors(api.call(alpha, "POST", SERVICES, with(good, "bufferMinutes", 3), manager), "bufferMinutes");
        fieldErrors(api.call(alpha, "POST", SERVICES, with(good, "bufferMinutes", -5), manager), "bufferMinutes");
        fieldErrors(api.call(alpha, "POST", SERVICES, with(good, "categoryId", null), manager), "categoryId");
        fieldErrors(
                api.call(
                        alpha,
                        "POST",
                        SERVICES,
                        with(good, "categoryId", UUID.randomUUID().toString()),
                        manager),
                "categoryId");
        fieldErrors(api.call(alpha, "POST", SERVICES, with(good, "priceMinor", "250000"), manager), "priceMinor");
        fieldErrors(api.call(alpha, "POST", SERVICES, with(good, "surprise", 1), manager), "surprise");
        fieldErrors(
                api.call(alpha, "POST", SERVICES, Map.of("name", "X", "categoryId", colour.toString()), manager),
                "name",
                "priceMinor",
                "durationMinutes",
                "bufferMinutes");

        assertThat(services()).isEqualTo(before);
        assertThat(api.created(alpha, manager, with(with(good, "name", "Bo"), "priceMinor", 10_000)))
                .isNotNull();
        assertThat(api.created(alpha, manager, with(with(good, "name", "n".repeat(40)), "priceMinor", 50_000_000)))
                .isNotNull();
        Map<String, Object> longest = with(with(good, "name", "Longest"), "durationMinutes", 480);
        longest.put("bufferMinutes", 60);
        longest.put("description", "d".repeat(90));
        assertThat(api.created(alpha, manager, longest)).isNotNull();
        assertThat(api.created(alpha, manager, with(with(good, "name", "Shortest"), "durationMinutes", 5)))
                .isNotNull();
    }

    @Test
    void aNameIsUniqueInTheSalonRegardlessOfCase() {
        created("Root touch-up");
        long before = services();

        fieldErrors(api.call(alpha, "POST", SERVICES, service(nails, "ROOT TOUCH-UP", 2500, 45, 0), manager), "name");

        assertThat(services()).isEqualTo(before);
    }

    @Test
    void aPriceTimeOrBufferChangeMakesANewVersionAndLeavesTheEarlierOneUnchanged() {
        Map<String, Object> service = created("Root touch-up");
        UUID first = UUID.fromString((String) terms(service).get("versionId"));
        Map<String, Object> firstRow = owner.queryForMap(
                "SELECT number, price_minor, array_to_string(segment_minutes, ',') AS minutes"
                        + " FROM catalogue_terms_version WHERE id = ?",
                first);

        Http.Exchange repriced = saved(service, "priceMinor", 300_000);

        assertThat(repriced.status()).isEqualTo(200);
        Map<String, Object> second = api.body(repriced);
        assertThat(terms(second)).containsEntry("number", 2).containsEntry("priceMinor", 300_000);
        assertThat(terms(second).get("versionId")).isNotEqualTo(first.toString());
        assertThat(owner.queryForMap(
                        "SELECT number, price_minor, array_to_string(segment_minutes, ',') AS minutes"
                                + " FROM catalogue_terms_version WHERE id = ?",
                        first))
                .isEqualTo(firstRow);
        Map<String, Object> third = api.body(saved(second, "durationMinutes", 60));
        assertThat(terms(third)).containsEntry("number", 3).containsEntry("durationMinutes", 60);
        Map<String, Object> fourth = api.body(saved(third, "bufferMinutes", 0));
        assertThat(terms(fourth))
                .containsEntry("number", 4)
                .containsEntry("segments", List.of(Map.of("kind", "active", "minutes", 60)));
        assertThat(versions()).isEqualTo(4);
    }

    @Test
    void aChangeOfOnlyNameDescriptionCategoryOrOnlineMakesNoVersion() {
        Map<String, Object> service = created("Root touch-up");
        Object versionId = terms(service).get("versionId");

        Map<String, Object> renamed = api.body(saved(service, "name", "Roots"));
        Map<String, Object> described = api.body(saved(renamed, "description", "Regrowth"));
        Map<String, Object> moved = api.body(saved(described, "categoryId", nails.toString()));
        Map<String, Object> hidden = api.body(saved(moved, "bookableOnline", false));

        assertThat(hidden)
                .containsEntry("name", "Roots")
                .containsEntry("description", "Regrowth")
                .containsEntry("categoryId", nails.toString())
                .containsEntry("bookableOnline", false)
                .containsEntry("version", 4);
        assertThat(terms(hidden)).containsEntry("versionId", versionId).containsEntry("number", 1);
        assertThat(versions()).isEqualTo(1);
    }

    @Test
    void aSaveThatChangesNothingIsInvalidStateAndAStaleVersionIsAConflict() {
        Map<String, Object> service = created("Root touch-up");
        Map<String, Object> repriced = api.body(saved(service, "priceMinor", 300_000));

        refused(saved(repriced, null, null), 409, "invalid_state");
        refused(saved(service, "priceMinor", 350_000), 409, "conflict");
        fieldErrors(saved(repriced, "version", null), "version");
        refused(
                api.call(alpha, "PUT", SERVICES + "/" + UUID.randomUUID(), save(repriced, null, null), manager),
                404,
                "not_found");

        assertThat(versions()).isEqualTo(2);
        assertThat(terms(api.body(api.call(alpha, "GET", SERVICES + "/" + service.get("id"), null, manager))))
                .containsEntry("priceMinor", 300_000);
    }

    @Test
    void ofTwoSavesOfTheSameVersionAtOnceExactlyOneSucceeds() throws Exception {
        Map<String, Object> service = created("Root touch-up");

        List<Integer> statuses = CatalogueFixture.together(
                () -> saved(service, "priceMinor", 300_000).status(),
                () -> saved(service, "priceMinor", 310_000).status());

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(versions()).isEqualTo(2);
    }

    @Test
    void aDuplicateIsHiddenNamedCopyUpwardAndHasItsOwnFirstVersion() {
        Map<String, Object> service = created("Root touch-up");

        Http.Exchange first = api.call(alpha, "POST", SERVICES + "/" + service.get("id") + "/duplicate", null, manager);
        Http.Exchange second =
                api.call(alpha, "POST", SERVICES + "/" + service.get("id") + "/duplicate", null, manager);

        assertThat(first.status()).isEqualTo(201);
        Map<String, Object> copy = api.body(first);
        assertThat(copy)
                .containsEntry("name", "Root touch-up (copy)")
                .containsEntry("bookableOnline", false)
                .containsEntry("categoryId", colour.toString());
        assertThat(terms(copy)).containsEntry("number", 1).containsEntry("priceMinor", 250_000);
        assertThat(terms(copy).get("versionId")).isNotEqualTo(terms(service).get("versionId"));
        assertThat(api.body(second)).containsEntry("name", "Root touch-up (copy 2)");
        assertThat(owner.queryForObject(
                        "SELECT after_values->>'duplicatedFrom' FROM audit_entry WHERE tenant_id = ?"
                                + " AND action = 'catalogue.service_duplicated' AND subject_id = ?",
                        String.class,
                        alpha.id(),
                        copy.get("id")))
                .isEqualTo(service.get("id"));
    }

    @Test
    void aDuplicatesBaseNameIsShortenedToFitFortyCharacters() {
        String forty = "Extra long hair treatment with argan oil";
        assertThat(forty).hasSize(40);
        Map<String, Object> service = created(forty);

        Map<String, Object> copy =
                api.body(api.call(alpha, "POST", SERVICES + "/" + service.get("id") + "/duplicate", null, manager));
        Map<String, Object> copy2 =
                api.body(api.call(alpha, "POST", SERVICES + "/" + service.get("id") + "/duplicate", null, manager));

        assertThat((String) copy.get("name"))
                .isEqualTo("Extra long hair treatment with ar (copy)")
                .hasSize(40);
        assertThat((String) copy2.get("name"))
                .isEqualTo("Extra long hair treatment with (copy 2)")
                .hasSizeLessThanOrEqualTo(40);
    }

    @Test
    void aDuplicatesBaseIsCutBetweenCharactersSoTheNextCopyIsCopyTwo() {
        // 32 letters and spaces, an emoji (two UTF-16 units), then 7 more: 40 characters.
        String name = "Keratin smoothing with blow dry \uD83D\uDC87 deluxe";
        Map<String, Object> service = created(name);

        Map<String, Object> copy =
                api.body(api.call(alpha, "POST", SERVICES + "/" + service.get("id") + "/duplicate", null, manager));
        Map<String, Object> copy2 =
                api.body(api.call(alpha, "POST", SERVICES + "/" + service.get("id") + "/duplicate", null, manager));

        assertThat((String) copy.get("name")).isEqualTo("Keratin smoothing with blow dry \uD83D\uDC87 (copy)");
        assertThat((String) copy2.get("name")).isEqualTo("Keratin smoothing with blow dry (copy 2)");
        assertThat(owner.queryForList(
                        "SELECT name FROM catalogue_service WHERE tenant_id = ? AND deleted_at IS NULL ORDER BY name",
                        String.class,
                        alpha.id()))
                .containsExactlyInAnyOrder(name, (String) copy.get("name"), (String) copy2.get("name"));
    }

    @Test
    void hidingAndShowingOnlineChangeOnlyTheOnlineSetting() {
        Map<String, Object> service = created("Root touch-up");
        Map<String, Object> hide = Map.of("bookableOnline", false, "version", 0);

        Http.Exchange hidden = api.call(alpha, "PUT", SERVICES + "/" + service.get("id") + "/online", hide, manager);

        assertThat(hidden.status()).isEqualTo(200);
        assertThat(api.body(hidden)).containsEntry("bookableOnline", false).containsEntry("version", 1);
        assertThat(terms(api.body(hidden))).isEqualTo(terms(service));
        refused(api.call(alpha, "PUT", SERVICES + "/" + service.get("id") + "/online", hide, manager), 409, "conflict");
        refused(
                api.call(
                        alpha,
                        "PUT",
                        SERVICES + "/" + service.get("id") + "/online",
                        Map.of("bookableOnline", false, "version", 1),
                        manager),
                409,
                "invalid_state");
        assertThat(api.call(
                                alpha,
                                "PUT",
                                SERVICES + "/" + service.get("id") + "/online",
                                Map.of("bookableOnline", true, "version", 1),
                                manager)
                        .status())
                .isEqualTo(200);
        assertThat(versions()).isEqualTo(1);
        assertThat(owner.queryForList(
                        "SELECT action FROM audit_entry WHERE tenant_id = ? AND subject_id = ? ORDER BY created_at",
                        String.class,
                        alpha.id(),
                        service.get("id")))
                .containsExactly(
                        "catalogue.service_created",
                        "catalogue.service_online_changed",
                        "catalogue.service_online_changed");
    }

    @Test
    void aDeletedServiceLeavesTheListFreesItsNameAndKeepsItsVersions() {
        Map<String, Object> service = created("Root touch-up");
        Map<String, Object> repriced = api.body(saved(service, "priceMinor", 300_000));

        refused(
                api.call(alpha, "DELETE", SERVICES + "/" + service.get("id") + "?version=0", null, manager),
                409,
                "conflict");
        assertThat(api.call(alpha, "DELETE", SERVICES + "/" + service.get("id") + "?version=1", null, manager)
                        .status())
                .isEqualTo(204);

        assertThat(CatalogueFixture.maps(api.body(api.call(alpha, "GET", SERVICES, null, manager))
                        .get("items")))
                .isEmpty();
        refused(api.call(alpha, "GET", SERVICES + "/" + service.get("id"), null, manager), 404, "not_found");
        assertThat(versions()).isEqualTo(2);
        assertThat(owner.queryForObject(
                        "SELECT price_minor FROM catalogue_terms_version WHERE id = ?",
                        Long.class,
                        UUID.fromString((String) terms(repriced).get("versionId"))))
                .isEqualTo(300_000L);
        assertThat(created("Root touch-up")).containsEntry("name", "Root touch-up");
        assertThat(owner.queryForObject(
                        "SELECT before_values->>'name' FROM audit_entry WHERE tenant_id = ?"
                                + " AND action = 'catalogue.service_deleted' AND subject_id = ?",
                        String.class,
                        alpha.id(),
                        service.get("id")))
                .isEqualTo("Root touch-up");
    }

    @Test
    void theListHasEveryLiveServiceByNameRegardlessOfCase() {
        created("zebra cut");
        created("Apple facial");
        created("mango wax");

        assertThat(CatalogueFixture.maps(api.body(api.call(alpha, "GET", SERVICES, null, manager))
                        .get("items")))
                .extracting(item -> item.get("name"))
                .containsExactly("Apple facial", "mango wax", "zebra cut");
    }

    @Test
    void theListGivesTheSalonsCurrencyBeforeItsFirstServiceAndCreatesInIt() {
        assertThat(api.body(api.call(alpha, "GET", SERVICES, null, manager)))
                .containsEntry("currency", "LKR")
                .containsEntry("items", List.of());
        owner.update("UPDATE tenant SET currency = 'USD' WHERE id = ?", alpha.id());

        assertThat(api.body(api.call(alpha, "GET", SERVICES, null, manager)))
                .containsEntry("currency", "USD")
                .containsEntry("items", List.of());
        assertThat(terms(created("Root touch-up"))).containsEntry("currency", "USD");
        assertThat(api.body(api.call(alpha, "GET", SERVICES, null, manager))).containsEntry("currency", "USD");
    }

    @Test
    void aPriceChangeIsAuditedNamingTheTermsVersionItCreated() {
        Map<String, Object> service = created("Root touch-up");
        Map<String, Object> repriced = api.body(saved(service, "priceMinor", 300_000));

        Map<String, Object> entry = owner.queryForMap(
                "SELECT actor_kind, before_values->>'priceMinor' AS before, after_values->>'priceMinor' AS after,"
                        + " after_values->>'termsVersionId' AS terms FROM audit_entry WHERE tenant_id = ?"
                        + " AND action = 'catalogue.service_updated' AND subject_id = ?",
                alpha.id(),
                service.get("id"));
        assertThat(entry)
                .containsEntry("actor_kind", "staff")
                .containsEntry("before", "250000")
                .containsEntry("after", "300000")
                .containsEntry("terms", terms(repriced).get("versionId"));
    }

    @Test
    void aSalonHasAtMostThreeHundredServicesAndDuplicateCountsToo() {
        owner.execute((java.sql.Connection connection) -> {
            try (var statement = connection.createStatement()) {
                statement.execute("BEGIN");
                statement.execute("SELECT set_config('app.tenant_id', '" + alpha.id() + "', true)");
                statement.execute("INSERT INTO catalogue_service (tenant_id, id, branch_id, category_id, name,"
                        + " bookable_online, current_terms_id, version, created_at, updated_at)"
                        + " SELECT '" + alpha.id() + "', gen_random_uuid(), '" + salons.defaultBranch(alpha) + "', '"
                        + colour + "', 'Filler ' || n, true, gen_random_uuid(), 0, now(), now()"
                        + " FROM generate_series(1, 299) n");
                statement.execute("COMMIT");
            }
            return null;
        });
        Map<String, Object> last = created("The three hundredth");
        long before = services();

        refused(
                api.call(alpha, "POST", SERVICES, service(colour, "One too many", 2500, 45, 0), manager),
                409,
                "limit_reached");
        refused(
                api.call(alpha, "POST", SERVICES + "/" + last.get("id") + "/duplicate", null, manager),
                409,
                "limit_reached");

        assertThat(services()).isEqualTo(before).isEqualTo(300);
    }
}
