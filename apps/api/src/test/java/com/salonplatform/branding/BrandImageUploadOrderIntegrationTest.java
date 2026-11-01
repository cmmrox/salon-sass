package com.salonplatform.branding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.salonplatform.branding.domain.BrandImagesTest;
import com.salonplatform.identity.adapter.IdentityCrypto;
import com.salonplatform.identity.domain.StaffRole;
import com.salonplatform.platform.OperatorTestSupport;
import com.salonplatform.platform.adapter.OperatorCrypto;
import com.salonplatform.platform.service.OperatorAccountService;
import com.salonplatform.testing.IntegrationTest;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import com.salonplatform.testing.TestSalons;
import com.salonplatform.testing.TestSalons.TestSalon;
import com.salonplatform.testing.TrackedUpload;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * A brand image upload checks the caller's permission and the image's kind first, then the file's declared size, and
 * only then reads its bytes (F05-S01 AC-20, BRD-01). The file is one that counts its reads, so it goes through the
 * whole request path in-process, filters included; every other brand image test uses real HTTP.
 */
@AutoConfigureMockMvc
class BrandImageUploadOrderIntegrationTest extends IntegrationTest {

    private static final int FIVE_MEGABYTES = 5 * 1024 * 1024;

    @Autowired
    MockMvc mvc;

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
    private SettingsTestSupport settings;
    private TestSalon alpha;
    private String manager;

    @BeforeEach
    void aSalonAndItsManager() {
        TestSalons salons = new TestSalons(owner);
        OperatorTestSupport console =
                new OperatorTestSupport(http(), port(), operators, operatorCrypto, json, environment, clock);
        settings = new SettingsTestSupport(
                http(), port(), salons, identityCrypto, json, transactions, entities, clock, console);
        alpha = salons.create("Brand Order", "#D6086A");
        manager = settings.signedIn(alpha, StaffRole.MANAGER).token();
    }

    private MvcResult sent(String kind, TrackedUpload file, String token) throws Exception {
        var request = multipart("/api/v1/salon/brand/images")
                .file(file)
                .param("kind", kind)
                .header(HttpHeaders.HOST, alpha.host(port()));
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return mvc.perform(request).andReturn();
    }

    private Map<?, ?> problem(MvcResult result) throws Exception {
        return json.readValue(result.getResponse().getContentAsString(), Map.class);
    }

    private void refusedUnread(MvcResult result, TrackedUpload file, int status, String code) throws Exception {
        assertThat(result.getResponse().getStatus())
                .withFailMessage("%s", result.getResponse().getContentAsString())
                .isEqualTo(status);
        assertThat(problem(result).get("code")).isEqualTo(code);
        assertThat(file.reads()).as("reads of the refused file").isZero();
    }

    private static TrackedUpload good() throws Exception {
        return new TrackedUpload(BrandImagesTest.image(300, 300, "png"));
    }

    @Test
    void noSessionIsRefusedWithoutReadingTheFile() throws Exception {
        TrackedUpload file = good();
        refusedUnread(sent("logo", file, null), file, 401, "invalid_session");
    }

    @Test
    void aRoleWithoutThePermissionIsRefusedWithoutReadingTheFile() throws Exception {
        TrackedUpload file = good();
        String receptionist = settings.signedIn(alpha, StaffRole.RECEPTIONIST).token();
        refusedUnread(sent("logo", file, receptionist), file, 403, "permission_denied");
    }

    @Test
    void anUnknownKindIsRefusedWithoutReadingTheFile() throws Exception {
        TrackedUpload file = good();
        MvcResult result = sent("banner", file, manager);

        refusedUnread(result, file, 400, "bad_request");
        assertThat(problem(result).get("errors").toString()).contains("kind");
    }

    @Test
    void aFileDeclaredOverTheLimitIsRefusedWithTheSameAnswerWithoutBeingRead() throws Exception {
        TrackedUpload file = new TrackedUpload(new byte[FIVE_MEGABYTES + 1]);
        MvcResult result = sent("logo", file, manager);

        refusedUnread(result, file, 400, "bad_request");
        assertThat(problem(result).get("errors"))
                .isEqualTo(List.of(Map.of("field", "file", "detail", "must be at most 5 MB")));
    }

    @Test
    void aFileAtTheLimitIsReadAndChecked() throws Exception {
        TrackedUpload file = new TrackedUpload(new byte[FIVE_MEGABYTES]);
        MvcResult result = sent("logo", file, manager);

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(problem(result).get("errors"))
                .isEqualTo(List.of(Map.of("field", "file", "detail", "must be a PNG, JPEG or WebP image")));
        assertThat(file.reads()).isEqualTo(1);
    }

    @Test
    void aGoodFileIsReadOnceAndKept() throws Exception {
        TrackedUpload file = good();
        MvcResult result = sent("logo", file, manager);

        assertThat(result.getResponse().getStatus())
                .withFailMessage("%s", result.getResponse().getContentAsString())
                .isEqualTo(201);
        assertThat(file.reads()).isEqualTo(1);
    }
}
