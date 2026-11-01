package com.salonplatform.branding;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * F04-S01 through HTTP: an Owner or Manager saves the profile as a draft, publishes it and discards it,
 * and {@code GET /api/v1/salon} serves only what was published (AC-3 to AC-5); every field rule is a 400
 * field error that saves nothing (AC-6); a stale version is a conflict and two publications of one version
 * yield one (AC-7); every change is in the audit trail with field names and never values (AC-10). Every
 * response is held to the committed OpenAPI document.
 */
class SalonProfileIntegrationTest extends IntegrationTest {

    static final String DRAFT = "/api/v1/salon/profile/draft";
    static final String PUBLICATION = "/api/v1/salon/profile/publication";
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
    private UUID branch;

    @BeforeEach
    void salonWithItsDefaultBranch() {
        TestSalons salons = new TestSalons(owner);
        OperatorTestSupport console =
                new OperatorTestSupport(http(), port(), operators, operatorCrypto, json, environment, clock);
        settings = new SettingsTestSupport(
                http(), port(), salons, identityCrypto, json, transactions, entities, clock, console);
        salon = salons.create("Profile Studio", "#D6086A");
        branch = salons.defaultBranch(salon);
    }

    // --- fixtures ----------------------------------------------------------------------------------------

    static Map<String, Object> profile(UUID branch, Map<String, Object> contact, String description) {
        Map<String, Object> links = new LinkedHashMap<>();
        links.put("instagram", "https://instagram.com/profilestudio");
        links.put("facebook", null);
        links.put("website", "");
        Map<String, Object> branchEntry = new LinkedHashMap<>();
        branchEntry.put("id", branch.toString());
        branchEntry.putAll(contact);
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("description", description);
        profile.put("links", links);
        profile.put("branches", List.of(branchEntry));
        return profile;
    }

    static Map<String, Object> contact() {
        Map<String, Object> contact = new LinkedHashMap<>();
        contact.put("address", "  No. 24, Flower Road, Colombo 07  ");
        contact.put("location", Map.of("latitude", 6.9061, "longitude", 79.8611));
        contact.put("phone", "011 250 4417");
        contact.put("whatsapp", "077 412 3300");
        return contact;
    }

    static Map<String, Object> save(long version, Map<String, Object> profile) {
        return Map.of("version", version, "profile", profile);
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

    private long version(Map<String, Object> draft) {
        return ((Number) draft.get("version")).longValue();
    }

    // JSON maps, read untyped.
    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstBranch(Map<String, Object> profile) {
        return ((List<Map<String, Object>>) profile.get("branches")).getFirst();
    }

    // JSON maps, read untyped.
    @SuppressWarnings("unchecked")
    private static Map<String, Object> profileOf(Map<String, Object> body) {
        return (Map<String, Object>) body.get("profile");
    }

    private Map<String, Object> publicSalon() {
        Http.Exchange answer = call("GET", SALON, null, null);
        return ok(answer);
    }

    private List<Map<String, Object>> auditRows() {
        return owner.queryForList(
                "SELECT actor_kind, actor_id, action, subject_kind, subject_id, after_values::text AS after, created_at"
                        + " FROM audit_entry WHERE tenant_id = ? AND action LIKE 'branding.%' ORDER BY created_at, id",
                salon.id());
    }

    // --- AC-3 to AC-5 ---------------------------------------------------------------------------------------

    @Test
    void aNewSalonsDraftIsEmptyAndNothingIsPublished() {
        String token = settings.signedIn(salon, StaffRole.MANAGER).token();

        Map<String, Object> draft = ok(call("GET", DRAFT, null, token));

        assertThat(draft).containsEntry("version", 0).containsEntry("hasUnpublishedChanges", false);
        assertThat(draft.get("publishedAt")).isNull();
        assertThat(draft.get("publishedBy")).isNull();
        Map<String, Object> profile = profileOf(draft);
        assertThat(profile.get("description")).isNull();
        assertThat(firstBranch(profile))
                .containsEntry("id", branch.toString())
                .containsEntry("isDefault", true)
                .containsEntry("address", null)
                .containsEntry("location", null)
                .containsEntry("phone", null)
                .containsEntry("whatsapp", null);
        assertThat(publicSalon()).containsEntry("profile", null);
    }

    @Test
    void aSavedDraftIsKeptAndShownToSettingsStaffWhileThePublicSalonStillHasNoProfile() {
        String token = settings.signedIn(salon, StaffRole.OWNER).token();

        Map<String, Object> saved = ok(call("PUT", DRAFT, save(0, profile(branch, contact(), " A salon. ")), token));
        Map<String, Object> reread = ok(call("GET", DRAFT, null, token));

        assertThat(reread).isEqualTo(saved);
        assertThat(saved).containsEntry("version", 1).containsEntry("hasUnpublishedChanges", true);
        Map<String, Object> profile = profileOf(saved);
        assertThat(profile.get("description")).isEqualTo("A salon.");
        assertThat(profile.get("links"))
                .isEqualTo(
                        mapOf("instagram", "https://instagram.com/profilestudio", "facebook", null, "website", null));
        assertThat(firstBranch(profile))
                .containsEntry("address", "No. 24, Flower Road, Colombo 07")
                .containsEntry("location", Map.of("latitude", 6.9061, "longitude", 79.8611))
                .containsEntry("phone", "+94112504417")
                .containsEntry("whatsapp", "+94774123300");
        assertThat(publicSalon()).containsEntry("profile", null);
    }

    @Test
    void publishingServesExactlyTheSavedValuesInOneStepAndALaterDraftIsNotServed() {
        String token = settings.signedIn(salon, StaffRole.MANAGER).token();
        Map<String, Object> saved = ok(call("PUT", DRAFT, save(0, profile(branch, contact(), "First words")), token));

        Map<String, Object> published = ok(call("POST", PUBLICATION, Map.of("version", 1), token));

        assertThat(published).containsEntry("version", 2).containsEntry("hasUnpublishedChanges", false);
        assertThat(published.get("publishedAt")).isNotNull();
        assertThat(published.get("publishedBy")).isNotNull();
        assertThat(publicSalon().get("profile")).isEqualTo(profileOf(saved));

        ok(call("PUT", DRAFT, save(2, profile(branch, contact(), "Second words")), token));
        assertThat(profileOf(publicSalon()).get("description")).isEqualTo("First words");
        assertThat(ok(call("GET", DRAFT, null, token))).containsEntry("hasUnpublishedChanges", true);
    }

    @Test
    void publishingWithNothingChangedIsInvalidStateAndChangesNothing() {
        String token = settings.signedIn(salon, StaffRole.OWNER).token();
        refused(call("POST", PUBLICATION, Map.of("version", 0), token), 409, "invalid_state");
        assertThat(publicSalon()).containsEntry("profile", null);

        ok(call("PUT", DRAFT, save(0, profile(branch, contact(), "Words")), token));
        ok(call("POST", PUBLICATION, Map.of("version", 1), token));
        Map<String, Object> before = ok(call("GET", DRAFT, null, token));

        refused(call("POST", PUBLICATION, Map.of("version", 2), token), 409, "invalid_state");

        assertThat(ok(call("GET", DRAFT, null, token))).isEqualTo(before);
    }

    @Test
    void discardingReturnsTheDraftToThePublishedProfileAndLeavesThePublishedProfileAlone() {
        String token = settings.signedIn(salon, StaffRole.MANAGER).token();
        ok(call("PUT", DRAFT, save(0, profile(branch, contact(), "Published words")), token));
        ok(call("POST", PUBLICATION, Map.of("version", 1), token));
        Map<String, Object> publicBefore = publicSalon();
        Map<String, Object> changed = new LinkedHashMap<>(contact());
        changed.put("phone", "0771234567");
        ok(call("PUT", DRAFT, save(2, profile(branch, changed, "Draft words")), token));

        Map<String, Object> discarded = ok(call("DELETE", DRAFT, Map.of("version", 3), token));

        assertThat(discarded).containsEntry("version", 4).containsEntry("hasUnpublishedChanges", false);
        assertThat(profileOf(discarded)).isEqualTo(publicBefore.get("profile"));
        assertThat(publicSalon()).isEqualTo(publicBefore);
    }

    @Test
    void discardingBeforeTheFirstPublicationEmptiesTheDraft() {
        String token = settings.signedIn(salon, StaffRole.OWNER).token();
        ok(call("PUT", DRAFT, save(0, profile(branch, contact(), "Words")), token));

        Map<String, Object> discarded = ok(call("DELETE", DRAFT, Map.of("version", 1), token));

        assertThat(discarded).containsEntry("hasUnpublishedChanges", false);
        Map<String, Object> profile = profileOf(discarded);
        assertThat(profile.get("description")).isNull();
        assertThat(firstBranch(profile)).containsEntry("phone", null).containsEntry("location", null);
        assertThat(publicSalon()).containsEntry("profile", null);
    }

    // --- AC-6 -----------------------------------------------------------------------------------------------

    @Test
    void everyCommonFormOfASriLankanNumberIsStoredOnceInE164() {
        String token = settings.signedIn(salon, StaffRole.MANAGER).token();
        long version = 0;
        for (String typed : List.of("077 123 4567", "+94771234567", "0771234567", "+94 77 123 4567")) {
            Map<String, Object> contact = new LinkedHashMap<>(contact());
            contact.put("whatsapp", typed);
            Map<String, Object> saved = ok(call("PUT", DRAFT, save(version, profile(branch, contact, null)), token));
            assertThat(firstBranch(profileOf(saved))).as(typed).containsEntry("whatsapp", "+94771234567");
            version = version(saved);
        }
        Map<String, Object> landline = new LinkedHashMap<>(contact());
        landline.put("phone", "0112504417");
        Map<String, Object> saved = ok(call("PUT", DRAFT, save(version, profile(branch, landline, null)), token));
        assertThat(firstBranch(profileOf(saved))).containsEntry("phone", "+94112504417");
    }

    @Test
    void eachBrokenRuleIsAFieldErrorOnThatFieldAndNothingIsSaved() {
        String token = settings.signedIn(salon, StaffRole.OWNER).token();
        Map<String, Object> before = ok(call("GET", DRAFT, null, token));
        record Case(String field, String key, Object value, boolean link) {}
        List<Case> cases = List.of(
                new Case("profile.branches[0].phone", "phone", "12345", false),
                new Case("profile.branches[0].phone", "phone", "+44 20 7946 0958", false),
                new Case("profile.branches[0].whatsapp", "whatsapp", "011 250 4417", false),
                new Case(
                        "profile.branches[0].location.latitude",
                        "location",
                        Map.of("latitude", 91, "longitude", 1),
                        false),
                new Case(
                        "profile.branches[0].location.longitude",
                        "location",
                        Map.of("latitude", 1, "longitude", -180.5),
                        false),
                new Case("profile.branches[0].address", "address", "a".repeat(201), false),
                new Case("profile.links.instagram", "instagram", "http://instagram.com/x", true),
                new Case("profile.links.website", "website", "https://" + "a".repeat(190) + ".com", true),
                new Case("profile.description", "description", "d".repeat(501), false));
        for (Case each : cases) {
            Map<String, Object> contact = new LinkedHashMap<>(contact());
            String description = null;
            Map<String, Object> profile;
            if (each.key().equals("description")) {
                description = (String) each.value();
                profile = profile(branch, contact, description);
            } else if (each.link()) {
                profile = profile(branch, contact, null);
                // JSON maps, built untyped.
                @SuppressWarnings("unchecked")
                Map<String, Object> links = (Map<String, Object>) profile.get("links");
                links.put(each.key(), each.value());
            } else {
                contact.put(each.key(), each.value());
                profile = profile(branch, contact, null);
            }
            Http.Exchange answer = call("PUT", DRAFT, save(0, profile), token);

            refused(answer, 400, "bad_request");
            assertThat(settings.body(answer).get("errors"))
                    .as(each.field())
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                    .extracting("field")
                    .containsExactly(each.field());
            assertThat(answer.body()).as("no value in the error").doesNotContain(String.valueOf(each.value()));
        }
        assertThat(ok(call("GET", DRAFT, null, token))).isEqualTo(before);
        assertThat(auditRows()).isEmpty();
    }

    @Test
    void aBranchIdThatIsNotTheSalonsIsTheSameFieldErrorAsOneThatDoesNotExist() {
        TestSalon other = new TestSalons(owner).create("Other Studio", "#1F2937");
        UUID othersBranch = new TestSalons(owner).defaultBranch(other);
        String token = settings.signedIn(salon, StaffRole.MANAGER).token();

        Http.Exchange foreign = call("PUT", DRAFT, save(0, profile(othersBranch, contact(), null)), token);
        Http.Exchange unknown = call("PUT", DRAFT, save(0, profile(UUID.randomUUID(), contact(), null)), token);

        refused(foreign, 400, "bad_request");
        refused(unknown, 400, "bad_request");
        assertThat(settings.body(foreign).get("errors"))
                .isEqualTo(settings.body(unknown).get("errors"));
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM branding_branch_contact_draft WHERE tenant_id = ? AND phone IS NOT NULL",
                        Integer.class,
                        other.id()))
                .isZero();
    }

    @Test
    void anUnknownFieldOrAWrongTypeIsAFieldError() {
        String token = settings.signedIn(salon, StaffRole.MANAGER).token();
        Map<String, Object> profile = profile(branch, contact(), null);
        profile.put("tenantId", UUID.randomUUID().toString());

        refused(call("PUT", DRAFT, save(0, profile), token), 400, "bad_request");
        refused(call("POST", PUBLICATION, Map.of("version", "0"), token), 400, "bad_request");
        Map<String, Object> textCoordinates = new LinkedHashMap<>(contact());
        textCoordinates.put("location", Map.of("latitude", "6.9061", "longitude", "79.8611"));
        Http.Exchange coerced = call("PUT", DRAFT, save(0, profile(branch, textCoordinates, null)), token);
        refused(coerced, 400, "bad_request");
        assertThat(coerced.body()).contains("profile.branches[0].location.latitude");
    }

    // --- AC-7 -----------------------------------------------------------------------------------------------

    @Test
    void aSaveAPublicationOrADiscardWithAnOlderVersionIsAConflictAndChangesNothing() {
        String first = settings.signedIn(salon, StaffRole.OWNER).token();
        String second = settings.signedIn(salon, StaffRole.MANAGER).token();
        ok(call("PUT", DRAFT, save(0, profile(branch, contact(), "Mine")), first));
        Map<String, Object> current = ok(call("GET", DRAFT, null, first));

        refused(call("PUT", DRAFT, save(0, profile(branch, contact(), "Theirs")), second), 409, "conflict");
        refused(call("POST", PUBLICATION, Map.of("version", 0), second), 409, "conflict");
        refused(call("DELETE", DRAFT, Map.of("version", 0), second), 409, "conflict");

        assertThat(ok(call("GET", DRAFT, null, second))).isEqualTo(current);
        assertThat(publicSalon()).containsEntry("profile", null);
    }

    @Test
    void ofTwoPublicationsOfTheSameVersionSentAtOnceExactlyOneSucceeds() throws Exception {
        String token = settings.signedIn(salon, StaffRole.OWNER).token();
        ok(call("PUT", DRAFT, save(0, profile(branch, contact(), "Race")), token));
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
                if (exchange.status() != 200) {
                    assertThat(settings.body(exchange).get("code")).isEqualTo("conflict");
                }
            }
            assertThat(statuses).filteredOn(status -> status == 200).hasSize(1);
            assertThat(statuses).filteredOn(status -> status == 409).hasSize(attempts - 1);
        }
        assertThat(ok(call("GET", DRAFT, null, token))).containsEntry("version", 2);
        assertThat(auditRows())
                .extracting(row -> row.get("action"))
                .containsExactly("branding.profile_draft_saved", "branding.profile_published");
    }

    // --- AC-10 ----------------------------------------------------------------------------------------------

    @Test
    void savingPublishingAndDiscardingAreAuditedWithTheActorAndTheChangedFieldNamesButNoValue() {
        SettingsTestSupport.Staff manager = settings.signedIn(salon, StaffRole.MANAGER);
        String token = manager.token();
        ok(call("PUT", DRAFT, save(0, profile(branch, contact(), "Audited words")), token));
        ok(call("POST", PUBLICATION, Map.of("version", 1), token));
        Map<String, Object> changed = new LinkedHashMap<>(contact());
        changed.put("whatsapp", "0771234567");
        ok(call("PUT", DRAFT, save(2, profile(branch, changed, "Audited words")), token));
        ok(call("DELETE", DRAFT, Map.of("version", 3), token));

        List<Map<String, Object>> rows = auditRows();

        assertThat(rows)
                .extracting(row -> row.get("action"))
                .containsExactly(
                        "branding.profile_draft_saved",
                        "branding.profile_published",
                        "branding.profile_draft_saved",
                        "branding.profile_draft_discarded");
        assertThat(rows)
                .allSatisfy(row -> assertThat(row)
                        .containsEntry("actor_kind", "staff")
                        .containsEntry("actor_id", manager.account().getId().toString())
                        .containsEntry("subject_kind", "salon_profile")
                        .containsEntry("subject_id", salon.id().toString()));
        String whatsappField = "branches[" + branch + "].whatsapp";
        assertThat((String) rows.get(0).get("after"))
                .contains("description", "links.instagram", "branches[" + branch + "].phone", whatsappField);
        assertThat((String) rows.get(2).get("after")).contains(whatsappField).doesNotContain("description");
        assertThat((String) rows.get(3).get("after")).contains(whatsappField);
        for (Map<String, Object> row : rows) {
            assertThat((String) row.get("after"))
                    .doesNotContain("Audited", "Flower", "+94", "0771234567", "instagram.com", "6.9061");
        }
    }

    private static Map<String, Object> mapOf(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
