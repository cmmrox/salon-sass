package com.salonplatform.staff;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.branding.domain.BrandImagesTest;
import com.salonplatform.identity.domain.StaffRole;
import com.salonplatform.testing.Http;
import com.salonplatform.testing.OpenApiContract;
import com.salonplatform.testing.TestSalons.TestSalon;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** A team member's photo (F06-S01 AC-9, under F05-S01's AC-10), held to the committed contract. */
class TeamPhotosIntegrationTest extends TwoSalonTeam {

    private final OpenApiContract contract = OpenApiContract.committed();

    private Http.BinaryExchange upload(TestSalon salon, Object memberId, byte[] content, String token) {
        String boundary = "----salon" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        if (content != null) {
            body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"p.jpg\""
                            + "\r\nContent-Type: image/jpeg\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            body.writeBytes(content);
            body.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        body.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "multipart/form-data; boundary=" + boundary);
        headers.put("Accept", "application/json");
        if (token != null) {
            headers.put("Authorization", "Bearer " + token);
        }
        String path = TeamFixture.MEMBERS + "/" + memberId + "/photo";
        Http.BinaryExchange exchange =
                http().sendBytesOnHost("PUT", salon.host(port()), path, headers, body.toByteArray());
        contract.assertMatches("PUT", TeamFixture.MEMBERS + "/{id}/photo", exchange);
        return exchange;
    }

    private Http.BinaryExchange photo(TestSalon salon, Object urlOrId) {
        String path = urlOrId.toString().startsWith("/") ? urlOrId.toString() : TeamFixture.PHOTOS + urlOrId;
        Map<String, String> headers = Map.of("Accept", "image/jpeg, application/problem+json");
        Http.BinaryExchange exchange = http().sendBytesOnHost("GET", salon.host(port()), path, headers, null);
        contract.assertMatches("GET", TeamFixture.PHOTOS + "{id}", exchange);
        return exchange;
    }

    // JSON is read as an untyped map; the cast states the shape the assertions check.
    @SuppressWarnings("unchecked")
    private Map<String, Object> uploaded(Object memberId, byte[] content) {
        Http.BinaryExchange exchange = upload(alpha, memberId, content, salonOwner);
        assertThat(exchange.status())
                .withFailMessage("%s", new String(exchange.body()))
                .isEqualTo(200);
        return json.readValue(exchange.body(), Map.class);
    }

    // JSON is read as an untyped map; the casts state the shape the assertions check.
    @SuppressWarnings("unchecked")
    private void refusedFile(Object memberId, byte[] content, String reason) {
        Http.BinaryExchange exchange = upload(alpha, memberId, content, salonOwner);
        assertThat(exchange.status()).isEqualTo(400);
        Map<String, Object> problem = JsonMapper.builder().build().readValue(exchange.body(), Map.class);
        assertThat(problem).containsEntry("code", "bad_request");
        List<Map<String, Object>> errors = (List<Map<String, Object>>) problem.get("errors");
        assertThat(errors.getFirst()).containsEntry("field", "file");
        assertThat((String) errors.getFirst().get("detail")).contains(reason);
    }

    @Test
    void aPhotoIsCroppedToItsCentreSquareKeptAsAJpegOfAtMost800AndServedWithoutCaching() throws Exception {
        Map<String, Object> member = added("Photographed");

        Map<String, Object> withPhoto = uploaded(member.get("id"), BrandImagesTest.image(1600, 1000, "png"));

        assertThat((String) withPhoto.get("photoUrl")).isEqualTo(TeamFixture.PHOTOS + withPhoto.get("photoId"));
        Http.BinaryExchange served = photo(alpha, withPhoto.get("photoUrl"));
        assertThat(served.status()).isEqualTo(200);
        assertThat(served.contentType()).isEqualTo("image/jpeg");
        assertThat(served.headers())
                .containsEntry("x-content-type-options", "nosniff")
                .containsEntry("cache-control", "private, no-cache, no-store, max-age=0, must-revalidate");
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(served.body()));
        assertThat(image.getWidth()).isEqualTo(800);
        assertThat(image.getHeight()).isEqualTo(800);
    }

    @Test
    void metadataIsNotKept() throws Exception {
        Map<String, Object> member = added("No Metadata");
        byte[] sent = BrandImagesTest.jpegWithMetadata(800, 600);

        Map<String, Object> withPhoto = uploaded(member.get("id"), sent);

        byte[] kept = photo(alpha, withPhoto.get("photoUrl")).body();
        assertThat(new String(kept, StandardCharsets.ISO_8859_1)).doesNotContain("Exif");
    }

    @Test
    void aFileThatBreaksARuleIsRefusedWithTheReasonAndThePreviousPhotoStays() throws Exception {
        Map<String, Object> member = added("Keeps Photo");
        Map<String, Object> withPhoto = uploaded(member.get("id"), BrandImagesTest.image(400, 400, "png"));

        refusedFile(member.get("id"), BrandImagesTest.image(319, 600, "png"), "320");
        refusedFile(member.get("id"), "not an image".getBytes(StandardCharsets.UTF_8), "PNG");
        byte[] large = new byte[5 * 1024 * 1024 + 1];
        System.arraycopy(BrandImagesTest.image(400, 400, "png"), 0, large, 0, 16);
        refusedFile(member.get("id"), large, "5");

        assertThat(read(member.get("id"))).containsEntry("photoId", withPhoto.get("photoId"));
        assertThat(photo(alpha, withPhoto.get("photoUrl")).status()).isEqualTo(200);
    }

    @Test
    void noFileIsRefusedNamingTheField() {
        Map<String, Object> member = added("No File");

        Http.BinaryExchange exchange = upload(alpha, member.get("id"), null, salonOwner);

        assertThat(exchange.status()).isEqualTo(400);
        assertThat(new String(exchange.body(), StandardCharsets.UTF_8)).contains("\"file\"");
    }

    @Test
    void aReplacedOrRemovedPhotoAnswersNotFound() throws Exception {
        Map<String, Object> member = added("Replaced");
        Object first = uploaded(member.get("id"), BrandImagesTest.image(400, 400, "png"))
                .get("photoUrl");

        Map<String, Object> second = uploaded(member.get("id"), BrandImagesTest.image(500, 500, "jpeg"));

        assertThat(photo(alpha, first).status()).isEqualTo(404);
        assertThat(photo(alpha, second.get("photoUrl")).status()).isEqualTo(200);

        Http.Exchange removed =
                api.call(alpha, "DELETE", TeamFixture.MEMBERS + "/" + member.get("id") + "/photo", null, salonOwner);
        status(removed, 200);
        assertThat(api.body(removed)).containsEntry("photoId", null).containsEntry("photoUrl", null);
        assertThat(photo(alpha, second.get("photoUrl")).status()).isEqualTo(404);

        refused(
                api.call(alpha, "DELETE", TeamFixture.MEMBERS + "/" + member.get("id") + "/photo", null, salonOwner),
                409,
                "invalid_state");
    }

    @Test
    void aRemovedMembersPhotoIsGone() throws Exception {
        Map<String, Object> member = added("Removed With Photo");
        Map<String, Object> withPhoto = uploaded(member.get("id"), BrandImagesTest.image(400, 400, "png"));

        status(
                api.call(
                        alpha,
                        "DELETE",
                        TeamFixture.MEMBERS + "/" + member.get("id") + "?version=" + withPhoto.get("version"),
                        null,
                        salonOwner),
                204);

        assertThat(photo(alpha, withPhoto.get("photoUrl")).status()).isEqualTo(404);
    }

    @Test
    void aPhotoIsServedOnItsOwnSalonsHostnameOnlyAndOthersCannotChangeIt() throws Exception {
        Map<String, Object> member = added("Alpha Photo");
        Map<String, Object> withPhoto = uploaded(member.get("id"), BrandImagesTest.image(400, 400, "png"));
        String betaOwner = settings.signedIn(beta, StaffRole.OWNER).token();

        assertThat(photo(alpha, withPhoto.get("photoUrl")).status()).isEqualTo(200);
        assertThat(photo(beta, withPhoto.get("photoUrl")).status()).isEqualTo(404);
        assertThat(photo(alpha, "not-a-uuid").status()).isEqualTo(404);
        assertThat(upload(beta, member.get("id"), BrandImagesTest.image(400, 400, "png"), betaOwner)
                        .status())
                .isEqualTo(404);
    }

    @Test
    void aRoleWithoutManageIsRefusedBeforeAnythingElse() throws Exception {
        Map<String, Object> member = added("Protected");
        String receptionist = settings.signedIn(alpha, StaffRole.RECEPTIONIST).token();

        assertThat(upload(alpha, member.get("id"), BrandImagesTest.image(400, 400, "png"), receptionist)
                        .status())
                .isEqualTo(403);
    }
}
