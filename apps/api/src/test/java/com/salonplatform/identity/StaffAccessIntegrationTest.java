package com.salonplatform.identity;

import static org.assertj.core.api.Assertions.*;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.salonplatform.identity.domain.*;
import com.salonplatform.identity.infrastructure.*;
import com.salonplatform.shared.tenancy.*;
import com.salonplatform.testing.*;
import com.salonplatform.testing.TestSalons.TestSalon;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Staff APIs on real HTTP/PostgreSQL: security, isolation, live permissions and simultaneous
 * one-use controls.
 */
// JSON bodies are read as untyped maps (Map.class); each cast states the shape the assertion
// beside it then checks, so a wrong shape fails that assertion with a ClassCastException.
@SuppressWarnings("unchecked")
class StaffAccessIntegrationTest extends IntegrationTest {
    @Autowired
    IdentityCrypto crypto;

    @Autowired
    IdentitySecrets secrets;

    @Autowired
    Clock clock;

    @Autowired
    JsonMapper json;

    @Autowired
    PlatformTransactionManager manager;

    @PersistenceContext
    EntityManager em;

    private final JdbcTemplate owner = TestDatabase.of(TestPostgres.shared()).asOwner();
    private TestSalons salons;
    private TestSalon alpha, beta;
    private String password;
    private Map<StaffRole, StaffAccount> accounts;
    private Map<UUID, String> totpSecrets;

    @BeforeEach
    void setup() throws Exception {
        salons = new TestSalons(owner);
        alpha = salons.create("Identity Alpha", "#111111");
        beta = salons.create("Identity Beta", "#222222");
        password = UUID.randomUUID() + " synthetic";
        accounts = new EnumMap<>(StaffRole.class);
        totpSecrets = new HashMap<>();
        for (StaffRole role : StaffRole.values()) accounts.put(role, add(alpha, role, role == StaffRole.OWNER));
        add(beta, StaffRole.OWNER, true);
        add(beta, StaffRole.MANAGER, false);
    }

    private StaffAccount add(TestSalon salon, StaffRole role, boolean mfa) throws Exception {
        return TenantContext.callAs(
                salons.asCurrent(salon),
                () -> new TransactionTemplate(manager).execute(status -> {
                    StaffAccount a = new StaffAccount();
                    a.id = UUID.randomUUID();
                    a.email = role.name().toLowerCase(Locale.ROOT) + "@example.test";
                    a.mobile = "+9477" + String.format("%07d", role.ordinal() + 1);
                    a.role = role.name();
                    a.active = true;
                    a.createdAt = clock.instant();
                    a.updatedAt = a.createdAt;
                    a.passwordHash = crypto.encodePassword(password);
                    if (mfa) {
                        String secret = crypto.newTotpSecret();
                        totpSecrets.put(a.id, secret);
                        a.mfaSecret = crypto.encrypt("totp:" + salon.id() + ":" + a.id, secret);
                    }
                    em.persist(a);
                    return a;
                }));
    }

    private Map<String, Object> body(Http.Exchange response) {
        return json.readValue(response.body(), Map.class);
    }

    private Http.Exchange call(TestSalon salon, String method, String path, Object request, String token) {
        return callHost(salon.host(port()), method, path, request, token);
    }

    private Http.Exchange callHost(String host, String method, String path, Object request, String token) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        if (token != null) headers.put("Authorization", "Bearer " + token);
        return http().sendOnHost(
                        method,
                        host,
                        "/api/v1/identity" + path,
                        headers,
                        request == null ? null : json.writeValueAsString(request));
    }

    private Http.Exchange signIn(TestSalon salon, StaffRole role) {
        return call(
                salon,
                "POST",
                "/staff/sign-in",
                Map.of(
                        "identifier",
                        role.name().toLowerCase(Locale.ROOT) + "@example.test",
                        "password",
                        password,
                        "deviceLabel",
                        "JUnit device"),
                null);
    }

    private Map<String, Object> tokens(StaffRole role) {
        Http.Exchange sign = signIn(alpha, role);
        assertThat(sign.status()).isEqualTo(200);
        Map<String, Object> result = body(sign);
        if (result.get("credentials") != null) return (Map<String, Object>) result.get("credentials");
        String code = crypto.totpCode(
                totpSecrets.get(accounts.get(role).id), clock.instant().getEpochSecond() / 30);
        Http.Exchange verify = call(
                alpha,
                "POST",
                "/staff/mfa/verify",
                Map.of(
                        "challengeId",
                        result.get("challengeId"),
                        "challengeToken",
                        result.get("challengeToken"),
                        "code",
                        code),
                null);
        assertThat(verify.status()).isEqualTo(200);
        return body(verify);
    }

    private String token(StaffRole role) {
        return (String) tokens(role).get("accessToken");
    }

    private void status(Http.Exchange result, int expected) {
        assertThat(result.status()).withFailMessage("%s", result.body()).isEqualTo(expected);
    }

    @Test
    void emailMobileAndUnknownDisabledCredentialsHaveCorrectOutcomes() {
        Http.Exchange valid = call(
                alpha,
                "POST",
                "/staff/sign-in",
                Map.of("identifier", " RECEPTIONIST@EXAMPLE.TEST ", "password", password, "deviceLabel", "phone"),
                null);
        status(valid, 200);
        Map<String, Object> t = (Map<String, Object>) body(valid).get("credentials");
        assertThat(Instant.parse((String) t.get("accessExpiresAt")))
                .isAfter(clock.instant().plusSeconds(290));
        assertThat(Instant.parse((String) t.get("refreshExpiresAt")))
                .isAfter(clock.instant().plusSeconds(6 * 86400));
        status(
                call(
                        alpha,
                        "POST",
                        "/staff/sign-in",
                        Map.of("identifier", "0770000003", "password", password, "deviceLabel", "phone"),
                        null),
                200);
        for (String identifier : List.of("unknown@example.test", "manager@example.test"))
            status(
                    call(
                            alpha,
                            "POST",
                            "/staff/sign-in",
                            Map.of(
                                    "identifier",
                                    identifier,
                                    "password",
                                    UUID.randomUUID().toString(),
                                    "deviceLabel",
                                    "phone"),
                            null),
                    401);
        owner.update("UPDATE identity_staff SET active=false WHERE id=?", accounts.get(StaffRole.MANAGER).id);
        status(signIn(alpha, StaffRole.MANAGER), 401);
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM identity_rate_bucket WHERE tenant_id=?", Long.class, alpha.id()))
                .isPositive();
    }

    @Test
    void failedUnknownIdentifierLimitsAreDurableAndCannotBeBypassedByBodyAddress() {
        for (int i = 0; i < 10; i++)
            status(
                    call(
                            alpha,
                            "POST",
                            "/staff/sign-in",
                            Map.of("identifier", "missing@example.test", "password", password, "deviceLabel", "phone"),
                            null),
                    401);
        status(
                call(
                        alpha,
                        "POST",
                        "/staff/sign-in",
                        Map.of(
                                "identifier",
                                "missing@example.test",
                                "password",
                                password,
                                "deviceLabel",
                                "other",
                                "address",
                                "1.2.3.4"),
                        null),
                429);
        assertThat(owner.queryForObject(
                        "SELECT max(attempts) FROM identity_rate_bucket WHERE tenant_id=?"
                                + " AND dimension='staff-identifier'",
                        Integer.class,
                        alpha.id()))
                .isEqualTo(11);
    }

    @Test
    void ownerEnrollmentIsLimitedAndNeverGrantsAProtectedSessionBeforeTotp() {
        owner.update("UPDATE identity_staff SET mfa_secret=null WHERE id=?", accounts.get(StaffRole.OWNER).id);
        Map<String, Object> c = body(signIn(alpha, StaffRole.OWNER));
        assertThat(c.get("state")).isEqualTo("enroll_mfa");
        assertThat(c.get("credentials")).isNull();
        status(call(alpha, "GET", "/me", null, (String) c.get("challengeToken")), 401);
        Map<String, Object> input =
                Map.of("challengeId", c.get("challengeId"), "challengeToken", c.get("challengeToken"));
        Http.Exchange first = call(alpha, "POST", "/staff/mfa/enroll", input, null);
        status(first, 200);
        String secret = (String) body(first).get("secret");
        assertThat(body(call(alpha, "POST", "/staff/mfa/enroll", input, null)).get("secret"))
                .isEqualTo(secret);
        Map<String, Object> verify = new HashMap<>(input);
        verify.put("code", crypto.totpCode(secret, clock.instant().getEpochSecond() / 30));
        Http.Exchange success = call(alpha, "POST", "/staff/mfa/verify", verify, null);
        status(success, 200);
        status(call(alpha, "POST", "/staff/mfa/verify", verify, null), 401);
        status(call(alpha, "GET", "/me", null, (String) body(success).get("accessToken")), 200);
        String stored = owner.queryForObject(
                "SELECT mfa_secret FROM identity_staff WHERE id=?", String.class, accounts.get(StaffRole.OWNER).id);
        assertThat(stored).doesNotContain(secret);
    }

    @Test
    void nonOwnerCanEnrollVoluntarilyAndOldSessionStopsWorking() {
        StaffAccount a = accounts.get(StaffRole.RECEPTIONIST);
        owner.update("UPDATE identity_staff SET mfa_secret=null WHERE id=?", a.id);
        Map<String, Object> original = tokens(StaffRole.RECEPTIONIST);
        String bearer = (String) original.get("accessToken");
        Map<String, Object> challenge = body(call(alpha, "POST", "/staff/mfa/challenges", null, bearer));
        Map<String, Object> request =
                Map.of("challengeId", challenge.get("challengeId"), "challengeToken", challenge.get("challengeToken"));
        Http.Exchange enrollment = call(alpha, "POST", "/staff/mfa/enroll", request, null);
        status(enrollment, 200);
        String secret = (String) body(enrollment).get("secret");
        Map<String, Object> verify = new HashMap<>(request);
        verify.put("code", crypto.totpCode(secret, clock.instant().getEpochSecond() / 30));
        Http.Exchange signed = call(alpha, "POST", "/staff/mfa/verify", verify, null);
        status(signed, 200);
        status(call(alpha, "GET", "/me", null, bearer), 401);
        Http.Exchange current =
                call(alpha, "GET", "/me", null, (String) body(signed).get("accessToken"));
        status(current, 200);
        assertThat(body(current).get("mfaEnabled")).isEqualTo(true);
    }

    @Test
    void twoSalonsConcurrentRequestsAndCustomerKindCannotCrossStaffBoundary() throws Exception {
        String a = token(StaffRole.MANAGER);
        Map<String, Object> betaSign = body(signIn(beta, StaffRole.MANAGER));
        String b = (String) ((Map<String, Object>) betaSign.get("credentials")).get("accessToken");
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Integer>> results = new ArrayList<>();
            for (int n = 0; n < 20; n++) {
                boolean foreign = n % 2 == 0;
                results.add(pool.submit(
                        () -> call(beta, "GET", "/me", null, foreign ? a : b).status()));
            }
            for (int n = 0; n < results.size(); n++)
                assertThat(results.get(n).get()).isEqualTo(n % 2 == 0 ? 404 : 200);
        }
        Map<String, Object> session = tokens(StaffRole.RECEPTIONIST);
        String customer = crypto.access(
                alpha.id(),
                accounts.get(StaffRole.RECEPTIONIST).id,
                UUID.fromString((String) session.get("sessionId")),
                "customer",
                1);
        status(call(alpha, "GET", "/me", null, customer), 401);
        status(call(alpha, "GET", "/staff", null, customer), 401);
    }

    @Test
    void fiveMfaFailuresExpireChallengeWithoutResetByEnrollmentRetry() {
        Map<String, Object> c = body(signIn(alpha, StaffRole.OWNER));
        for (int i = 0; i < 5; i++)
            status(
                    call(
                            alpha,
                            "POST",
                            "/staff/mfa/verify",
                            Map.of(
                                    "challengeId",
                                    c.get("challengeId"),
                                    "challengeToken",
                                    c.get("challengeToken"),
                                    "code",
                                    "wrong"),
                            null),
                    401);
        String code = crypto.totpCode(
                totpSecrets.get(accounts.get(StaffRole.OWNER).id),
                clock.instant().getEpochSecond() / 30);
        status(
                call(
                        alpha,
                        "POST",
                        "/staff/mfa/verify",
                        Map.of(
                                "challengeId",
                                c.get("challengeId"),
                                "challengeToken",
                                c.get("challengeToken"),
                                "code",
                                code),
                        null),
                401);
        assertThat(owner.queryForObject(
                        "SELECT attempts FROM identity_mfa_challenge WHERE id=?",
                        Integer.class,
                        UUID.fromString((String) c.get("challengeId"))))
                .isEqualTo(5);
    }

    @Test
    void simultaneousTotpUseProducesOneSession() throws Exception {
        Map<String, Object> c = body(signIn(alpha, StaffRole.OWNER));
        String code = crypto.totpCode(
                totpSecrets.get(accounts.get(StaffRole.OWNER).id),
                clock.instant().getEpochSecond() / 30);
        Object request =
                Map.of("challengeId", c.get("challengeId"), "challengeToken", c.get("challengeToken"), "code", code);
        List<Integer> statuses = race(
                () -> call(alpha, "POST", "/staff/mfa/verify", request, null).status());
        assertThat(statuses).containsExactlyInAnyOrder(200, 401);
    }

    @Test
    void jwtValidationHostBindingAndNativeAccessAreFailClosed() {
        String access = token(StaffRole.MANAGER);
        status(call(beta, "GET", "/me", null, access), 404);
        status(
                call(
                        beta,
                        "POST",
                        "/staff/sign-in",
                        Map.of("identifier", "manager@example.test", "password", password, "deviceLabel", "phone"),
                        access),
                404);
        status(
                call(
                        beta,
                        "POST",
                        "/sessions/refresh",
                        Map.of("refreshToken", java.util.UUID.randomUUID().toString()),
                        access),
                404);
        status(callHost("localhost:" + port(), "GET", "/me", null, access), 200);
        status(callHost("console.localhost:" + port(), "GET", "/me", null, access), 404);
        status(callHost("unknown.localhost:" + port(), "GET", "/me", null, access), 404);
        for (String hostile :
                List.of(access.substring(0, access.length() - 6) + "xxxxxx", "eyJhbGciOiJub25lIn0.e30.", "broken"))
            status(call(alpha, "GET", "/me", null, hostile), 401);
        Map<String, Object> t = tokens(StaffRole.RECEPTIONIST);
        status(
                callHost(
                        "localhost:" + port(),
                        "POST",
                        "/sessions/refresh",
                        Map.of("refreshToken", t.get("refreshToken")),
                        (String) t.get("accessToken")),
                404);
    }

    @Test
    void issuerAudienceKindExpiryAndAlgorithmAreValidated() {
        Map<String, Object> t = tokens(StaffRole.MANAGER);
        UUID sid = UUID.fromString((String) t.get("sessionId"));
        for (String mutation : List.of("issuer", "audience", "expiry", "future", "kind", "algorithm")) {
            JwtClaimsSet c = JwtClaimsSet.builder()
                    .issuer(mutation.equals("issuer") ? "wrong" : "salon-platform")
                    .audience(List.of(mutation.equals("audience") ? "wrong" : "salon-api"))
                    .subject(accounts.get(StaffRole.MANAGER).id.toString())
                    .issuedAt(clock.instant().plusSeconds(mutation.equals("future") ? 60 : -600))
                    .expiresAt(clock.instant().plusSeconds(mutation.equals("expiry") ? -1 : 300))
                    .claim("tenant", alpha.id().toString())
                    .claim("session", sid.toString())
                    .claim("kind", mutation.equals("kind") ? "operator" : "staff")
                    .claim("generation", 1)
                    .build();
            JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(secrets.signing()));
            String bad;
            if (mutation.equals("algorithm")) {
                byte[] key = new byte[64];
                new java.security.SecureRandom().nextBytes(key);
                encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
                bad = encoder.encode(JwtEncoderParameters.from(
                                JwsHeader.with(MacAlgorithm.HS512).build(), c))
                        .getTokenValue();
            } else
                bad = encoder.encode(JwtEncoderParameters.from(
                                JwsHeader.with(MacAlgorithm.HS256).build(), c))
                        .getTokenValue();
            status(call(alpha, "GET", "/me", null, bad), 401);
        }
    }

    @Test
    void sessionsAreOwnPaginatedAndRevocationIsImmediate() {
        Map<String, Object> manager = tokens(StaffRole.MANAGER), reception = tokens(StaffRole.RECEPTIONIST);
        String m = (String) manager.get("accessToken"), r = (String) reception.get("accessToken");
        Http.Exchange list = call(alpha, "GET", "/sessions?page=0&size=1", null, m);
        status(list, 200);
        assertThat(body(list).get("total")).isEqualTo(1);
        assertThat((List<?>) body(list).get("items")).hasSize(1);
        status(call(alpha, "DELETE", "/sessions/" + reception.get("sessionId"), null, m), 404);
        status(call(alpha, "DELETE", "/sessions/" + manager.get("sessionId"), null, m), 204);
        status(call(alpha, "GET", "/me", null, m), 401);
        status(call(alpha, "GET", "/me", null, r), 200);
        status(call(alpha, "GET", "/sessions?size=101", null, r), 400);
        status(call(alpha, "DELETE", "/sessions", null, r), 204);
        status(call(alpha, "GET", "/me", null, r), 401);
    }

    @Test
    void refreshIsAtomicHashOnlyAndReplayRevokesFamily() throws Exception {
        Map<String, Object> t = tokens(StaffRole.MANAGER);
        String refresh = (String) t.get("refreshToken");
        Object request = Map.of("refreshToken", refresh);
        List<Http.Exchange> results = raceResults(() -> call(alpha, "POST", "/sessions/refresh", request, null));
        assertThat(results.stream().map(Http.Exchange::status).toList()).containsExactlyInAnyOrder(200, 401);
        Http.Exchange win =
                results.stream().filter(r -> r.status() == 200).findFirst().orElseThrow();
        status(call(alpha, "GET", "/me", null, (String) body(win).get("accessToken")), 401);
        assertThat(owner.queryForObject(
                        "SELECT refresh_hash FROM identity_session WHERE id=?", String.class, UUID.fromString((String)
                                t.get("sessionId"))))
                .doesNotContain(refresh);
    }

    @Test
    void allRolePermissionsAreReturnedAndEnforced() {
        for (StaffRole role : StaffRole.values()) {
            String token = token(role);
            Http.Exchange me = call(alpha, "GET", "/me", null, token);
            status(me, 200);
            Map<String, Object> p = body(me);
            assertThat(new HashSet<>((List<String>) p.get("permissions"))).isEqualTo(role.permissions(false));
            assertThat(p.get("scope")).isEqualTo(role.scope(false));
            status(
                    call(alpha, "GET", "/staff", null, token),
                    role == StaffRole.OWNER || role == StaffRole.MANAGER ? 200 : 403);
            status(
                    call(alpha, "GET", "/audit", null, token),
                    role == StaffRole.OWNER || role == StaffRole.MANAGER ? 200 : 403);
            if (role != StaffRole.OWNER)
                status(
                        call(
                                alpha,
                                "POST",
                                "/staff",
                                Map.of(
                                        "email",
                                        UUID.randomUUID() + "@example.test",
                                        "password",
                                        password,
                                        "role",
                                        "MANAGER"),
                                token),
                        403);
        }
    }

    @Test
    void administrationIsVersionedAndPermissionsChangeImmediately() {
        String ownerToken = token(StaffRole.OWNER),
                professional = token(StaffRole.PROFESSIONAL),
                manager = token(StaffRole.MANAGER);
        StaffAccount pro = accounts.get(StaffRole.PROFESSIONAL);
        status(
                call(alpha, "PATCH", "/staff/" + pro.id, Map.of("version", 0, "professionalGrant", true), ownerToken),
                200);
        assertThat(body(call(alpha, "GET", "/me", null, professional)).get("scope"))
                .isEqualTo("salon");
        status(
                call(alpha, "PATCH", "/staff/" + pro.id, Map.of("version", 0, "professionalGrant", false), ownerToken),
                409);
        status(
                call(
                        alpha,
                        "PATCH",
                        "/staff/" + accounts.get(StaffRole.MANAGER).id,
                        Map.of("version", 0, "role", "RECEPTIONIST"),
                        ownerToken),
                200);
        status(call(alpha, "GET", "/staff", null, manager), 403);
        status(
                call(
                        alpha,
                        "POST",
                        "/staff",
                        Map.of("email", "manager@example.test", "password", password, "role", "ACCOUNTANT"),
                        ownerToken),
                409);
        status(
                call(
                        alpha,
                        "POST",
                        "/staff",
                        Map.of("email", "bad-email", "password", password, "role", "ACCOUNTANT"),
                        ownerToken),
                400);
        status(call(alpha, "DELETE", "/staff/" + pro.id, null, ownerToken), 204);
        status(call(alpha, "GET", "/me", null, professional), 401);
        UUID foreign = owner.queryForObject(
                "SELECT id FROM identity_staff WHERE tenant_id=? AND role='OWNER'", UUID.class, beta.id());
        status(call(alpha, "DELETE", "/staff/" + foreign, null, ownerToken), 404);
    }

    @Test
    void singleIdentifierStaffCreationSignInAndListsMatchNullableContract() {
        String ownerToken = token(StaffRole.OWNER);
        String email = UUID.randomUUID() + "@synthetic.example";
        String mobile = "+94771234567";
        List<Map<String, Object>> created = new ArrayList<>();
        for (var identifiers : List.of(
                Map.of("email", "  " + email.toUpperCase(Locale.ROOT) + "  "), Map.of("mobile", "0771234567"))) {
            Map<String, Object> request = new HashMap<>(identifiers);
            request.put("password", password);
            request.put("role", "PROFESSIONAL");
            var response = call(alpha, "POST", "/staff", request, ownerToken);
            status(response, 201);
            var account = body(response);
            assertThat(account)
                    .containsEntry("email", identifiers.containsKey("email") ? email : null)
                    .containsEntry("mobile", identifiers.containsKey("mobile") ? mobile : null);
            created.add(account);
            var signIn = call(
                    alpha,
                    "POST",
                    "/staff/sign-in",
                    Map.of(
                            "identifier",
                            identifiers.containsKey("email") ? email : mobile,
                            "password",
                            password,
                            "deviceLabel",
                            "Synthetic identifier check"),
                    null);
            status(signIn, 200);
            var credentials = (Map<String, Object>) body(signIn).get("credentials");
            status(call(alpha, "GET", "/me", null, (String) credentials.get("accessToken")), 200);
            status(call(beta, "GET", "/me", null, (String) credentials.get("accessToken")), 404);
            status(call(alpha, "POST", "/staff", request, ownerToken), 409);
        }
        var page = call(alpha, "GET", "/staff?size=100", null, ownerToken);
        status(page, 200);
        var items = (List<Map<String, Object>>) body(page).get("items");
        for (var account : created) assertThat(items).contains(account);
        status(call(alpha, "POST", "/staff", Map.of("password", password, "role", "PROFESSIONAL"), ownerToken), 400);
        status(
                call(
                        alpha,
                        "POST",
                        "/staff",
                        Map.of(
                                "email",
                                UUID.randomUUID() + "@synthetic.example",
                                "password",
                                password,
                                "role",
                                "PROFESSIONAL"),
                        token(StaffRole.MANAGER)),
                403);
        Map<String, Object> document = json.readValue(http().get("/v3/api-docs").body(), Map.class);
        var schemas = (Map<String, Object>) ((Map<String, Object>) document.get("components")).get("schemas");
        var staff = (Map<String, Object>) schemas.get("StaffView");
        var properties = (Map<String, Object>) staff.get("properties");
        for (String field : List.of("email", "mobile")) {
            Object types = ((Map<String, Object>) properties.get(field)).get("type");
            assertThat(types).as("Staff %s permits string and null", field).isInstanceOf(List.class);
            assertThat((List<String>) types).containsExactlyInAnyOrder("string", "null");
            assertThat((List<String>) staff.get("required")).contains(field);
        }
        var pageSchema = (Map<String, Object>) schemas.get("PageStaffView");
        var pageProperties = (Map<String, Object>) pageSchema.get("properties");
        var array = (Map<String, Object>) pageProperties.get("items");
        assertThat((Map<String, Object>) array.get("items")).containsEntry("$ref", "#/components/schemas/StaffView");
    }

    @Test
    void promotionRequiresFreshMfaAndLastOwnerCannotBeRemoved() {
        String o = token(StaffRole.OWNER);
        Map<String, Object> manager = tokens(StaffRole.MANAGER);
        StaffAccount m = accounts.get(StaffRole.MANAGER);
        status(call(alpha, "DELETE", "/staff/" + accounts.get(StaffRole.OWNER).id, null, o), 409);
        status(
                call(
                        alpha,
                        "PATCH",
                        "/staff/" + accounts.get(StaffRole.OWNER).id,
                        Map.of("version", 0, "role", "MANAGER"),
                        o),
                409);
        status(call(alpha, "PATCH", "/staff/" + m.id, Map.of("version", 0, "role", "OWNER"), o), 200);
        status(call(alpha, "GET", "/me", null, (String) manager.get("accessToken")), 401);
        assertThat(body(signIn(alpha, StaffRole.MANAGER)).get("state")).isEqualTo("enroll_mfa");
    }

    @Test
    void concurrentOwnerRemovalDemotionPreservesOneOwner() throws Exception {
        StaffAccount second = accounts.get(StaffRole.MANAGER);
        owner.update(
                "UPDATE identity_staff SET role='OWNER',mfa_secret=? WHERE id=?",
                crypto.encrypt("totp:" + alpha.id() + ":" + second.id, crypto.newTotpSecret()),
                second.id);
        String o = token(StaffRole.OWNER);
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> a = pool.submit(() -> {
                start.await();
                return call(alpha, "DELETE", "/staff/" + second.id, null, o).status();
            });
            Future<Integer> b = pool.submit(() -> {
                start.await();
                return call(
                                alpha,
                                "PATCH",
                                "/staff/" + accounts.get(StaffRole.OWNER).id,
                                Map.of("version", 0, "role", "MANAGER"),
                                o)
                        .status();
            });
            start.countDown();
            List<Integer> outcomes = List.of(a.get(), b.get());
            assertThat(outcomes.stream().filter(v -> v == 200 || v == 204).count())
                    .isEqualTo(1);
            assertThat(outcomes)
                    .allMatch(v -> v == 200 || v == 204 || v == 403 || v == 409); // whichever succeeds first leaves one
            // Owner
        } finally {
            pool.close();
        }
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM identity_staff WHERE tenant_id=? AND" + " active=true AND role='OWNER'",
                        Long.class,
                        alpha.id()))
                .isEqualTo(1);
    }

    @Test
    void passwordChangeRotatesCurrentCredentialsAndRevokesOthers() {
        Map<String, Object> first = tokens(StaffRole.MANAGER), second = tokens(StaffRole.MANAGER);
        String next = UUID.randomUUID() + " next";
        status(
                call(
                        alpha,
                        "PUT",
                        "/staff/password",
                        Map.of("oldPassword", UUID.randomUUID().toString(), "newPassword", next),
                        (String) first.get("accessToken")),
                401);
        status(
                call(alpha, "PUT", "/staff/password", Map.of("oldPassword", password, "newPassword", "short"), (String)
                        first.get("accessToken")),
                400);
        Http.Exchange changed =
                call(alpha, "PUT", "/staff/password", Map.of("oldPassword", password, "newPassword", next), (String)
                        first.get("accessToken"));
        status(changed, 200);
        status(call(alpha, "GET", "/me", null, (String) first.get("accessToken")), 401);
        status(call(alpha, "GET", "/me", null, (String) second.get("accessToken")), 401);
        status(call(alpha, "GET", "/me", null, (String) body(changed).get("accessToken")), 200);
        status(signIn(alpha, StaffRole.MANAGER), 401);
    }

    @Test
    void auditsAreImmutablePaginatedIsolatedAndContainNonsecretAccessChanges() {
        String o = token(StaffRole.OWNER);
        StaffAccount pro = accounts.get(StaffRole.PROFESSIONAL);
        status(call(alpha, "PATCH", "/staff/" + pro.id, Map.of("version", 0, "professionalGrant", true), o), 200);
        Http.Exchange audits = call(alpha, "GET", "/audit?subjectId=" + pro.id, null, o);
        status(audits, 200);
        assertThat(audits.body())
                .contains("professionalGrant", "false", "true")
                .doesNotContain(password, pro.mobile, pro.email, "passwordHash", "mfaSecret");
        assertThat(((List<?>) body(call(alpha, "GET", "/audit?subjectId=" + UUID.randomUUID(), null, o))
                        .get("items")))
                .isEmpty();
        assertThatThrownBy(() -> owner.update("UPDATE audit_entry SET action='changed' WHERE tenant_id=?", alpha.id()))
                .hasStackTraceContaining("an audit entry is never changed");
    }

    private List<Integer> race(Callable<Integer> operation) throws Exception {
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch latch = new CountDownLatch(1);
            Future<Integer> a = pool.submit(() -> {
                latch.await();
                return operation.call();
            });
            Future<Integer> b = pool.submit(() -> {
                latch.await();
                return operation.call();
            });
            latch.countDown();
            return List.of(a.get(), b.get());
        }
    }

    private List<Http.Exchange> raceResults(Callable<Http.Exchange> operation) throws Exception {
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch latch = new CountDownLatch(1);
            Future<Http.Exchange> a = pool.submit(() -> {
                latch.await();
                return operation.call();
            });
            Future<Http.Exchange> b = pool.submit(() -> {
                latch.await();
                return operation.call();
            });
            latch.countDown();
            return List.of(a.get(), b.get());
        }
    }
}
