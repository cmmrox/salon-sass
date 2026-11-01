package com.salonplatform.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.identity.adapter.IdentityCrypto;
import com.salonplatform.platform.adapter.OperatorCrypto;
import com.salonplatform.platform.service.BootstrapFaults;
import com.salonplatform.platform.service.OperatorAccountService;
import com.salonplatform.testing.Http;
import com.salonplatform.testing.IntegrationTest;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import com.salonplatform.testing.TestSalons;
import com.salonplatform.testing.TestSalons.TestSalon;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * F03-S01 plans and salon provisioning over real HTTP and PostgreSQL: immutable definitions, the
 * complete profile and first Owner, strict field errors, idempotent retries, single winners of a
 * race, rollback of every bootstrap step, permissions and the allowlisted directory (AC-3, AC-4,
 * AC-7 to AC-14, AC-16).
 */
class OperatorProvisioningIntegrationTest extends IntegrationTest {

    /** The controlled bootstrap failure, set per test. */
    static final AtomicReference<BootstrapFaults.Step> FAIL_AT = new AtomicReference<>();

    @TestConfiguration
    static class Faults {
        @Bean
        @Primary
        BootstrapFaults controlledFaults() {
            return step -> {
                if (step == FAIL_AT.get()) {
                    throw new IllegalStateException("controlled bootstrap failure");
                }
            };
        }
    }

    @Autowired
    OperatorAccountService accounts;

    @Autowired
    OperatorCrypto crypto;

    @Autowired
    IdentityCrypto identityCrypto;

    @Autowired
    JsonMapper json;

    @Autowired
    Environment environment;

    @Autowired
    Clock clock;

    private final JdbcTemplate owner = TestDatabase.of(TestPostgres.shared()).asOwner();
    private OperatorTestSupport console;
    private String admin;

    @BeforeEach
    void setUp() {
        FAIL_AT.set(null);
        console = new OperatorTestSupport(http(), port(), accounts, crypto, json, environment, clock);
        admin = console.token(console.create("OPERATOR_ADMIN"));
    }

    @AfterEach
    void clearFaults() {
        FAIL_AT.set(null);
    }

    private static void status(Http.Exchange response, int expected) {
        assertThat(response.status())
                .withFailMessage("%s %s", response.status(), response.body())
                .isEqualTo(expected);
    }

    private Http.Exchange create(String path, Object body, String token, String key) {
        return console.callOn(
                console.host(), "POST", path, body, token, key == null ? Map.of() : Map.of("Idempotency-Key", key));
    }

    private String newPlan() {
        Http.Exchange created = create(
                "/plans",
                OperatorTestSupport.plan(OperatorTestSupport.uniqueKey("p")),
                admin,
                UUID.randomUUID().toString());
        status(created, 201);
        return (String) console.body(created).get("id");
    }

    private List<String> fields(Http.Exchange response) {
        // JSON is read as an untyped map (Map.class); the cast states the shape the assertions then check.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors =
                (List<Map<String, Object>>) console.body(response).get("errors");
        return errors == null
                ? List.of()
                : errors.stream().map(e -> (String) e.get("field")).toList();
    }

    private long count(String sql, Object... args) {
        return owner.queryForObject(sql, Long.class, args);
    }

    // --- AC-7: plans -------------------------------------------------------------------------------

    @Test
    void anAdminCreatesAndReadsAnExactImmutablePlanDefinition() {
        String key = OperatorTestSupport.uniqueKey("plan");
        Map<String, Object> request = OperatorTestSupport.plan(key);
        request.put("staffSeats", 1000000);
        request.put("messageAllowance", 0);
        Http.Exchange created =
                create("/plans", request, admin, UUID.randomUUID().toString());
        status(created, 201);
        assertThat(created.headers().get("cache-control")).contains("no-store");
        Map<String, Object> plan = console.body(created);
        assertThat(plan)
                .containsEntry("key", key)
                .containsEntry("staffSeats", 1000000)
                .containsEntry("branches", 1)
                .containsEntry("messageAllowance", 0)
                .containsEntry("customDomain", false)
                .containsEntry("dedicatedApp", true)
                .containsKeys("id", "createdAt", "updatedAt");
        Http.Exchange read = console.call("GET", "/plans/" + plan.get("id"), null, admin);
        status(read, 200);
        assertThat(console.body(read)).isEqualTo(plan);
        // Immutable in the database for every role.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> owner.update(
                        "UPDATE plan SET name = 'x' WHERE id = ?", UUID.fromString((String) plan.get("id"))))
                .hasStackTraceContaining("never changed");
        // The same key again, under another idempotency key, is taken.
        Http.Exchange taken = create(
                "/plans",
                OperatorTestSupport.plan(key),
                admin,
                UUID.randomUUID().toString());
        status(taken, 409);
        assertThat(console.body(taken)).containsEntry("code", "plan_key_taken");
    }

    @Test
    void anInvalidPlanIsRefusedFieldByFieldAndCreatesNothing() {
        String key = OperatorTestSupport.uniqueKey("bad");
        List<Map.Entry<Object, String>> cases = new ArrayList<>();
        Map<String, Object> missing = OperatorTestSupport.plan(key);
        missing.remove("branches");
        cases.add(Map.entry(missing, "branches"));
        Map<String, Object> unknown = OperatorTestSupport.plan(key);
        unknown.put("unlimited", true);
        cases.add(Map.entry(unknown, "unlimited"));
        Map<String, Object> text = OperatorTestSupport.plan(key);
        text.put("staffSeats", "5");
        cases.add(Map.entry(text, "staffSeats"));
        Map<String, Object> decimal = OperatorTestSupport.plan(key);
        decimal.put("branches", 1.5);
        cases.add(Map.entry(decimal, "branches"));
        Map<String, Object> bool = OperatorTestSupport.plan(key);
        bool.put("customDomain", 1);
        cases.add(Map.entry(bool, "customDomain"));
        Map<String, Object> negative = OperatorTestSupport.plan(key);
        negative.put("messageAllowance", -1);
        cases.add(Map.entry(negative, "messageAllowance"));
        Map<String, Object> above = OperatorTestSupport.plan(key);
        above.put("staffSeats", 1000001);
        cases.add(Map.entry(above, "staffSeats"));
        Map<String, Object> zero = OperatorTestSupport.plan(key);
        zero.put("branches", 0);
        cases.add(Map.entry(zero, "branches"));
        Map<String, Object> nullFlag = OperatorTestSupport.plan(key);
        nullFlag.put("dedicatedApp", null);
        cases.add(Map.entry(nullFlag, "dedicatedApp"));
        Map<String, Object> badKey = OperatorTestSupport.plan("-x");
        cases.add(Map.entry(badKey, "key"));
        Map<String, Object> longName = OperatorTestSupport.plan(key);
        longName.put("name", "n".repeat(121));
        cases.add(Map.entry(longName, "name"));
        for (Map.Entry<Object, String> each : cases) {
            Http.Exchange refused =
                    create("/plans", each.getKey(), admin, UUID.randomUUID().toString());
            status(refused, 400);
            assertThat(console.body(refused)).containsEntry("code", "bad_request");
            assertThat(fields(refused)).as("%s", each.getKey()).contains(each.getValue());
        }
        assertThat(count("SELECT count(*) FROM plan WHERE key = ?", key)).isZero();
        // A missing or malformed Idempotency-Key names the header.
        Http.Exchange noKey = create("/plans", OperatorTestSupport.plan(key), admin, null);
        status(noKey, 400);
        assertThat(fields(noKey)).containsExactly("Idempotency-Key");
        status(create("/plans", OperatorTestSupport.plan(key), admin, "not-a-uuid"), 400);
        assertThat(count("SELECT count(*) FROM plan WHERE key = ?", key)).isZero();
    }

    @Test
    void repeatingAPlanCreationReplaysTheOriginalAndAChangedRequestConflicts() throws Exception {
        String key = OperatorTestSupport.uniqueKey("idem");
        String idempotency = UUID.randomUUID().toString();
        List<Http.Exchange> answers =
                race(5, () -> create("/plans", OperatorTestSupport.plan(key), admin, idempotency));
        assertThat(answers).allSatisfy(answer -> status(answer, 201));
        assertThat(answers.stream().map(a -> console.body(a).get("id")).distinct())
                .hasSize(1);
        String id = (String) console.body(answers.get(0)).get("id");
        assertThat(count("SELECT count(*) FROM plan WHERE key = ?", key)).isEqualTo(1);
        assertThat(count(
                        "SELECT count(*) FROM platform_audit_entry WHERE action = 'plan.created' AND subject_id = ?",
                        id))
                .isEqualTo(1);

        Map<String, Object> changed = OperatorTestSupport.plan(key);
        changed.put("branches", 2);
        Http.Exchange conflict = create("/plans", changed, admin, idempotency);
        status(conflict, 409);
        assertThat(console.body(conflict)).containsEntry("code", "idempotency_conflict");
        // Unauthenticated: no receipt is reachable.
        status(create("/plans", OperatorTestSupport.plan(key), null, idempotency), 401);
    }

    @Test
    void simultaneousDistinctKeysForOnePlanKeyCreateExactlyOne() throws Exception {
        String key = OperatorTestSupport.uniqueKey("race");
        List<Http.Exchange> answers = race(
                6,
                () -> create(
                        "/plans",
                        OperatorTestSupport.plan(key),
                        admin,
                        UUID.randomUUID().toString()));
        assertThat(answers).filteredOn(a -> a.status() == 201).hasSize(1);
        assertThat(answers)
                .filteredOn(a -> a.status() == 409)
                .allSatisfy(a -> assertThat(console.body(a)).containsEntry("code", "plan_key_taken"))
                .hasSize(5);
        assertThat(count("SELECT count(*) FROM plan WHERE key = ?", key)).isEqualTo(1);
    }

    // --- AC-8, AC-9: salons and the first Owner ----------------------------------------------------

    @Test
    void anAdminProvisionsACompleteSalonWhoseOwnerMustEnrollMfaAtItsOwnSalon() {
        String plan = newPlan();
        String slug = OperatorTestSupport.uniqueKey("s");
        String ownerEmail = "owner-" + slug + "@example.test";
        String ownerPassword = "owner synthetic " + UUID.randomUUID();
        Map<String, Object> request = OperatorTestSupport.salon(slug.toUpperCase(), plan, ownerEmail, ownerPassword);
        request.put("timeZone", "Europe/London");
        Http.Exchange created =
                create("/salons", request, admin, UUID.randomUUID().toString());
        status(created, 201);
        Map<String, Object> result = console.body(created);
        assertThat(result.keySet()).containsExactlyInAnyOrder("id", "slug", "hostname", "planId", "createdAt");
        assertThat(result)
                .containsEntry("slug", slug)
                .containsEntry("hostname", slug + ".localhost")
                .containsEntry("planId", plan);
        assertThat(created.body()).doesNotContain(ownerPassword).doesNotContainIgnoringCase("token");

        Map<String, Object> detail = console.body(console.call("GET", "/salons/" + result.get("id"), null, admin));
        assertThat(detail)
                .containsEntry("name", "Synthetic Salon " + slug.toUpperCase())
                .containsEntry("slug", slug)
                .containsEntry("status", "active")
                .containsEntry("country", "LK")
                .containsEntry("currency", "LKR")
                .containsEntry("timeZone", "Europe/London")
                .containsEntry("defaultLanguage", "si")
                .containsEntry("contactEmail", "contact-" + slug.toLowerCase() + "@example.test")
                .containsEntry("contactPhone", "+94771234567")
                .containsEntry("address", "1 Synthetic Road\nColombo 07")
                .containsEntry("ownerEmail", ownerEmail);
        // JSON is read as an untyped map (Map.class); the cast states the shape the assertions then check.
        @SuppressWarnings("unchecked")
        Map<String, Object> planSummary = (Map<String, Object>) detail.get("plan");
        assertThat(planSummary).containsEntry("id", plan).containsKeys("key", "name");
        assertThat(detail.toString()).doesNotContain(ownerPassword);

        // The hostname resolves the salon's name and time zone at once.
        Http.Exchange salon = http().getOnHost(slug + ".localhost:" + port(), "/api/v1/salon");
        status(salon, 200);
        assertThat(console.body(salon))
                .containsEntry("name", "Synthetic Salon " + slug.toUpperCase())
                .containsEntry("timeZone", "Europe/London")
                .containsEntry("brandColour", "#111827");

        // The Owner signs in only at its salon, only with the supplied password, and must enroll MFA.
        Map<String, Object> credentials =
                Map.of("identifier", ownerEmail, "password", ownerPassword, "deviceLabel", "x");
        Http.Exchange signIn = http().sendOnHost(
                        "POST",
                        slug + ".localhost:" + port(),
                        "/api/v1/identity/staff/sign-in",
                        Map.of("Content-Type", "application/json"),
                        json.writeValueAsString(credentials));
        status(signIn, 200);
        assertThat(console.body(signIn)).containsEntry("state", "enroll_mfa").doesNotContainKey("credentials");
        TestSalon other = new TestSalons(owner).create("Other", "#445566");
        status(
                http().sendOnHost(
                                "POST",
                                other.host(port()),
                                "/api/v1/identity/staff/sign-in",
                                Map.of("Content-Type", "application/json"),
                                json.writeValueAsString(credentials)),
                401);
        status(
                http().sendOnHost(
                                "POST",
                                slug + ".localhost:" + port(),
                                "/api/v1/identity/staff/sign-in",
                                Map.of("Content-Type", "application/json"),
                                json.writeValueAsString(Map.of(
                                        "identifier",
                                        ownerEmail,
                                        "password",
                                        "a different password value",
                                        "deviceLabel",
                                        "x"))),
                401);
        // JSON is read as an untyped map (Map.class); the cast states the shape the assertions then check.
        @SuppressWarnings("unchecked")
        Map<String, Object> challenge = console.body(signIn);
        Http.Exchange setup = http().sendOnHost(
                        "POST",
                        slug + ".localhost:" + port(),
                        "/api/v1/identity/staff/mfa/enroll",
                        Map.of("Content-Type", "application/json"),
                        json.writeValueAsString(Map.of(
                                "challengeId",
                                challenge.get("challengeId"),
                                "challengeToken",
                                challenge.get("challengeToken"))));
        status(setup, 200);
        String secret = (String) console.body(setup).get("secret");
        Http.Exchange verified = http().sendOnHost(
                        "POST",
                        slug + ".localhost:" + port(),
                        "/api/v1/identity/staff/mfa/verify",
                        Map.of("Content-Type", "application/json"),
                        json.writeValueAsString(Map.of(
                                "challengeId",
                                challenge.get("challengeId"),
                                "challengeToken",
                                challenge.get("challengeToken"),
                                "code",
                                identityCrypto.totpCode(secret, clock.instant().getEpochSecond() / 30))));
        status(verified, 200);
        Http.Exchange principal = http().getOnHost(
                        slug + ".localhost:" + port(),
                        "/api/v1/identity/me",
                        Map.of(
                                "Authorization",
                                "Bearer " + console.body(verified).get("accessToken")));
        status(principal, 200);
        assertThat(console.body(principal)).containsEntry("role", "OWNER");
        // JSON is read as an untyped map (Map.class); the cast states the shape the assertions then check.
        @SuppressWarnings("unchecked")
        List<String> permissions = (List<String>) console.body(principal).get("permissions");
        assertThat(permissions).contains("staff.manage", "audit.list", "staff.list");

        // The operator was never signed in as the Owner: its credential opens nothing at the salon.
        assertThat(http().getOnHost(
                                slug + ".localhost:" + port(),
                                "/api/v1/identity/me",
                                Map.of("Authorization", "Bearer " + admin))
                        .status())
                .isIn(401, 404);
        // The success audit and the receipt hold no credential or personal values.
        String audit = owner.queryForObject(
                "SELECT details::text FROM platform_audit_entry WHERE action = 'salon.created' " + "AND subject_id = ?",
                String.class,
                result.get("id"));
        assertThat(audit).doesNotContain(ownerPassword, ownerEmail, "+94771234567", "Synthetic Road");
        String receipt = owner.queryForObject(
                "SELECT fingerprint || result::text FROM provisioning_receipt WHERE subject_id = ?",
                String.class,
                UUID.fromString((String) result.get("id")));
        assertThat(receipt).doesNotContain(ownerPassword, ownerEmail, "+94771234567", "Synthetic Road");
        assertThat(owner.queryForObject(
                        "SELECT trace_id FROM platform_audit_entry WHERE action = 'salon.created' "
                                + "AND subject_id = ?",
                        String.class,
                        result.get("id")))
                .matches("[0-9a-f]{32}");
    }

    @Test
    void anInvalidSalonIsRefusedWithFieldErrorsAndCreatesNothing() {
        String plan = newPlan();
        String slug = OperatorTestSupport.uniqueKey("v");
        Map<String, String> badValues = new LinkedHashMap<>();
        badValues.put("slug", "console");
        badValues.put("slug ", "ab");
        badValues.put("slug  ", "a".repeat(31));
        badValues.put("slug   ", "-bad-");
        badValues.put("country", "XX");
        badValues.put("currency", "JPY");
        badValues.put("currency ", "XXX");
        badValues.put("defaultLanguage", "fr");
        badValues.put("timeZone", "Mars/Base");
        badValues.put("timeZone ", "EST");
        badValues.put("contactEmail", "not-an-email");
        badValues.put("contactPhone", "12345");
        badValues.put("address", "a".repeat(501));
        badValues.put("name", " ");
        for (Map.Entry<String, String> each : badValues.entrySet()) {
            String field = each.getKey().strip();
            Map<String, Object> request = OperatorTestSupport.salon(
                    field.equals("slug") ? each.getValue() : slug,
                    plan,
                    "o-" + slug + "@example.test",
                    "owner synthetic " + UUID.randomUUID());
            request.put(field, each.getValue());
            Http.Exchange refused =
                    create("/salons", request, admin, UUID.randomUUID().toString());
            status(refused, 400);
            assertThat(fields(refused)).as("%s=%s", field, each.getValue()).contains(field);
        }
        Map<String, Object> unknown =
                OperatorTestSupport.salon(slug, plan, "o@example.test", "owner synthetic value 1");
        unknown.put("tenantId", UUID.randomUUID().toString());
        assertThat(fields(create("/salons", unknown, admin, UUID.randomUUID().toString())))
                .contains("tenantId");
        Map<String, Object> ownerUnknown =
                OperatorTestSupport.salon(slug, plan, "o@example.test", "owner synthetic v2");
        // JSON is read as an untyped map (Map.class); the cast states the shape the assertions then check.
        @SuppressWarnings("unchecked")
        Map<String, Object> ownerPart = (Map<String, Object>) ownerUnknown.get("owner");
        ownerPart.put("role", "OPERATOR_ADMIN");
        assertThat(fields(
                        create("/salons", ownerUnknown, admin, UUID.randomUUID().toString())))
                .contains("owner.role");
        Map<String, Object> weak = OperatorTestSupport.salon(slug, plan, "bad-email", "short");
        assertThat(fields(create("/salons", weak, admin, UUID.randomUUID().toString())))
                .contains("owner.email", "owner.password");
        Map<String, Object> noOwner = OperatorTestSupport.salon(slug, plan, "o@example.test", "owner synthetic v3");
        noOwner.remove("owner");
        assertThat(fields(create("/salons", noOwner, admin, UUID.randomUUID().toString())))
                .contains("owner");
        Map<String, Object> badPlan =
                OperatorTestSupport.salon(slug, "not-a-uuid", "o@example.test", "owner synthetic v4");
        assertThat(fields(create("/salons", badPlan, admin, UUID.randomUUID().toString())))
                .contains("planId");
        // A plan that does not exist: 404, nothing written.
        Http.Exchange missingPlan = create(
                "/salons",
                OperatorTestSupport.salon(
                        slug,
                        UUID.randomUUID().toString(),
                        "o-" + slug + "@example.test",
                        "owner synthetic " + UUID.randomUUID()),
                admin,
                UUID.randomUUID().toString());
        status(missingPlan, 404);
        assertThat(console.body(missingPlan)).containsEntry("code", "not_found");
        assertThat(count("SELECT count(*) FROM tenant WHERE slug = ?", slug)).isZero();
        assertThat(count("SELECT count(*) FROM identity_staff WHERE email = ?", "o-" + slug + "@example.test"))
                .isZero();
    }

    @Test
    void aRepeatedSalonCreationReplaysOneSalonAndAChangedRequestConflicts() throws Exception {
        String plan = newPlan();
        String slug = OperatorTestSupport.uniqueKey("r");
        String key = UUID.randomUUID().toString();
        Map<String, Object> request = OperatorTestSupport.salon(
                slug, plan, "r-" + slug + "@example.test", "owner synthetic " + UUID.randomUUID());
        List<Http.Exchange> answers = race(4, () -> create("/salons", request, admin, key));
        assertThat(answers).allSatisfy(a -> status(a, 201));
        assertThat(answers.stream().map(a -> console.body(a).get("id")).distinct())
                .hasSize(1);
        assertThat(count("SELECT count(*) FROM tenant WHERE slug = ?", slug)).isEqualTo(1);
        assertThat(count(
                        "SELECT count(*) FROM platform_audit_entry WHERE action = 'salon.created' AND details->>'slug' = ?",
                        slug))
                .isEqualTo(1);
        // A changed password under the same key is a different request.
        Map<String, Object> changed = OperatorTestSupport.salon(
                slug, plan, "r-" + slug + "@example.test", "another synthetic password value");
        Http.Exchange conflict = create("/salons", changed, admin, key);
        status(conflict, 409);
        assertThat(console.body(conflict)).containsEntry("code", "idempotency_conflict");
        status(create("/salons", request, null, key), 401);
    }

    @Test
    void simultaneousDistinctKeysForOneSlugCreateOneSalonAndAnExistingSalonIsNeverReplaced() throws Exception {
        String plan = newPlan();
        String slug = OperatorTestSupport.uniqueKey("c");
        List<Http.Exchange> answers = race(
                5,
                () -> create(
                        "/salons",
                        OperatorTestSupport.salon(
                                slug,
                                plan,
                                "c-" + UUID.randomUUID() + "@example.test",
                                "owner synthetic " + UUID.randomUUID()),
                        admin,
                        UUID.randomUUID().toString()));
        assertThat(answers).filteredOn(a -> a.status() == 201).hasSize(1);
        assertThat(answers)
                .filteredOn(a -> a.status() == 409)
                .allSatisfy(a -> assertThat(console.body(a)).containsEntry("code", "slug_taken"))
                .hasSize(4);
        assertThat(count("SELECT count(*) FROM tenant WHERE slug = ?", slug)).isEqualTo(1);
        UUID tenant = owner.queryForObject("SELECT id FROM tenant WHERE slug = ?", UUID.class, slug);
        assertThat(count("SELECT count(*) FROM identity_staff WHERE tenant_id = ?", tenant))
                .isEqualTo(1);

        TestSalon existing = new TestSalons(owner).create("Existing Salon", "#556677");
        String before = owner.queryForObject(
                "SELECT display_name || brand_colour FROM salon_brand WHERE tenant_id = ?",
                String.class,
                existing.id());
        Http.Exchange taken = create(
                "/salons",
                OperatorTestSupport.salon(
                        existing.slug(),
                        plan,
                        "x-" + UUID.randomUUID() + "@example.test",
                        "owner synthetic " + UUID.randomUUID()),
                admin,
                UUID.randomUUID().toString());
        status(taken, 409);
        assertThat(console.body(taken)).containsEntry("code", "slug_taken");
        assertThat(owner.queryForObject(
                        "SELECT display_name || brand_colour FROM salon_brand WHERE tenant_id = ?",
                        String.class,
                        existing.id()))
                .isEqualTo(before);
        assertThat(count("SELECT count(*) FROM identity_staff WHERE tenant_id = ?", existing.id()))
                .isZero();
    }

    // --- AC-13: rollback ---------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(BootstrapFaults.Step.class)
    void aFailedBootstrapStepLeavesNothingAndARetryWithTheSameKeySucceeds(BootstrapFaults.Step step) {
        String plan = newPlan();
        String slug = OperatorTestSupport.uniqueKey("f");
        String ownerEmail = "f-" + slug + "@example.test";
        String key = UUID.randomUUID().toString();
        Map<String, Object> request =
                OperatorTestSupport.salon(slug, plan, ownerEmail, "owner synthetic " + UUID.randomUUID());
        long tenants = count("SELECT count(*) FROM tenant");
        long categories = count("SELECT count(*) FROM catalogue_category");
        FAIL_AT.set(step);
        Http.Exchange failed = create("/salons", request, admin, key);
        status(failed, 500);
        assertThat(count("SELECT count(*) FROM tenant")).isEqualTo(tenants);
        assertThat(count("SELECT count(*) FROM tenant WHERE slug = ?", slug)).isZero();
        assertThat(count("SELECT count(*) FROM identity_staff WHERE email = ?", ownerEmail))
                .isZero();
        assertThat(count("SELECT count(*) FROM salon_brand WHERE display_name = ?", "Synthetic Salon " + slug))
                .isZero();
        // F04-S01 AC-1: the default branch is created in the same transaction, so it is rolled back with it.
        assertThat(count("SELECT count(*) FROM branding_branch WHERE name = ?", "Synthetic Salon " + slug))
                .isZero();
        assertThat(count("SELECT count(*) FROM provisioning_receipt WHERE idempotency_key = ?", UUID.fromString(key)))
                .isZero();
        assertThat(count(
                        "SELECT count(*) FROM platform_audit_entry WHERE action = 'salon.created' AND details->>'slug' = ?",
                        slug))
                .isZero();
        // F05-S01: the default categories are written in the same transaction, after the brand, so a failure at
        // or after the brand step leaves none behind.
        assertThat(count("SELECT count(*) FROM catalogue_category")).isEqualTo(categories);
        status(http().getOnHost(slug + ".localhost:" + port(), "/api/v1/salon"), 404);

        FAIL_AT.set(null);
        Http.Exchange retried = create("/salons", request, admin, key);
        status(retried, 201);
        assertThat(count("SELECT count(*) FROM tenant WHERE slug = ?", slug)).isEqualTo(1);
        assertThat(count(
                        "SELECT count(*) FROM branding_branch b JOIN tenant t ON t.id = b.tenant_id"
                                + " WHERE t.slug = ? AND b.is_default",
                        slug))
                .isEqualTo(1);
        assertThat(count(
                        "SELECT count(*) FROM catalogue_category c JOIN tenant t ON t.id = c.tenant_id WHERE t.slug = ?",
                        slug))
                .isEqualTo(10);
        status(http().getOnHost(slug + ".localhost:" + port(), "/api/v1/salon"), 200);
    }

    @Test
    void aProvisionedSalonHasTheTenDefaultCategoriesInOrderOnItsDefaultBranch() {
        String plan = newPlan();
        String slug = OperatorTestSupport.uniqueKey("c");
        Map<String, Object> request = OperatorTestSupport.salon(
                slug, plan, "c-" + slug + "@example.test", "owner synthetic " + UUID.randomUUID());

        status(create("/salons", request, admin, UUID.randomUUID().toString()), 201);

        UUID salon = owner.queryForObject("SELECT id FROM tenant WHERE slug = ?", UUID.class, slug);
        List<Map<String, Object>> categories = owner.execute((java.sql.Connection connection) -> {
            try (var statement = connection.createStatement()) {
                statement.execute("BEGIN");
                statement.execute("SELECT set_config('app.tenant_id', '" + salon + "', true)");
                var rows = statement.executeQuery("SELECT c.name, c.position, c.branch_id = b.id AS on_default"
                        + " FROM catalogue_category c JOIN branding_branch b ON b.tenant_id = c.tenant_id"
                        + " AND b.is_default WHERE c.tenant_id = '" + salon + "' ORDER BY c.position");
                List<Map<String, Object>> found = new java.util.ArrayList<>();
                while (rows.next()) {
                    found.add(Map.of(
                            "name", rows.getString(1), "position", rows.getInt(2), "onDefault", rows.getBoolean(3)));
                }
                statement.execute("COMMIT");
                return found;
            }
        });
        assertThat(categories)
                .extracting(category -> category.get("name"))
                .containsExactly(
                        "Cuts & styling",
                        "Colour",
                        "Facials & skin",
                        "Nails",
                        "Brows & lashes",
                        "Waxing",
                        "Makeup & draping",
                        "Massage & spa",
                        "Bridal",
                        "Consultations");
        assertThat(categories).extracting(category -> category.get("onDefault")).containsOnly(true);
    }

    // --- AC-3: permissions -------------------------------------------------------------------------

    @Test
    void supportAndEverySalonPrincipalAreRefusedEveryProtectedActionAndChangeNothing() {
        String plan = newPlan();
        String support = console.token(console.create("OPERATOR_SUPPORT"));
        long plans = count("SELECT count(*) FROM plan");
        long tenants = count("SELECT count(*) FROM tenant");
        TestSalon salon = new TestSalons(owner).create("Permission Salon", "#667788");
        List<String> salonTokens = new ArrayList<>();
        for (String role : List.of("OWNER", "MANAGER", "RECEPTIONIST", "PROFESSIONAL", "ACCOUNTANT")) {
            salonTokens.add(identityCrypto.access(salon.id(), UUID.randomUUID(), UUID.randomUUID(), "staff", 1));
        }
        salonTokens.add(identityCrypto.access(salon.id(), UUID.randomUUID(), UUID.randomUUID(), "customer", 1));

        String slug = OperatorTestSupport.uniqueKey("d");
        for (String token : salonTokens) {
            for (String path : List.of("/plans", "/salons", "/plans/" + plan, "/me")) {
                status(console.call("GET", path, null, token), 401);
            }
            status(
                    create(
                            "/plans",
                            OperatorTestSupport.plan(OperatorTestSupport.uniqueKey("d")),
                            token,
                            UUID.randomUUID().toString()),
                    401);
            status(
                    create(
                            "/salons",
                            OperatorTestSupport.salon(slug, plan, "d@example.test", "owner synthetic v5"),
                            token,
                            UUID.randomUUID().toString()),
                    401);
        }
        // F03-S04: Support reads the salon directory (operator.salon.read); plans stay the Admin's.
        for (String path : List.of("/plans", "/plans/" + plan)) {
            Http.Exchange denied = console.call("GET", path, null, support);
            status(denied, 403);
            assertThat(console.body(denied)).containsEntry("code", "permission_denied");
        }
        status(console.call("GET", "/salons", null, support), 200);
        status(
                create(
                        "/plans",
                        OperatorTestSupport.plan(OperatorTestSupport.uniqueKey("d")),
                        support,
                        UUID.randomUUID().toString()),
                403);
        status(
                create(
                        "/salons",
                        OperatorTestSupport.salon(slug, plan, "d@example.test", "owner synthetic v5"),
                        support,
                        UUID.randomUUID().toString()),
                403);
        assertThat(count("SELECT count(*) FROM plan")).isEqualTo(plans);
        assertThat(count("SELECT count(*) FROM tenant")).isEqualTo(tenants + 1);
        assertThat(count("SELECT count(*) FROM tenant WHERE slug = ?", slug)).isZero();
    }

    // --- AC-14: lists ------------------------------------------------------------------------------

    @Test
    void listsPageStablyWithTotalsLiteralSearchAndAllowlistedFields() {
        String plan = newPlan();
        String marker = OperatorTestSupport.uniqueKey("m");
        List<String> created = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Map<String, Object> request = OperatorTestSupport.salon(
                    marker + i, plan, "l" + i + "-" + marker + "@example.test", "owner synthetic " + UUID.randomUUID());
            request.put("name", "List 100%_" + marker + " " + i);
            Http.Exchange answer =
                    create("/salons", request, admin, UUID.randomUUID().toString());
            status(answer, 201);
            created.add((String) console.body(answer).get("id"));
        }
        Http.Exchange page0 = console.call("GET", "/salons?q=" + marker + "&page=0&size=2", null, admin);
        status(page0, 200);
        Map<String, Object> first = console.body(page0);
        assertThat(first).containsEntry("total", 3).containsEntry("page", 0).containsEntry("size", 2);
        // JSON is read as an untyped map (Map.class); the cast states the shape the assertions then check.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) first.get("items");
        assertThat(items).extracting(i -> i.get("id")).containsExactly(created.get(2), created.get(1));
        assertThat(items.get(0).keySet())
                .containsExactlyInAnyOrder("id", "name", "slug", "hostname", "status", "plan", "createdAt");
        assertThat(page0.body()).doesNotContain("password", "ownerEmail", "contactPhone", "@example.test");
        Map<String, Object> second =
                console.body(console.call("GET", "/salons?q=" + marker + "&page=1&size=2", null, admin));
        // JSON is read as an untyped map (Map.class); the cast states the shape the assertions then check.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rest = (List<Map<String, Object>>) second.get("items");
        assertThat(rest).extracting(i -> i.get("id")).containsExactly(created.get(0));
        // Literal: SQL wildcards match only themselves; the slug matches case-insensitively.
        assertThat(console.body(console.call("GET", "/salons?q=100%25_" + marker, null, admin)))
                .containsEntry("total", 3);
        assertThat(console.body(console.call("GET", "/salons?q=100__" + marker, null, admin)))
                .containsEntry("total", 0);
        assertThat(console.body(console.call("GET", "/salons?q=" + marker.toUpperCase() + "1", null, admin)))
                .containsEntry("total", 1);
        // Plans search by key.
        Map<String, Object> planPage = console.body(console.call("GET", "/plans?size=100", null, admin));
        assertThat(((Number) planPage.get("total")).longValue()).isGreaterThanOrEqualTo(1);

        for (String query : List.of("page=-1", "page=100001", "size=0", "size=101", "page=x", "q=" + "q".repeat(121))) {
            Http.Exchange refused = console.call("GET", "/salons?" + query, null, admin);
            status(refused, 400);
            assertThat(fields(refused)).isNotEmpty();
        }
        status(console.call("GET", "/salons/" + UUID.randomUUID(), null, admin), 404);
        status(console.call("GET", "/plans/" + UUID.randomUUID(), null, admin), 404);
        Http.Exchange badId = console.call("GET", "/salons/not-an-id", null, admin);
        status(badId, 400);
        assertThat(fields(badId)).containsExactly("id");
    }

    @Test
    void aLegacySalonShowsItsUnconfiguredProfileAsNullNotFabricated() {
        TestSalon legacy = new TestSalons(owner).create("Legacy Brand Name", "#778899");
        Map<String, Object> detail = console.body(console.call("GET", "/salons/" + legacy.id(), null, admin));
        assertThat(detail)
                .containsEntry("slug", legacy.slug())
                .containsEntry("name", null)
                .containsEntry("plan", null)
                .containsEntry("ownerEmail", null)
                .containsEntry("country", null)
                .containsEntry("currency", null)
                .containsEntry("defaultLanguage", null)
                .containsEntry("timeZone", "Asia/Colombo");
        // No tenant business record reaches the console: the brand name is the salon's own data.
        assertThat(detail.toString()).doesNotContain("Legacy Brand Name");
    }

    private static <T> List<T> race(int count, Callable<T> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return work.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
