package com.salonplatform.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.salonplatform.branding.domain.BrandImagesTest;
import com.salonplatform.identity.domain.StaffRole;
import com.salonplatform.testing.TrackedUpload;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * A team photo upload checks the caller's permission and the member first, then the file's declared size, and only then
 * reads its bytes (F06-S01 AC-9, as F05-S01's AC-20). The file is one that counts its reads, so it goes through the
 * whole request path in-process, filters included; every other photo test uses real HTTP.
 */
@AutoConfigureMockMvc
class TeamPhotoUploadOrderIntegrationTest extends TwoSalonTeam {

    private static final int FIVE_MEGABYTES = 5 * 1024 * 1024;

    @Autowired
    MockMvc mvc;

    private MvcResult sent(Object id, TrackedUpload file, String token) throws Exception {
        var request = multipart(HttpMethod.PUT, TeamFixture.MEMBERS + "/" + id + "/photo")
                .file(file)
                .header(HttpHeaders.HOST, alpha.host(port()));
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return mvc.perform(request).andReturn();
    }

    private void refusedUnread(MvcResult result, TrackedUpload file, int status, String code) throws Exception {
        assertThat(result.getResponse().getStatus())
                .withFailMessage("%s", result.getResponse().getContentAsString())
                .isEqualTo(status);
        Map<?, ?> problem = json.readValue(result.getResponse().getContentAsString(), Map.class);
        assertThat(problem.get("code")).isEqualTo(code);
        assertThat(file.reads()).as("reads of the refused file").isZero();
    }

    private static TrackedUpload good() throws Exception {
        return new TrackedUpload(BrandImagesTest.image(400, 400, "png"));
    }

    @Test
    void noSessionIsRefusedWithoutReadingTheFile() throws Exception {
        TrackedUpload file = good();
        refusedUnread(sent(added("Order One").get("id"), file, null), file, 401, "invalid_session");
    }

    @Test
    void aRoleWithoutThePermissionIsRefusedWithoutReadingTheFile() throws Exception {
        TrackedUpload file = good();
        String receptionist = settings.signedIn(alpha, StaffRole.RECEPTIONIST).token();
        refusedUnread(sent(added("Order Two").get("id"), file, receptionist), file, 403, "permission_denied");
    }

    @Test
    void anAccountantIsRefusedWithoutReadingTheFile() throws Exception {
        TrackedUpload file = good();
        String accountant = settings.signedIn(alpha, StaffRole.ACCOUNTANT).token();
        refusedUnread(sent(added("Order Accountant").get("id"), file, accountant), file, 403, "permission_denied");
    }

    @Test
    void anUnknownMemberIsNotFoundWithoutReadingTheFile() throws Exception {
        TrackedUpload file = good();
        refusedUnread(sent(UUID.randomUUID(), file, salonOwner), file, 404, "not_found");
    }

    @Test
    void aFileDeclaredOverTheLimitIsRefusedWithoutReadingIt() throws Exception {
        TrackedUpload file = new TrackedUpload(new byte[FIVE_MEGABYTES + 1]);
        refusedUnread(sent(added("Order Three").get("id"), file, salonOwner), file, 400, "bad_request");
    }

    @Test
    void anAcceptableFileIsReadOnce() throws Exception {
        TrackedUpload file = good();

        MvcResult result = sent(added("Order Four").get("id"), file, salonOwner);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(file.reads()).isEqualTo(1);
    }
}
