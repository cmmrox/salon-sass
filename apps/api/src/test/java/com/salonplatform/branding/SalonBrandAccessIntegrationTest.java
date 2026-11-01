package com.salonplatform.branding;

import static com.salonplatform.branding.SalonBrandIntegrationTest.DRAFT;
import static com.salonplatform.branding.SalonBrandIntegrationTest.IMAGE;
import static com.salonplatform.branding.SalonBrandIntegrationTest.IMAGES;
import static com.salonplatform.branding.SalonBrandIntegrationTest.PUBLICATION;
import static com.salonplatform.branding.SalonBrandIntegrationTest.RESET;
import static com.salonplatform.branding.SalonBrandIntegrationTest.brand;
import static com.salonplatform.branding.SalonBrandIntegrationTest.save;
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
 * Who reaches the brand (F04-S02): roles without settings are refused every endpoint (AC-1); one salon's
 * settings user acts only on the hostname's salon, another salon's image is refused in a draft and not served
 * on another hostname (AC-7, AC-8); a support session reads and is refused every change and upload, and a
 * suspended or read-only salon answers as its other endpoints do (AC-9). Held to the committed contract.
 */
class SalonBrandAccessIntegrationTest extends IntegrationTest {

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
    private TestSalons salons;
    private TestSalon alpha;
    private TestSalon beta;

    @BeforeEach
    void twoSalons() {
        salons = new TestSalons(owner);
        OperatorTestSupport console =
                new OperatorTestSupport(http(), port(), operators, operatorCrypto, json, environment, clock);
        settings = new SettingsTestSupport(
                http(), port(), salons, identityCrypto, json, transactions, entities, clock, console);
        alpha = salons.create("Brand Alpha", "#D6086A");
        beta = salons.create("Brand Beta", "#1F2937");
    }

    private Http.Exchange call(TestSalon salon, String method, String path, Object body, String token) {
        Http.Exchange exchange = settings.call(salon, method, path, body, token);
        contract.assertMatches(method, path, exchange);
        return exchange;
    }

    private void refused(Http.Exchange exchange, int status, String code) {
        assertThat(exchange.status()).withFailMessage("%s", exchange.body()).isEqualTo(status);
        assertThat(settings.body(exchange).get("code")).isEqualTo(code);
    }

    private Http.BinaryExchange upload(TestSalon salon, String token) throws Exception {
        Http.BinaryExchange exchange =
                settings.upload(salon, "logo", "logo.png", "image/png", BrandImagesTest.image(300, 300, "png"), token);
        contract.assertMatches("POST", IMAGES, exchange);
        return exchange;
    }

    private String uploaded(TestSalon salon, String token) throws Exception {
        Http.BinaryExchange answer = upload(salon, token);
        assertThat(answer.status())
                .withFailMessage("%s", answer.asText().body())
                .isEqualTo(201);
        return (String) settings.body(answer.asText()).get("id");
    }

    /** Every brand row and image of a salon, as the owner sees them. */
    private List<Map<String, Object>> brandRows(TestSalon salon) {
        return owner.queryForList("""
                SELECT 'draft' AS kind, version::text AS a, display_name AS b, brand_colour AS c
                  FROM branding_brand_draft WHERE tenant_id = ?
                UNION ALL SELECT 'published', coalesce(published_at::text, ''), display_name, brand_colour
                  FROM salon_brand WHERE tenant_id = ?
                UNION ALL SELECT 'image', id::text, kind, media_type FROM branding_image WHERE tenant_id = ?
                ORDER BY 1, 2
                """, salon.id(), salon.id(), salon.id());
    }

    private void everyChangeRefused(TestSalon salon, String token, int status, String code) throws Exception {
        refused(call(salon, "PUT", DRAFT, save(0, brand("No", null, "#111111", null, null)), token), status, code);
        refused(call(salon, "POST", RESET, Map.of("version", 0), token), status, code);
        refused(call(salon, "POST", PUBLICATION, Map.of("version", 0), token), status, code);
        refused(call(salon, "DELETE", DRAFT, Map.of("version", 0), token), status, code);
        Http.BinaryExchange upload = upload(salon, token);
        assertThat(upload.status()).isEqualTo(status);
        assertThat(upload.asText().body()).contains("\"code\":\"" + code + "\"");
    }

    // --- AC-1 -----------------------------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(
            value = StaffRole.class,
            names = {"RECEPTIONIST", "PROFESSIONAL", "ACCOUNTANT"})
    void rolesWithoutSettingsAreRefusedEveryEndpointAndChangeNothing(StaffRole role) throws Exception {
        String token = settings.signedIn(alpha, role).token();
        List<Map<String, Object>> before = brandRows(alpha);

        refused(call(alpha, "GET", DRAFT, null, token), 403, "permission_denied");
        everyChangeRefused(alpha, token, 403, "permission_denied");

        assertThat(brandRows(alpha)).isEqualTo(before);
    }

    @ParameterizedTest
    @EnumSource(
            value = StaffRole.class,
            names = {"OWNER", "MANAGER"})
    void theOwnerAndTheManagerReadTheDraft(StaffRole role) {
        assertThat(call(
                                alpha,
                                "GET",
                                DRAFT,
                                null,
                                settings.signedIn(alpha, role).token())
                        .status())
                .isEqualTo(200);
    }

    // --- AC-7, AC-8 ------------------------------------------------------------------------------------------

    @Test
    void aSettingsUserActsOnlyOnTheHostnamesSalon() throws Exception {
        String alphasOwner = settings.signedIn(alpha, StaffRole.OWNER).token();
        String betasManager = settings.signedIn(beta, StaffRole.MANAGER).token();
        String alphasLogo = uploaded(alpha, alphasOwner);
        call(alpha, "PUT", DRAFT, save(0, brand("Brand Alpha", null, "#D6086A", alphasLogo, null)), alphasOwner);
        call(alpha, "POST", PUBLICATION, Map.of("version", 1), alphasOwner);
        List<Map<String, Object>> alphaBefore = brandRows(alpha);

        // Beta's session on alpha's hostname is a session of another salon.
        refused(call(alpha, "GET", DRAFT, null, betasManager), 404, "not_found");
        everyChangeRefused(alpha, betasManager, 404, "not_found");
        // Alpha's image named in beta's draft is the same error as an id that names nothing.
        Http.Exchange foreign =
                call(beta, "PUT", DRAFT, save(0, brand("Brand Beta", null, "#1F2937", alphasLogo, null)), betasManager);
        Http.Exchange unknown = call(
                beta,
                "PUT",
                DRAFT,
                save(
                        0,
                        brand(
                                "Brand Beta",
                                null,
                                "#1F2937",
                                java.util.UUID.randomUUID().toString(),
                                null)),
                betasManager);
        refused(foreign, 400, "bad_request");
        assertThat(settings.body(foreign).get("errors"))
                .isEqualTo(settings.body(unknown).get("errors"));
        // Alpha's published logo is served on alpha's hostname only.
        Http.BinaryExchange onAlpha = settings.image(alpha, alphasLogo, null);
        Http.BinaryExchange onBeta = settings.image(beta, alphasLogo, betasManager);
        Http.BinaryExchange missing = settings.image(beta, java.util.UUID.randomUUID(), null);
        contract.assertMatches("GET", IMAGE, onBeta);
        assertThat(onAlpha.status()).isEqualTo(200);
        assertThat(onBeta.status()).isEqualTo(404);
        assertThat(onBeta.asText()
                        .body()
                        .replaceAll("\"traceId\":\"[0-9a-f]+\"", "")
                        .replace(alphasLogo, "ID"))
                .isEqualTo(missing.asText()
                        .body()
                        .replaceAll("\"traceId\":\"[0-9a-f]+\"", "")
                        .replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "ID"));
        assertThat(onBeta.headers())
                .containsEntry("x-content-type-options", "nosniff")
                .containsEntry("cache-control", "private, no-cache, no-store, max-age=0, must-revalidate");

        assertThat(brandRows(alpha)).isEqualTo(alphaBefore);
    }

    // --- AC-9 ------------------------------------------------------------------------------------------------

    @Test
    void aSupportSessionReadsTheDraftAndADraftImageAndIsRefusedEveryChangeAndUpload() throws Exception {
        String ownersToken = settings.signedIn(alpha, StaffRole.OWNER).token();
        String draftLogo = uploaded(alpha, ownersToken);
        call(alpha, "PUT", DRAFT, save(0, brand("Brand Alpha", null, "#D6086A", draftLogo, null)), ownersToken);
        String support = settings.supportSession(alpha);
        List<Map<String, Object>> before = brandRows(alpha);

        assertThat(call(alpha, "GET", DRAFT, null, support).status()).isEqualTo(200);
        assertThat(settings.image(alpha, draftLogo, support).status()).isEqualTo(200);
        everyChangeRefused(alpha, support, 403, "support_read_only");

        assertThat(brandRows(alpha)).isEqualTo(before);
    }

    @Test
    void aSuspendedSalonAnswersSalonSuspendedOnEveryEndpoint() throws Exception {
        String token = settings.signedIn(alpha, StaffRole.OWNER).token();
        salons.suspend(alpha);
        try {
            refused(call(alpha, "GET", DRAFT, null, token), 403, "salon_suspended");
            everyChangeRefused(alpha, token, 403, "salon_suspended");
            Http.BinaryExchange image = settings.image(alpha, java.util.UUID.randomUUID(), null);
            contract.assertMatches("GET", IMAGE, image);
            assertThat(image.status()).isEqualTo(403);
        } finally {
            salons.activate(alpha);
        }
    }

    @Test
    void aReadOnlySalonReadsTheDraftAndRefusesEveryChangeAndUploadWithSalonReadOnly() throws Exception {
        String token = settings.signedIn(alpha, StaffRole.MANAGER).token();
        owner.update("UPDATE tenant SET access_state = 'read_only' WHERE id = ?", alpha.id());
        List<Map<String, Object>> before = brandRows(alpha);

        assertThat(call(alpha, "GET", DRAFT, null, token).status()).isEqualTo(200);
        everyChangeRefused(alpha, token, 403, "salon_read_only");

        assertThat(brandRows(alpha)).isEqualTo(before);
    }
}
