package com.salonplatform.catalogue;

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
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * A service's photo (F05-S01 AC-10): PNG, JPEG or WebP of up to 5 MB and at least 320 x 320, told by its content,
 * cropped to its centre square and kept as a JPEG of at most 800 x 800 with no metadata, served by an opaque id on
 * the salon's own hostname with no-store caching and {@code nosniff}. A file that breaks a rule is refused with the
 * reason and the service keeps its previous photo; a replaced or removed photo is no longer served. Held to the
 * committed contract.
 */
class CataloguePhotosIntegrationTest extends IntegrationTest {

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

    private Map<String, Object> created() {
        return api.created(alpha, manager, service(colour, "Root touch-up", 4500, 60, 0));
    }

    private Map<String, Object> uploaded(Object serviceId, byte[] content) {
        Http.BinaryExchange exchange = api.upload(alpha, serviceId, "photo", content, manager);
        assertThat(exchange.status())
                .withFailMessage("%s", new String(exchange.body()))
                .isEqualTo(200);
        return api.body(exchange.asText());
    }

    private static BufferedImage read(byte[] bytes) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    @Test
    void aPhotoIsCroppedToItsCentreSquareKeptAsAJpegOfAtMost800AndServedWithoutCaching() throws Exception {
        Map<String, Object> service = created();

        Map<String, Object> withPhoto = uploaded(service.get("id"), BrandImagesTest.image(1600, 1000, "png"));

        assertThat(withPhoto.get("photoUrl")).isEqualTo("/api/v1/catalogue/photos/" + withPhoto.get("photoId"));
        assertThat(withPhoto.get("version")).isEqualTo(1);
        Http.BinaryExchange served = api.photo(alpha, withPhoto.get("photoUrl"));
        assertThat(served.status()).isEqualTo(200);
        assertThat(served.contentType()).isEqualTo("image/jpeg");
        assertThat(served.headers())
                .containsEntry("x-content-type-options", "nosniff")
                .containsEntry("cache-control", "private, no-cache, no-store, max-age=0, must-revalidate");
        BufferedImage kept = read(served.body());
        assertThat(kept.getWidth()).isEqualTo(800);
        assertThat(kept.getHeight()).isEqualTo(800);
    }

    @Test
    void aSmallPhotoIsKeptAtItsOwnSquareAndWebpIsAccepted() throws Exception {
        Map<String, Object> service = created();

        Map<String, Object> small = uploaded(service.get("id"), BrandImagesTest.image(320, 500, "jpeg"));
        BufferedImage kept = read(api.photo(alpha, small.get("photoUrl")).body());
        assertThat(kept.getWidth()).isEqualTo(320);
        assertThat(kept.getHeight()).isEqualTo(320);

        Map<String, Object> webp = uploaded(service.get("id"), BrandImagesTest.resource("logo-600x400.webp"));
        BufferedImage fromWebp = read(api.photo(alpha, webp.get("photoUrl")).body());
        assertThat(fromWebp.getWidth()).isEqualTo(400);
        assertThat(fromWebp.getHeight()).isEqualTo(400);
    }

    @Test
    void metadataIsNotKept() throws Exception {
        Map<String, Object> service = created();
        byte[] uploaded = BrandImagesTest.jpegWithMetadata(800, 600);

        byte[] kept = api.photo(alpha, uploaded(service.get("id"), uploaded).get("photoUrl"))
                .body();

        assertThat(new String(kept, StandardCharsets.ISO_8859_1))
                .doesNotContain("Exif", "GPSLatitude", "comment-secret");
    }

    @Test
    void aFileThatBreaksARuleIsRefusedWithTheReasonAndThePreviousPhotoStays() throws Exception {
        Map<String, Object> service = created();
        Map<String, Object> withPhoto = uploaded(service.get("id"), BrandImagesTest.image(400, 400, "png"));
        Object photoUrl = withPhoto.get("photoUrl");
        byte[] servedBefore = api.photo(alpha, photoUrl).body();

        refusedFile(
                service.get("id"),
                "renamed.jpg",
                "not an image at all".getBytes(StandardCharsets.UTF_8),
                "must be a PNG, JPEG or WebP image");
        refusedFile(
                service.get("id"),
                "small.png",
                BrandImagesTest.image(319, 600, "png"),
                "must be at least 320 x 320 pixels");
        byte[] large = new byte[6 * 1024 * 1024];
        System.arraycopy(BrandImagesTest.image(400, 400, "png"), 0, large, 0, 16);
        refusedFile(service.get("id"), "large.png", large, "must be at most 5 MB");
        byte[] truncated = java.util.Arrays.copyOf(BrandImagesTest.image(400, 400, "png"), 60);
        refusedFile(service.get("id"), "broken.png", truncated, "could not be read as an image");
        Http.BinaryExchange missing = api.upload(alpha, service.get("id"), "none", null, manager);
        assertThat(missing.status()).isEqualTo(400);
        assertThat(new String(missing.body(), StandardCharsets.UTF_8)).contains("\"field\":\"file\"");

        Map<String, Object> after = api.body(api.call(alpha, "GET", SERVICES + "/" + service.get("id"), null, manager));
        assertThat(after.get("photoUrl")).isEqualTo(photoUrl);
        assertThat(api.photo(alpha, photoUrl).body()).isEqualTo(servedBefore);
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM catalogue_photo WHERE tenant_id = ?", Long.class, alpha.id()))
                .isEqualTo(1);
    }

    private void refusedFile(Object serviceId, String name, byte[] content, String reason) {
        Http.BinaryExchange exchange = api.upload(alpha, serviceId, name, content, manager);
        assertThat(exchange.status())
                .withFailMessage("%s", new String(exchange.body()))
                .isEqualTo(400);
        Map<String, Object> body = api.body(exchange.asText());
        assertThat(body.get("code")).isEqualTo("bad_request");
        assertThat(body.get("errors").toString()).contains("field=file", "detail=" + reason);
    }

    @Test
    void aReplacedOrRemovedPhotoIsNoLongerServed() throws Exception {
        Map<String, Object> service = created();
        Object first = uploaded(service.get("id"), BrandImagesTest.image(400, 400, "png"))
                .get("photoUrl");
        Map<String, Object> replaced = uploaded(service.get("id"), BrandImagesTest.image(500, 500, "jpeg"));

        assertThat(api.photo(alpha, first).status()).isEqualTo(404);
        assertThat(api.photo(alpha, replaced.get("photoUrl")).status()).isEqualTo(200);

        Http.Exchange removed = api.call(alpha, "DELETE", SERVICES + "/" + service.get("id") + "/photo", null, manager);
        assertThat(removed.status()).isEqualTo(200);
        assertThat(api.body(removed)).containsEntry("photoId", null).containsEntry("photoUrl", null);
        assertThat(api.photo(alpha, replaced.get("photoUrl")).status()).isEqualTo(404);
        refused(
                api.call(alpha, "DELETE", SERVICES + "/" + service.get("id") + "/photo", null, manager),
                409,
                "invalid_state");
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM catalogue_photo WHERE tenant_id = ?", Long.class, alpha.id()))
                .isZero();
        assertThat(owner.queryForList(
                        "SELECT action FROM audit_entry WHERE tenant_id = ? AND subject_id = ? AND action LIKE '%photo%'",
                        String.class, alpha.id(), service.get("id")))
                .containsExactly(
                        "catalogue.service_photo_changed",
                        "catalogue.service_photo_changed",
                        "catalogue.service_photo_removed");
    }

    @Test
    void aDuplicateHasItsOwnCopyOfThePhotoAndADeletedServicesPhotoIsGone() throws Exception {
        Map<String, Object> service = created();
        Map<String, Object> withPhoto = uploaded(service.get("id"), BrandImagesTest.image(400, 400, "png"));
        Map<String, Object> copy =
                api.body(api.call(alpha, "POST", SERVICES + "/" + service.get("id") + "/duplicate", null, manager));

        assertThat(copy.get("photoId")).isNotNull().isNotEqualTo(withPhoto.get("photoId"));
        assertThat(api.photo(alpha, copy.get("photoUrl")).body())
                .isEqualTo(api.photo(alpha, withPhoto.get("photoUrl")).body());

        assertThat(api.call(alpha, "DELETE", SERVICES + "/" + service.get("id") + "?version=1", null, manager)
                        .status())
                .isEqualTo(204);
        assertThat(api.photo(alpha, withPhoto.get("photoUrl")).status()).isEqualTo(404);
        assertThat(api.photo(alpha, copy.get("photoUrl")).status()).isEqualTo(200);
    }

    @Test
    void aPhotoIsServedToAnyoneOnItsOwnSalonsHostnameOnlyAndAMalformedIdIsNotFound() throws Exception {
        Map<String, Object> withPhoto = uploaded(created().get("id"), BrandImagesTest.image(400, 400, "png"));

        assertThat(api.photo(alpha, withPhoto.get("photoUrl")).status()).isEqualTo(200);
        Http.BinaryExchange elsewhere = api.photo(beta, withPhoto.get("photoUrl"));
        assertThat(elsewhere.status()).isEqualTo(404);
        assertThat(elsewhere.headers()).containsEntry("x-content-type-options", "nosniff");
        assertThat(api.photo(alpha, "not-an-id").status()).isEqualTo(404);
        assertThat(api.photo(alpha, UUID.randomUUID()).status()).isEqualTo(404);
    }
}
