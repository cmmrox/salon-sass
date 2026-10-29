package com.salonplatform.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.identity.adapter.IdentityCrypto;
import com.salonplatform.platform.OperatorTestSupport.Operator;
import com.salonplatform.platform.adapter.OperatorCrypto;
import com.salonplatform.platform.service.OperatorAccounts;
import com.salonplatform.testing.Http;
import com.salonplatform.testing.IntegrationTest;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import com.salonplatform.testing.TestSalons;
import com.salonplatform.testing.TestSalons.TestSalon;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * F03-S01 operator access over real HTTP and PostgreSQL: the exact console hostname, mandatory MFA,
 * single-use challenges and steps, session renewal/revocation, persistent sign-in limits and the
 * separation of operator credentials from every salon credential (AC-1, AC-2, AC-4, AC-5, AC-6).
 */
class OperatorAccessIntegrationTest extends IntegrationTest {

    @Autowired
    OperatorAccounts accounts;

    @Autowired
    OperatorCrypto crypto;

    @Autowired
    IdentityCrypto identityCrypto;

    @Autowired
    com.salonplatform.identity.adapter.IdentitySecrets identitySecrets;

    @Autowired
    JsonMapper json;

    @Autowired
    Environment environment;

    @Autowired
    Clock clock;

    private final JdbcTemplate owner = TestDatabase.of(TestPostgres.shared()).asOwner();
    private OperatorTestSupport console;

    @BeforeEach
    void setUp() {
        console = new OperatorTestSupport(http(), port(), accounts, crypto, json, environment, clock);
    }

    private static void status(Http.Exchange response, int expected) {
        assertThat(response.status())
                .withFailMessage("%s %s", response.status(), response.body())
                .isEqualTo(expected);
    }

    private String code(Http.Exchange response) {
        return (String) console.body(response).get("code");
    }

    // --- AC-2: hostname ----------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(
            strings = {
                "localhost",
                "127.0.0.1",
                "admin.localhost",
                "support.localhost",
                "consolex.localhost",
                "console.localhost.example.test",
                "a.console.localhost",
                "console",
                "[::1]"
            })
    void everyOtherHostnameIsNotFoundBeforeAnySideEffectEvenWithAValidCredential(String host) {
        Operator admin = console.create("OPERATOR_ADMIN");
        String token = console.token(admin);
        long buckets = owner.queryForObject("SELECT count(*) FROM operator_rate_bucket", Long.class);
        String target = host.startsWith("[") ? host + ":" + port() : host + ":" + port();

        for (String path : List.of("/me", "/plans", "/salons")) {
            Http.Exchange read = console.callOn(target, "GET", path, null, token, Map.of());
            status(read, 404);
            assertThat(code(read)).isEqualTo("not_found");
            assertThat(read.headers().get("cache-control")).contains("no-store");
        }
        Http.Exchange signIn = console.callOn(
                target,
                "POST",
                "/sign-in",
                Map.of("email", admin.email(), "password", admin.password(), "deviceLabel", "x"),
                null,
                Map.of());
        status(signIn, 404);
        Http.Exchange create = console.callOn(
                target,
                "POST",
                "/plans",
                OperatorTestSupport.plan(OperatorTestSupport.uniqueKey("h")),
                token,
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        status(create, 404);
        assertThat(owner.queryForObject("SELECT count(*) FROM operator_rate_bucket", Long.class))
                .isEqualTo(buckets);
    }

    @Test
    void aSalonHostnameOrAForgedForwardedHostNeverReachesTheConsole() {
        TestSalon salon = new TestSalons(owner).create("Console Probe", "#123456");
        Operator admin = console.create("OPERATOR_ADMIN");
        String token = console.token(admin);
        Map<String, String> forged = Map.of(
                "X-Forwarded-Host", OperatorTestSupport.CONSOLE, "Forwarded", "host=" + OperatorTestSupport.CONSOLE);
        status(console.callOn(salon.host(port()), "GET", "/me", null, token, forged), 404);
        status(console.callOn("localhost:" + port(), "GET", "/me", null, token, forged), 404);
        // The console host itself, any case and with its port, works; the platform and salon still do.
        status(console.callOn("CONSOLE.LOCALHOST:" + port(), "GET", "/me", null, token, Map.of()), 200);
        status(http().getOnHost("localhost:" + port(), "/api/v1/meta"), 200);
        status(http().getOnHost(salon.host(port()), "/api/v1/salon"), 200);
        // A tenant endpoint on the console host resolves no salon.
        status(http().getOnHost(console.host(), "/api/v1/salon"), 404);
    }

    // --- AC-20: local hooks ----------------------------------------------------------------------------

    @Test
    void theOperatorFixtureHooksDoNotExistOutsideTheLocalProfile() {
        for (String[] call : new String[][] {
            {"POST", "/local/operator/seed"},
            {"DELETE", "/local/operator/fixtures"},
            {"PUT", "/local/operator/bootstrap-failure"},
            {"GET", "/local/operator/bootstrap-failure"}
        }) {
            Http.Exchange answer = http().sendOnHost(
                            call[0],
                            "localhost:" + port(),
                            call[1],
                            Map.of("Content-Type", "application/json"),
                            call[0].equals("GET") || call[0].equals("DELETE") ? null : "{}");
            assertThat(answer.status()).as("%s %s", call[0], call[1]).isEqualTo(404);
        }
    }

    // --- AC-1: password then mandatory MFA ---------------------------------------------------------

    @Test
    void aFirstSignInEnrollsMfaBeforeAnySessionExists() {
        Operator fresh = console.create("OPERATOR_ADMIN", false);
        UUID id = owner.queryForObject("SELECT id FROM operator_user WHERE email = ?", UUID.class, fresh.email());

        Http.Exchange password = console.passwordSignIn(fresh.email(), fresh.password(), console.randomAddress());
        status(password, 200);
        Map<String, Object> challenge = console.body(password);
        assertThat(challenge).containsEntry("state", "enroll_mfa").containsEntry("credentials", null);
        assertThat(owner.queryForObject("SELECT count(*) FROM operator_session WHERE user_id = ?", Long.class, id))
                .isZero();

        Http.Exchange setup = console.call(
                "POST",
                "/mfa/challenges",
                Map.of("challengeId", challenge.get("challengeId"), "challengeToken", challenge.get("challengeToken")),
                null);
        status(setup, 200);
        String secret = (String) console.body(setup).get("secret");
        assertThat(secret).matches("[A-Z2-7]{32}");
        assertThat((String) console.body(setup).get("otpauthUri"))
                .startsWith("otpauth://totp/")
                .contains(secret);
        // The stored secret is encrypted, never the plain value.
        assertThat(owner.queryForObject(
                        "SELECT pending_secret FROM operator_mfa_challenge WHERE id = ?",
                        String.class,
                        UUID.fromString((String) challenge.get("challengeId"))))
                .doesNotContain(secret);

        Http.Exchange verified = console.call(
                "POST",
                "/mfa/verify",
                Map.of(
                        "challengeId",
                        challenge.get("challengeId"),
                        "challengeToken",
                        challenge.get("challengeToken"),
                        "code",
                        console.code(secret)),
                null);
        status(verified, 200);
        Map<String, Object> tokens = console.body(verified);
        Http.Exchange me = console.call("GET", "/me", null, (String) tokens.get("accessToken"));
        status(me, 200);
        assertThat(console.body(me))
                .containsEntry("kind", "operator")
                .containsEntry("role", "OPERATOR_ADMIN")
                .containsEntry("mfaEnabled", true)
                .doesNotContainKeys("tenant", "tenantId", "staffId");
        assertThat(owner.queryForObject("SELECT mfa_secret FROM operator_user WHERE id = ?", String.class, id))
                .isNotNull()
                .doesNotContain(secret);
        // An enrolled operator's later sign-in verifies; the setup is never offered again.
        Http.Exchange later = console.passwordSignIn(fresh.email(), fresh.password(), console.randomAddress());
        assertThat(console.body(later)).containsEntry("state", "verify_mfa");
        status(
                console.call(
                        "POST",
                        "/mfa/challenges",
                        Map.of(
                                "challengeId",
                                console.body(later).get("challengeId"),
                                "challengeToken",
                                console.body(later).get("challengeToken")),
                        null),
                401);
    }

    @Test
    void wrongExpiredConsumedReplayedAndExhaustedChallengesIssueNoSession() {
        Operator admin = console.create("OPERATOR_ADMIN");
        UUID id = owner.queryForObject("SELECT id FROM operator_user WHERE email = ?", UUID.class, admin.email());

        // Wrong code: refused, and the attempt is committed despite the 401.
        Map<String, Object> first =
                console.body(console.passwordSignIn(admin.email(), admin.password(), console.randomAddress()));
        UUID firstId = UUID.fromString((String) first.get("challengeId"));
        Http.Exchange wrong = console.call(
                "POST",
                "/mfa/verify",
                Map.of("challengeId", firstId, "challengeToken", first.get("challengeToken"), "code", "000000"),
                null);
        status(wrong, 401);
        assertThat(code(wrong)).isEqualTo("invalid_credentials");
        assertThat(owner.queryForObject(
                        "SELECT attempts FROM operator_mfa_challenge WHERE id = ?", Integer.class, firstId))
                .isEqualTo(1);
        // A wrong challenge token is refused even with the right code.
        status(
                console.call(
                        "POST",
                        "/mfa/verify",
                        Map.of(
                                "challengeId",
                                firstId,
                                "challengeToken",
                                "forged",
                                "code",
                                crypto.totpCode(
                                        admin.totpSecret(), clock.instant().getEpochSecond() / 30)),
                        null),
                401);
        // Exhausted after five attempts: even the right code is refused.
        for (int i = 0; i < 4; i++) {
            console.call(
                    "POST",
                    "/mfa/verify",
                    Map.of("challengeId", firstId, "challengeToken", first.get("challengeToken"), "code", "111111"),
                    null);
        }
        status(
                console.call(
                        "POST",
                        "/mfa/verify",
                        Map.of(
                                "challengeId",
                                firstId,
                                "challengeToken",
                                first.get("challengeToken"),
                                "code",
                                console.code(admin.totpSecret())),
                        null),
                401);

        // Expired: refused.
        Map<String, Object> second =
                console.body(console.passwordSignIn(admin.email(), admin.password(), console.randomAddress()));
        owner.update(
                "UPDATE operator_mfa_challenge SET expires_at = now() - interval '1 second' WHERE id = ?",
                UUID.fromString((String) second.get("challengeId")));
        status(
                console.call(
                        "POST",
                        "/mfa/verify",
                        Map.of(
                                "challengeId",
                                second.get("challengeId"),
                                "challengeToken",
                                second.get("challengeToken"),
                                "code",
                                console.code(admin.totpSecret())),
                        null),
                401);

        // Consumed: a successful challenge cannot be used again; its step cannot be replayed either.
        Map<String, Object> third =
                console.body(console.passwordSignIn(admin.email(), admin.password(), console.randomAddress()));
        String code = console.code(admin.totpSecret());
        status(
                console.call(
                        "POST",
                        "/mfa/verify",
                        Map.of(
                                "challengeId",
                                third.get("challengeId"),
                                "challengeToken",
                                third.get("challengeToken"),
                                "code",
                                code),
                        null),
                200);
        status(
                console.call(
                        "POST",
                        "/mfa/verify",
                        Map.of(
                                "challengeId",
                                third.get("challengeId"),
                                "challengeToken",
                                third.get("challengeToken"),
                                "code",
                                code),
                        null),
                401);
        Map<String, Object> fourth =
                console.body(console.passwordSignIn(admin.email(), admin.password(), console.randomAddress()));
        status(
                console.call(
                        "POST",
                        "/mfa/verify",
                        Map.of(
                                "challengeId",
                                fourth.get("challengeId"),
                                "challengeToken",
                                fourth.get("challengeToken"),
                                "code",
                                code),
                        null),
                401);
        assertThat(owner.queryForObject("SELECT count(*) FROM operator_session WHERE user_id = ?", Long.class, id))
                .isEqualTo(1);
        // Failed MFA attempts are in the platform audit.
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM platform_audit_entry WHERE action = 'operator.mfa_failed' "
                                + "AND actor_id = ?",
                        Long.class,
                        id.toString()))
                .isGreaterThanOrEqualTo(5);
    }

    @Test
    void simultaneousVerificationsOfOneChallengeIssueOneSession() throws Exception {
        Operator admin = console.create("OPERATOR_ADMIN");
        UUID id = owner.queryForObject("SELECT id FROM operator_user WHERE email = ?", UUID.class, admin.email());
        Map<String, Object> challenge =
                console.body(console.passwordSignIn(admin.email(), admin.password(), console.randomAddress()));
        String code = console.code(admin.totpSecret());
        List<Integer> statuses = race(
                8,
                () -> console.call(
                                "POST",
                                "/mfa/verify",
                                Map.of(
                                        "challengeId",
                                        challenge.get("challengeId"),
                                        "challengeToken",
                                        challenge.get("challengeToken"),
                                        "code",
                                        code),
                                null)
                        .status());
        assertThat(statuses).filteredOn(s -> s == 200).hasSize(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM operator_session WHERE user_id = ?", Long.class, id))
                .isEqualTo(1);
    }

    // --- AC-5: sessions ----------------------------------------------------------------------------

    @Test
    void renewalRotatesOnceAndAReplayRevokesTheFamily() {
        Operator admin = console.create("OPERATOR_ADMIN");
        Map<String, Object> tokens = console.signIn(admin);
        Http.Exchange renewed =
                console.call("POST", "/sessions/refresh", Map.of("refreshToken", tokens.get("refreshToken")), null);
        status(renewed, 200);
        Map<String, Object> next = console.body(renewed);
        assertThat(next.get("refreshToken")).isNotEqualTo(tokens.get("refreshToken"));
        status(console.call("GET", "/me", null, (String) next.get("accessToken")), 200);
        // The first access token belongs to an older generation now.
        status(console.call("GET", "/me", null, (String) tokens.get("accessToken")), 401);

        Http.Exchange replay =
                console.call("POST", "/sessions/refresh", Map.of("refreshToken", tokens.get("refreshToken")), null);
        status(replay, 401);
        assertThat(code(replay)).isEqualTo("invalid_session");
        status(console.call("GET", "/me", null, (String) next.get("accessToken")), 401);
        status(console.call("POST", "/sessions/refresh", Map.of("refreshToken", next.get("refreshToken")), null), 401);
    }

    @Test
    void simultaneousRenewalsNeverLeaveTwoUsableFamilies() throws Exception {
        Operator admin = console.create("OPERATOR_ADMIN");
        Map<String, Object> tokens = console.signIn(admin);
        List<Http.Exchange> answers = race(
                6,
                () -> console.call(
                        "POST", "/sessions/refresh", Map.of("refreshToken", tokens.get("refreshToken")), null));
        List<Map<String, Object>> renewed = answers.stream()
                .filter(a -> a.status() == 200)
                .map(console::body)
                .toList();
        assertThat(renewed).hasSizeLessThanOrEqualTo(1);
        long usable = renewed.stream()
                .filter(r ->
                        console.call("POST", "/sessions/refresh", Map.of("refreshToken", r.get("refreshToken")), null)
                                        .status()
                                == 200)
                .count();
        assertThat(usable).isLessThanOrEqualTo(1);
    }

    @Test
    void signOutExpiryRevocationAndDisablingStopEveryFurtherUse() {
        Operator admin = console.create("OPERATOR_ADMIN");
        Map<String, Object> tokens = console.signIn(admin);
        status(console.call("POST", "/sessions/sign-out", null, (String) tokens.get("accessToken")), 204);
        status(console.call("GET", "/me", null, (String) tokens.get("accessToken")), 401);
        status(
                console.call("POST", "/sessions/refresh", Map.of("refreshToken", tokens.get("refreshToken")), null),
                401);

        // Expired access: a correctly signed token past its five minutes is refused.
        Map<String, Object> second = console.signIn(admin);
        UUID operator = owner.queryForObject("SELECT id FROM operator_user WHERE email = ?", UUID.class, admin.email());
        String expired = crypto.access(
                operator,
                UUID.fromString((String) second.get("sessionId")),
                1,
                Instant.now().minusSeconds(400).truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        status(console.call("GET", "/me", null, expired), 401);
        status(console.call("GET", "/me", null, (String) second.get("accessToken")), 200);

        // Disabled account: its live session stops at once, and it cannot sign in.
        owner.update("UPDATE operator_user SET active = false WHERE id = ?", operator);
        status(console.call("GET", "/me", null, (String) second.get("accessToken")), 401);
        status(
                console.call("POST", "/sessions/refresh", Map.of("refreshToken", second.get("refreshToken")), null),
                401);
        Http.Exchange refused = console.passwordSignIn(admin.email(), admin.password(), console.randomAddress());
        status(refused, 401);
        assertThat(code(refused)).isEqualTo("invalid_credentials");

        // An unknown or garbage bearer is invalid_session.
        status(console.call("GET", "/me", null, "not-a-token"), 401);
        status(console.call("GET", "/me", null, null), 401);
    }

    // --- AC-6: persistent limits -------------------------------------------------------------------

    @Test
    void unknownAndKnownEmailsAreLimitedToTenAttemptsWithRetryAfter() {
        for (String email : List.of(
                "nobody-" + UUID.randomUUID() + "@example.test",
                console.create("OPERATOR_SUPPORT").email())) {
            for (int i = 0; i < 10; i++) {
                status(console.passwordSignIn(email, "wrong password value", console.randomAddress()), 401);
            }
            Http.Exchange limited = console.passwordSignIn(email, "wrong password value", console.randomAddress());
            status(limited, 429);
            assertThat(code(limited)).isEqualTo("rate_limited");
            assertThat(Integer.parseInt(limited.headers().get("retry-after"))).isBetween(1, 900);
        }
    }

    @Test
    void oneAddressIsLimitedToThirtyAttemptsAcrossEmails() {
        String address = console.randomAddress();
        for (int i = 0; i < 30; i++) {
            status(
                    console.passwordSignIn(
                            "addr-" + UUID.randomUUID() + "@example.test", "wrong password value", address),
                    401);
        }
        status(
                console.passwordSignIn("addr-" + UUID.randomUUID() + "@example.test", "wrong password value", address),
                429);
        // The counters are rows in the database, so a restart keeps them.
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM operator_rate_bucket WHERE dimension = 'operator-address' "
                                + "AND attempts >= 31",
                        Long.class))
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void simultaneousAttemptsOnOneEmailCountEveryAttempt() throws Exception {
        String email = "race-" + UUID.randomUUID() + "@example.test";
        List<Integer> statuses = race(
                16,
                () -> console.passwordSignIn(email, "wrong password value", console.randomAddress())
                        .status());
        assertThat(statuses).filteredOn(s -> s == 401).hasSize(10);
        assertThat(statuses).filteredOn(s -> s == 429).hasSize(6);
    }

    @Test
    void anInvalidOrStaffAddressAssertionIsRefusedBeforeAnyCounting() throws Exception {
        String email = "assert-" + UUID.randomUUID() + "@example.test";
        long before = owner.queryForObject("SELECT count(*) FROM operator_rate_bucket", Long.class);
        Map<String, Object> request = Map.of("email", email, "password", "wrong password value", "deviceLabel", "x");
        Http.Exchange forged = console.callOn(
                console.host(),
                "POST",
                "/sign-in",
                request,
                null,
                Map.of(
                        "x-salon-client-assertion",
                        "v1;10.0.0.1;" + Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS) + ";"
                                + "0".repeat(64)));
        status(forged, 400);
        assertThat(code(forged)).isEqualTo("invalid_client_assertion");
        // A staff assertion (identity's proxy key, a salon host, the staff path) cannot be replayed here.
        String issued =
                Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(identitySecrets.proxy(), "HmacSHA256"));
        String staff = "v1;10.0.0.2;" + issued + ";"
                + HexFormat.of()
                        .formatHex(mac.doFinal(
                                ("v1\nalpha.localhost\nPOST\n/api/v1/identity/staff/sign-in\n10.0.0.2\n" + issued)
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        status(
                console.callOn(
                        console.host(), "POST", "/sign-in", request, null, Map.of("x-salon-client-assertion", staff)),
                400);
        assertThat(owner.queryForObject("SELECT count(*) FROM operator_rate_bucket", Long.class))
                .isEqualTo(before);
    }

    // --- AC-4: principal separation ----------------------------------------------------------------

    @Test
    void operatorAndSalonCredentialsNeverAuthenticateEachOtherEvenWithMatchingValues() throws Exception {
        Operator admin = console.create("OPERATOR_ADMIN");
        String operatorToken = console.token(admin);
        TestSalon salon = new TestSalons(owner).create("Separation", "#334455");
        // A staff Owner in the salon with the operator's email and password.
        UUID staffId = UUID.randomUUID();
        owner.update("""
                INSERT INTO identity_staff (id, tenant_id, created_at, updated_at, email, password_hash, role, active,
                    professional_grant, version)
                VALUES (?, ?, now(), now(), ?, ?, 'MANAGER', true, false, 0)
                """, staffId, salon.id(), admin.email(), identityCrypto.encodePassword(admin.password()));

        // The operator credential on salon and bare API endpoints: refused.
        Map<String, String> bearer = Map.of("Authorization", "Bearer " + operatorToken);
        Http.Exchange onSalon = http().getOnHost(salon.host(port()), "/api/v1/identity/me", bearer);
        assertThat(onSalon.status()).isIn(401, 404);
        Http.Exchange onBare = http().getOnHost("localhost:" + port(), "/api/v1/identity/me", bearer);
        assertThat(onBare.status()).isIn(401, 404);
        assertThat(http().getOnHost(salon.host(port()), "/api/v1/identity/staff", bearer)
                        .status())
                .isIn(401, 404);

        // The staff credential with the same values signs in as staff only, and is refused here.
        Http.Exchange staffSignIn = http().sendOnHost(
                        "POST",
                        salon.host(port()),
                        "/api/v1/identity/staff/sign-in",
                        Map.of("Content-Type", "application/json"),
                        json.writeValueAsString(
                                Map.of("identifier", admin.email(), "password", admin.password(), "deviceLabel", "x")));
        status(staffSignIn, 200);
        // JSON is read as an untyped map (Map.class); the cast states the shape the assertions then check.
        @SuppressWarnings("unchecked")
        Map<String, Object> staffTokens =
                (Map<String, Object>) console.body(staffSignIn).get("credentials");
        String staffToken = (String) staffTokens.get("accessToken");
        for (String path : List.of("/me", "/plans", "/salons")) {
            Http.Exchange refused = console.call("GET", path, null, staffToken);
            status(refused, 401);
            assertThat(code(refused)).isEqualTo("invalid_session");
        }
        // A correctly signed customer credential is refused as well.
        String customer = identityCrypto.access(salon.id(), UUID.randomUUID(), UUID.randomUUID(), "customer", 1);
        status(console.call("GET", "/me", null, customer), 401);
        // The operator's own access is unaffected.
        status(console.call("GET", "/me", null, operatorToken), 200);
    }

    @Test
    void supportHasItsOwnPrincipalSalonReadsAndSupportSessionsOnly() {
        // F03-S04 grants Support the salon directory and detail (operator.salon.read) and its own support
        // sessions, superseding S01's denial of salon reads; plans stay the Admin's.
        Operator support = console.create("OPERATOR_SUPPORT");
        Map<String, Object> tokens = console.signIn(support);
        String token = (String) tokens.get("accessToken");
        Http.Exchange me = console.call("GET", "/me", null, token);
        status(me, 200);
        assertThat(console.body(me))
                .containsEntry("role", "OPERATOR_SUPPORT")
                .containsEntry(
                        "permissions",
                        List.of("operator.salon.read", "operator.session.manage", "operator.support.start"));
        for (String path : List.of("/plans", "/plans/" + UUID.randomUUID())) {
            Http.Exchange denied = console.call("GET", path, null, token);
            status(denied, 403);
            assertThat(code(denied)).isEqualTo("permission_denied");
        }
        status(console.call("GET", "/salons", null, token), 200);
        status(console.call("GET", "/salons/" + UUID.randomUUID(), null, token), 404);
        status(
                console.call("POST", "/sessions/refresh", Map.of("refreshToken", tokens.get("refreshToken")), null),
                200);
    }

    @Test
    void signInAuditsCarryActorTimeAndTraceButNoCredentialOrEmail() {
        Operator admin = console.create("OPERATOR_ADMIN");
        console.passwordSignIn(admin.email(), "wrong password value", console.randomAddress());
        console.signIn(admin);
        List<Map<String, Object>> entries = owner.queryForList("""
                SELECT actor_kind, actor_id, action, subject_id, details::text AS details, trace_id, created_at
                  FROM platform_audit_entry WHERE created_at > now() - interval '5 minutes'
                """);
        assertThat(entries)
                .extracting(e -> e.get("action"))
                .contains("operator.sign_in_failed", "operator.mfa_challenge_issued", "operator.signed_in");
        assertThat(entries).allSatisfy(entry -> {
            assertThat(String.valueOf(entry.get("trace_id"))).matches("[0-9a-f]{32}|null");
            assertThat(entry.toString()).doesNotContain(admin.email(), admin.password(), admin.totpSecret());
        });
    }

    private static <T> List<T> race(int count, Callable<T> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
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
