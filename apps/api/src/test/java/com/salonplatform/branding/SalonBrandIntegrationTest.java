package com.salonplatform.branding;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.branding.domain.BrandImagesTest;
import com.salonplatform.identity.adapter.IdentityCrypto;
import com.salonplatform.identity.domain.StaffRole;
import com.salonplatform.platform.OperatorTestSupport;
import com.salonplatform.platform.adapter.OperatorCrypto;
import com.salonplatform.platform.service.OperatorAccountService;
import com.salonplatform.testing.Http;
import com.salonplatform.testing.IntegrationTest;
import com.salonplatform.testing.OpenApiContract;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import com.salonplatform.testing.TestSalons;
import com.salonplatform.testing.TestSalons.TestSalon;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * F04-S02 through HTTP: the brand draft saved, reset, published and discarded, with GET /api/v1/salon
 * serving only what was published (AC-3, AC-5); uploads checked by content (AC-4); the brand's rules
 * (AC-6); images served by the published brand's rules with no-store and nosniff (AC-7); a stale version and
 * two publications at once (AC-9); the audit trail (AC-11). Every response is held to the committed OpenAPI
 * document.
 */
class SalonBrandIntegrationTest extends IntegrationTest {

    static final String DRAFT = "/api/v1/salon/brand/draft";
    static final String RESET = "/api/v1/salon/brand/draft/reset";
    static final String PUBLICATION = "/api/v1/salon/brand/publication";
    static final String IMAGES = "/api/v1/salon/brand/images";
    static final String IMAGE = "/api/v1/salon/brand/images/{id}";
    static final String SALON = "/api/v1/salon";

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
    private final OpenApiContract contract = OpenApiContract.committed();
    private SettingsTestSupport settings;
    private TestSalon salon;

    @BeforeEach
    void aSalon() {
        TestSalons salons = new TestSalons(owner);
        OperatorTestSupport console =
                new OperatorTestSupport(http(), port(), operators, operatorCrypto, json, environment, clock);
        settings = new SettingsTestSupport(
                http(), port(), salons, identityCrypto, json, transactions, entities, clock, console);
        salon = salons.create("Brand Studio", "#1F2937");
    }

    // --- fixtures ------------------------------------------------------------------------------------------

    static Map<String, Object> brand(String name, String tagline, String colour, Object logo, Object cover) {
        Map<String, Object> brand = new LinkedHashMap<>();
        brand.put("name", name);
        brand.put("tagline", tagline);
        brand.put("colour", colour);
        brand.put("logo", logo);
        brand.put("cover", cover);
        return brand;
    }

    static Map<String, Object> save(long version, Map<String, Object> brand) {
        return Map.of("version", version, "brand", brand);
    }

    private Http.Exchange call(String method, String path, Object body, String token) {
        Http.Exchange exchange = settings.call(salon, method, path, body, token);
        contract.assertMatches(method, path, exchange);
        return exchange;
    }

    private Map<String, Object> ok(Http.Exchange exchange) {
        assertThat(exchange.status()).withFailMessage("%s", exchange.body()).isEqualTo(200);
        return settings.body(exchange);
    }

    private void refused(Http.Exchange exchange, int status, String code) {
        assertThat(exchange.status()).withFailMessage("%s", exchange.body()).isEqualTo(status);
        assertThat(settings.body(exchange).get("code")).isEqualTo(code);
    }

    private Http.BinaryExchange upload(String kind, String name, String type, byte[] content, String token) {
        Http.BinaryExchange exchange = settings.upload(salon, kind, name, type, content, token);
        contract.assertMatches("POST", IMAGES, exchange);
        return exchange;
    }

    /** Uploads an image and returns its id. */
    private String uploaded(String kind, byte[] content, String token) {
        Http.BinaryExchange answer = upload(kind, kind + ".png", "image/png", content, token);
        assertThat(answer.status())
                .withFailMessage("%s", answer.asText().body())
                .isEqualTo(201);
        return (String) settings.body(answer.asText()).get("id");
    }

    private Http.BinaryExchange image(Object id, String token) {
        Http.BinaryExchange exchange = settings.image(salon, id, token);
        contract.assertMatches("GET", IMAGE, exchange);
        return exchange;
    }

    // JSON maps, read untyped.
    @SuppressWarnings("unchecked")
    private static Map<String, Object> brandOf(Map<String, Object> draft) {
        return (Map<String, Object>) draft.get("brand");
    }

    private Map<String, Object> publicSalon() {
        return ok(call("GET", SALON, null, null));
    }

    private long version(Map<String, Object> draft) {
        return ((Number) draft.get("version")).longValue();
    }

    private List<Map<String, Object>> auditRows() {
        return owner.queryForList(
                "SELECT actor_kind, action, after_values::text AS after FROM audit_entry"
                        + " WHERE tenant_id = ? AND action LIKE 'branding.brand%' ORDER BY created_at, id",
                salon.id());
    }

    // --- AC-3, AC-5 ------------------------------------------------------------------------------------------

    @Test
    void theDraftStartsEqualToThePublishedBrandWithNothingToPublish() {
        String token = settings.signedIn(salon, StaffRole.MANAGER).token();

        Map<String, Object> draft = ok(call("GET", DRAFT, null, token));

        assertThat(draft).containsEntry("version", 0).containsEntry("hasUnpublishedChanges", false);
        assertThat(brandOf(draft))
                .containsEntry("name", "Brand Studio")
                .containsEntry("colour", "#1F2937")
                .containsEntry("tagline", null)
                .containsEntry("logo", null)
                .containsEntry("cover", null);
        assertThat(draft.get("publishedAt")).isNull();
        Map<String, Object> published = publicSalon();
        assertThat(published)
                .containsEntry("tagline", null)
                .containsEntry("logo", null)
                .containsEntry("cover", null);
        refused(call("POST", PUBLICATION, Map.of("version", 0), token), 409, "invalid_state");
    }

    @Test
    void aSavedDraftIsKeptAndNotServedUntilPublishedThenServedExactly() throws Exception {
        String token = settings.signedIn(salon, StaffRole.OWNER).token();
        String logo = uploaded("logo", BrandImagesTest.image(600, 600, "png"), token);
        String cover = uploaded("cover", BrandImagesTest.image(1200, 500, "jpeg"), token);

        Map<String, Object> saved = ok(
                call("PUT", DRAFT, save(0, brand("  New Studio  ", " Hair & bridal ", "#2f6b5e", logo, cover)), token));

        assertThat(saved).containsEntry("version", 1).containsEntry("hasUnpublishedChanges", true);
        assertThat(brandOf(saved))
                .containsEntry("name", "New Studio")
                .containsEntry("tagline", "Hair & bridal")
                .containsEntry("colour", "#2F6B5E")
                .containsEntry("logo", Map.of("id", logo, "width", 512, "height", 512))
                .containsEntry("cover", Map.of("id", cover, "width", 1200, "height", 500));
        assertThat(ok(call("GET", DRAFT, null, token))).isEqualTo(saved);
        assertThat(publicSalon())
                .containsEntry("name", "Brand Studio")
                .containsEntry("brandColour", "#1F2937")
                .containsEntry("logo", null);

        Map<String, Object> published = ok(call("POST", PUBLICATION, Map.of("version", 1), token));

        assertThat(published).containsEntry("version", 2).containsEntry("hasUnpublishedChanges", false);
        assertThat(published.get("publishedAt")).isNotNull();
        Map<String, Object> salonNow = publicSalon();
        assertThat(salonNow)
                .containsEntry("name", "New Studio")
                .containsEntry("brandColour", "#2F6B5E")
                .containsEntry("tagline", "Hair & bridal")
                .containsEntry("logo", brandOf(saved).get("logo"))
                .containsEntry("cover", brandOf(saved).get("cover"));
        refused(call("POST", PUBLICATION, Map.of("version", 2), token), 409, "invalid_state");
    }

    @Test
    void discardReturnsTheDraftToThePublishedBrandAndResetSetsTheDefaultsKeepingTheName() throws Exception {
        String token = settings.signedIn(salon, StaffRole.MANAGER).token();
        String logo = uploaded("logo", BrandImagesTest.image(300, 300, "png"), token);
        ok(call("PUT", DRAFT, save(0, brand("Brand Studio", "Tag", "#8A4B6B", logo, null)), token));
        ok(call("POST", PUBLICATION, Map.of("version", 1), token));
        Map<String, Object> publishedBefore = publicSalon();
        ok(call("PUT", DRAFT, save(2, brand("Elsewhere", null, "#CC5500", null, null)), token));

        Map<String, Object> discarded = ok(call("DELETE", DRAFT, Map.of("version", 3), token));

        assertThat(discarded).containsEntry("hasUnpublishedChanges", false);
        assertThat(brandOf(discarded)).containsEntry("name", "Brand Studio").containsEntry("colour", "#8A4B6B");
        assertThat(publicSalon()).isEqualTo(publishedBefore);

        Map<String, Object> reset = ok(call("POST", RESET, Map.of("version", 4), token));

        assertThat(reset).containsEntry("hasUnpublishedChanges", true);
        assertThat(brandOf(reset))
                .containsEntry("name", "Brand Studio")
                .containsEntry("colour", "#D6086A")
                .containsEntry("tagline", null)
                .containsEntry("logo", null)
                .containsEntry("cover", null);
        assertThat(publicSalon()).isEqualTo(publishedBefore);
    }

    // --- AC-4 ------------------------------------------------------------------------------------------------

    @Test
    void uploadsAreToldByTheirContentAndEachRefusalIsAFieldErrorOnTheFileThatKeepsNothing() throws Exception {
        String token = settings.signedIn(salon, StaffRole.OWNER).token();
        record Bad(String kind, String name, String type, byte[] content) {}
        byte[] tooLarge = new byte[5 * 1024 * 1024 + 10];
        tooLarge[0] = (byte) 0x89;
        List<Bad> bad = List.of(
                new Bad("logo", "logo.png", "image/png", "not an image at all".getBytes(StandardCharsets.UTF_8)),
                new Bad("logo", "logo.png", "image/png", BrandImagesTest.image(300, 300, "gif")),
                new Bad("cover", "cover.jpg", "image/jpeg", tooLarge),
                new Bad("logo", "logo.png", "image/png", BrandImagesTest.image(100, 100, "png")),
                new Bad("cover", "cover.jpg", "image/jpeg", BrandImagesTest.image(1000, 200, "jpeg")),
                new Bad("cover", "cover.png", "image/png", BrandImagesTest.pngDeclaring(60_000, 60_000)),
                new Bad(
                        "cover",
                        "cover.png",
                        "image/png",
                        java.util.Arrays.copyOf(BrandImagesTest.image(1000, 400, "png"), 120)));
        for (Bad each : bad) {
            Http.BinaryExchange answer = upload(each.kind(), each.name(), each.type(), each.content(), token);
            assertThat(answer.status()).as(each.name()).isEqualTo(400);
            assertThat(answer.asText().body()).contains("\"field\":\"file\"").doesNotContain(each.name());
        }
        Http.BinaryExchange noKind =
                upload("banner", "x.png", "image/png", BrandImagesTest.image(300, 300, "png"), token);
        assertThat(noKind.asText().body()).contains("\"field\":\"kind\"");
        Http.BinaryExchange noFile = upload("logo", null, null, null, token);
        assertThat(noFile.asText().body()).contains("\"field\":\"file\"");
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM branding_image WHERE tenant_id = ?", Integer.class, salon.id()))
                .isZero();

        // A real image renamed and declared as something else is still told by its content.
        Http.BinaryExchange renamed =
                upload("cover", "notes.txt", "text/plain", BrandImagesTest.resource("cover-1000x420.webp"), token);
        assertThat(renamed.status()).isEqualTo(201);
    }

    @Test
    void aRequestOverTheRequestLimitIs413() {
        String token = settings.signedIn(salon, StaffRole.OWNER).token();
        byte[] huge = new byte[11 * 1024 * 1024];

        Http.BinaryExchange answer = upload("cover", "huge.jpg", "image/jpeg", huge, token);

        assertThat(answer.status()).isEqualTo(413);
    }

    // --- AC-6 ------------------------------------------------------------------------------------------------

    @Test
    void eachBrokenRuleIsAFieldErrorOnThatFieldAndNothingIsSaved() throws Exception {
        String token = settings.signedIn(salon, StaffRole.OWNER).token();
        String cover = uploaded("cover", BrandImagesTest.image(1000, 400, "jpeg"), token);
        Map<String, Object> before = ok(call("GET", DRAFT, null, token));
        Map<String, Map<String, Object>> cases = new LinkedHashMap<>();
        cases.put("brand.name", brand("   ", null, "#111111", null, null));
        cases.put("brand.name ", brand("n".repeat(61), null, "#111111", null, null));
        cases.put("brand.tagline", brand("Ok", "t".repeat(81), "#111111", null, null));
        cases.put("brand.colour", brand("Ok", null, "red", null, null));
        cases.put("brand.colour ", brand("Ok", null, "#12345", null, null));
        cases.put("brand.logo", brand("Ok", null, "#111111", cover, null));
        cases.put("brand.logo ", brand("Ok", null, "#111111", "not-an-id", null));
        cases.put(
                "brand.cover",
                brand("Ok", null, "#111111", null, java.util.UUID.randomUUID().toString()));
        for (Map.Entry<String, Map<String, Object>> each : cases.entrySet()) {
            Http.Exchange answer = call("PUT", DRAFT, save(0, each.getValue()), token);
            refused(answer, 400, "bad_request");
            assertThat(settings.body(answer).get("errors"))
                    .as(each.getKey())
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                    .extracting("field")
                    .containsExactly(each.getKey().strip());
        }
        Map<String, Object> sixty =
                ok(call("PUT", DRAFT, save(0, brand("n".repeat(60), "", "#abcdef", null, null)), token));
        assertThat(brandOf(sixty)).containsEntry("colour", "#ABCDEF").containsEntry("tagline", null);
        assertThat(version(before)).isZero();
    }

    // --- AC-7 ------------------------------------------------------------------------------------------------

    @Test
    void aPublishedImageIsServedToAnyoneAndADraftImageOnlyToSettingsStaff() throws Exception {
        String ownerToken = settings.signedIn(salon, StaffRole.OWNER).token();
        String receptionist = settings.signedIn(salon, StaffRole.RECEPTIONIST).token();
        String logo = uploaded("logo", BrandImagesTest.image(400, 400, "png"), ownerToken);
        String draftCover = uploaded("cover", BrandImagesTest.image(1000, 400, "jpeg"), ownerToken);
        ok(call("PUT", DRAFT, save(0, brand("Brand Studio", null, "#1F2937", logo, null)), ownerToken));
        ok(call("POST", PUBLICATION, Map.of("version", 1), ownerToken));
        ok(call("PUT", DRAFT, save(2, brand("Brand Studio", null, "#1F2937", logo, draftCover)), ownerToken));

        Http.BinaryExchange published = image(logo, null);
        assertThat(published.status()).isEqualTo(200);
        assertThat(published.contentType()).isEqualTo("image/png");
        assertThat(published.headers())
                .containsEntry("x-content-type-options", "nosniff")
                .containsEntry("cache-control", "private, no-cache, no-store, max-age=0, must-revalidate");
        assertThat(published.body()[1]).isEqualTo((byte) 'P');

        Http.BinaryExchange anonymous = image(draftCover, null);
        Http.BinaryExchange notSettings = image(draftCover, receptionist);
        Http.BinaryExchange unknown = image(java.util.UUID.randomUUID(), null);
        Http.BinaryExchange settingsStaff = image(draftCover, ownerToken);
        assertThat(anonymous.status()).isEqualTo(404);
        assertThat(notSettings.status()).isEqualTo(404);
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(settingsStaff.status()).isEqualTo(200);
        assertThat(settingsStaff.contentType()).isEqualTo("image/jpeg");
        assertThat(settingsStaff.headers()).containsEntry("x-content-type-options", "nosniff");
    }

    // --- AC-9 ------------------------------------------------------------------------------------------------

    @Test
    void aStaleVersionIsAConflictOnEveryChangeAndChangesNothing() {
        String token = settings.signedIn(salon, StaffRole.OWNER).token();
        ok(call("PUT", DRAFT, save(0, brand("Mine", null, "#111111", null, null)), token));
        Map<String, Object> current = ok(call("GET", DRAFT, null, token));

        refused(call("PUT", DRAFT, save(0, brand("Theirs", null, "#222222", null, null)), token), 409, "conflict");
        refused(call("POST", RESET, Map.of("version", 0), token), 409, "conflict");
        refused(call("POST", PUBLICATION, Map.of("version", 0), token), 409, "conflict");
        refused(call("DELETE", DRAFT, Map.of("version", 0), token), 409, "conflict");

        assertThat(ok(call("GET", DRAFT, null, token))).isEqualTo(current);
    }

    @Test
    void ofSeveralPublicationsOfTheSameVersionSentAtOnceExactlyOneSucceeds() throws Exception {
        String token = settings.signedIn(salon, StaffRole.OWNER).token();
        ok(call("PUT", DRAFT, save(0, brand("Race Studio", null, "#2F4B8A", null, null)), token));
        int attempts = 6;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Http.Exchange>> answers = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(attempts)) {
            for (int i = 0; i < attempts; i++) {
                answers.add(pool.submit(() -> {
                    start.await();
                    return settings.call(salon, "POST", PUBLICATION, Map.of("version", 1), token);
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Http.Exchange> answer : answers) {
                Http.Exchange exchange = answer.get();
                contract.assertMatches("POST", PUBLICATION, exchange);
                statuses.add(exchange.status());
            }
            assertThat(statuses).filteredOn(status -> status == 200).hasSize(1);
            assertThat(statuses).filteredOn(status -> status == 409).hasSize(attempts - 1);
        }
        assertThat(publicSalon()).containsEntry("name", "Race Studio");
    }

    // --- AC-11 -----------------------------------------------------------------------------------------------

    @Test
    void everyChangeAndUploadIsAuditedWithFieldNamesButNoValueFileNameOrBytes() throws Exception {
        String token = settings.signedIn(salon, StaffRole.MANAGER).token();
        String logo = uploaded("logo", BrandImagesTest.image(300, 300, "png"), token);
        ok(call("PUT", DRAFT, save(0, brand("Audited Studio", "Secret tagline", "#CC5500", logo, null)), token));
        ok(call("POST", PUBLICATION, Map.of("version", 1), token));
        ok(call("POST", RESET, Map.of("version", 2), token));
        ok(call("DELETE", DRAFT, Map.of("version", 3), token));

        List<Map<String, Object>> rows = auditRows();

        assertThat(rows)
                .extracting(row -> row.get("action"))
                .containsExactly(
                        "branding.brand_image_uploaded",
                        "branding.brand_draft_saved",
                        "branding.brand_published",
                        "branding.brand_draft_reset",
                        "branding.brand_draft_discarded");
        assertThat(rows).allSatisfy(row -> assertThat(row).containsEntry("actor_kind", "staff"));
        assertThat((String) rows.get(1).get("after")).contains("name", "tagline", "colour", "logo");
        assertThat((String) rows.get(3).get("after")).contains("colour", "logo", "tagline");
        for (Map<String, Object> row : rows) {
            assertThat((String) row.get("after"))
                    .doesNotContain("Audited Studio", "Secret tagline", "#CC5500", "logo.png", "PNG");
        }
    }
}
