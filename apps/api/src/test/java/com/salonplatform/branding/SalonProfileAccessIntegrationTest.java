package com.salonplatform.branding;

import static com.salonplatform.branding.SalonProfileIntegrationTest.DRAFT;
import static com.salonplatform.branding.SalonProfileIntegrationTest.PUBLICATION;
import static com.salonplatform.branding.SalonProfileIntegrationTest.SALON;
import static com.salonplatform.branding.SalonProfileIntegrationTest.contact;
import static com.salonplatform.branding.SalonProfileIntegrationTest.profile;
import static com.salonplatform.branding.SalonProfileIntegrationTest.save;
import static org.assertj.core.api.Assertions.assertThat;

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
 * Who reaches the profile endpoints (F04-S01): the Receptionist, Professional and Accountant are refused
 * every one with {@code permission_denied} and change nothing (AC-2); a salon's settings user acts only on
 * the hostname's salon, whatever it sends, and the public profile is the hostname's (AC-8); a support
 * session reads the draft and is refused every change, and a suspended or read-only salon answers like its
 * other endpoints (AC-9). Every response is held to the committed OpenAPI document.
 */
class SalonProfileAccessIntegrationTest extends IntegrationTest {

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
    private TestSalons salons;
    private SettingsTestSupport settings;
    private TestSalon alpha;
    private TestSalon beta;

    @BeforeEach
    void twoSalons() {
        salons = new TestSalons(owner);
        OperatorTestSupport console =
                new OperatorTestSupport(http(), port(), operators, operatorCrypto, json, environment, clock);
        settings = new SettingsTestSupport(
                http(), port(), salons, identityCrypto, json, transactions, entities, clock, console);
        alpha = salons.create("Access Alpha", "#D6086A");
        beta = salons.create("Access Beta", "#1F2937");
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

    /** Every row of the salon's profile and branch tables, as the owner sees them. */
    private List<Map<String, Object>> profileRows(TestSalon salon) {
        return owner.queryForList("""
                SELECT 'draft' AS kind, version::text AS a, description AS b, instagram_url AS c FROM branding_profile_draft
                 WHERE tenant_id = ?
                UNION ALL SELECT 'published', published_at::text, description, instagram_url
                  FROM branding_profile_published WHERE tenant_id = ?
                UNION ALL SELECT 'contact', branch_id::text, address, phone FROM branding_branch_contact_draft
                 WHERE tenant_id = ?
                UNION ALL SELECT 'public contact', branch_id::text, address, phone FROM branding_branch_contact_published
                 WHERE tenant_id = ?
                ORDER BY 1, 2
                """, salon.id(), salon.id(), salon.id(), salon.id());
    }

    private void everyWriteRefused(TestSalon salon, String token, int status, String code) {
        UUID branch = salons.defaultBranch(salon);
        refused(call(salon, "PUT", DRAFT, save(0, profile(branch, contact(), "No")), token), status, code);
        refused(call(salon, "POST", PUBLICATION, Map.of("version", 0), token), status, code);
        refused(call(salon, "DELETE", DRAFT, Map.of("version", 0), token), status, code);
    }

    /** Alpha's settings user saves and publishes a profile, and leaves a newer draft. */
    private void alphaPublishes(String token) {
        UUID branch = salons.defaultBranch(alpha);
        assertThat(call(alpha, "PUT", DRAFT, save(0, profile(branch, contact(), "Alpha words")), token)
                        .status())
                .isEqualTo(200);
        assertThat(call(alpha, "POST", PUBLICATION, Map.of("version", 1), token).status())
                .isEqualTo(200);
        assertThat(call(alpha, "PUT", DRAFT, save(2, profile(branch, contact(), "Alpha draft")), token)
                        .status())
                .isEqualTo(200);
    }

    // --- AC-2 -----------------------------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(
            value = StaffRole.class,
            names = {"RECEPTIONIST", "PROFESSIONAL", "ACCOUNTANT"})
    void rolesWithoutSettingsAreRefusedEveryEndpointAndChangeNothing(StaffRole role) {
        String token = settings.signedIn(alpha, role).token();
        List<Map<String, Object>> before = profileRows(alpha);

        refused(call(alpha, "GET", DRAFT, null, token), 403, "permission_denied");
        everyWriteRefused(alpha, token, 403, "permission_denied");

        assertThat(profileRows(alpha)).isEqualTo(before);
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM audit_entry WHERE tenant_id = ? AND action LIKE 'branding.%'",
                        Integer.class, alpha.id()))
                .isZero();
    }

    @ParameterizedTest
    @EnumSource(
            value = StaffRole.class,
            names = {"OWNER", "MANAGER"})
    void theOwnerAndTheManagerReadTheDraft(StaffRole role) {
        String token = settings.signedIn(alpha, role).token();

        assertThat(call(alpha, "GET", DRAFT, null, token).status()).isEqualTo(200);
    }

    @Test
    void withoutASessionEveryEndpointIsInvalidSession() {
        refused(call(alpha, "GET", DRAFT, null, null), 401, "invalid_session");
        everyWriteRefused(alpha, null, 401, "invalid_session");
    }

    // --- AC-8 -----------------------------------------------------------------------------------------------

    @Test
    void aSettingsUserActsOnlyOnTheHostnamesSalonWhateverItSends() {
        String alphasOwner = settings.signedIn(alpha, StaffRole.OWNER).token();
        alphaPublishes(alphasOwner);
        String betasManager = settings.signedIn(beta, StaffRole.MANAGER).token();
        List<Map<String, Object>> alphaBefore = profileRows(alpha);

        // Beta's own token on alpha's hostname is a session of another salon.
        refused(call(alpha, "GET", DRAFT, null, betasManager), 404, "not_found");
        everyWriteRefused(alpha, betasManager, 404, "not_found");
        // Alpha's branch named in a save on beta's hostname is not beta's branch.
        Http.Exchange aimed =
                call(beta, "PUT", DRAFT, save(0, profile(salons.defaultBranch(alpha), contact(), "x")), betasManager);
        refused(aimed, 400, "bad_request");
        // A salon named in the body or a header is not a salon at all.
        Map<String, Object> withSalon =
                new java.util.LinkedHashMap<>(save(0, profile(salons.defaultBranch(beta), contact(), "Beta words")));
        withSalon.put("tenantId", alpha.id().toString());
        refused(call(beta, "PUT", DRAFT, withSalon, betasManager), 400, "bad_request");
        Http.Exchange viaHeader = http().sendOnHost(
                        "GET",
                        beta.host(port()),
                        DRAFT,
                        Map.of(
                                "Authorization",
                                "Bearer " + betasManager,
                                "X-Tenant-Id",
                                alpha.id().toString()),
                        null);
        assertThat(viaHeader.status()).isEqualTo(200);
        assertThat(settings.body(viaHeader).get("version")).isEqualTo(0);

        assertThat(profileRows(alpha)).isEqualTo(alphaBefore);
    }

    // JSON maps, read untyped.
    @SuppressWarnings("unchecked")
    @Test
    void thePublicSalonServesOnlyTheHostnamesPublishedProfile() {
        alphaPublishes(settings.signedIn(alpha, StaffRole.OWNER).token());

        Map<String, Object> alphaSalon = settings.body(call(alpha, "GET", SALON, null, null));
        Map<String, Object> betaSalon = settings.body(call(beta, "GET", SALON, null, null));

        Map<String, Object> alphaProfile = (Map<String, Object>) alphaSalon.get("profile");
        assertThat(alphaProfile.get("description")).isEqualTo("Alpha words");
        assertThat(((List<Map<String, Object>>) alphaProfile.get("branches"))
                        .getFirst()
                        .get("id"))
                .isEqualTo(salons.defaultBranch(alpha).toString());
        assertThat(betaSalon).containsEntry("profile", null);
        assertThat(betaSalon.toString()).doesNotContain("Alpha");
    }

    // --- AC-9 -----------------------------------------------------------------------------------------------

    @Test
    void aSupportSessionReadsTheDraftAndIsRefusedEveryChange() {
        alphaPublishes(settings.signedIn(alpha, StaffRole.OWNER).token());
        String support = settings.supportSession(alpha);
        List<Map<String, Object>> before = profileRows(alpha);

        Http.Exchange read = call(alpha, "GET", DRAFT, null, support);
        assertThat(read.status()).withFailMessage("%s", read.body()).isEqualTo(200);
        assertThat(settings.body(read).get("hasUnpublishedChanges")).isEqualTo(true);
        UUID branch = salons.defaultBranch(alpha);
        refused(
                call(alpha, "PUT", DRAFT, save(3, profile(branch, contact(), "No")), support),
                403,
                "support_read_only");
        refused(call(alpha, "POST", PUBLICATION, Map.of("version", 3), support), 403, "support_read_only");
        refused(call(alpha, "DELETE", DRAFT, Map.of("version", 3), support), 403, "support_read_only");

        assertThat(profileRows(alpha)).isEqualTo(before);
    }

    @Test
    void aSuspendedSalonAnswersSalonSuspendedOnEveryEndpoint() {
        String token = settings.signedIn(alpha, StaffRole.OWNER).token();
        salons.suspend(alpha);
        try {
            refused(call(alpha, "GET", DRAFT, null, token), 403, "salon_suspended");
            everyWriteRefused(alpha, token, 403, "salon_suspended");
        } finally {
            salons.activate(alpha);
        }
    }

    @Test
    void aReadOnlySalonReadsTheDraftAndRefusesEveryChangeWithSalonReadOnly() {
        String token = settings.signedIn(alpha, StaffRole.MANAGER).token();
        owner.update("UPDATE tenant SET access_state = 'read_only' WHERE id = ?", alpha.id());
        List<Map<String, Object>> before = profileRows(alpha);

        assertThat(call(alpha, "GET", DRAFT, null, token).status()).isEqualTo(200);
        everyWriteRefused(alpha, token, 403, "salon_read_only");

        assertThat(profileRows(alpha)).isEqualTo(before);
    }
}
