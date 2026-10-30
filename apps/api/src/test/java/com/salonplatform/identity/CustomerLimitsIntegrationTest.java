package com.salonplatform.identity;

import static org.assertj.core.api.Assertions.*;

import com.salonplatform.identity.adapter.IdentityCrypto;
import com.salonplatform.testing.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;

/**
 * Low explicit test ceilings make salon alerts and midnight transitions observable without load.
 */
// JSON bodies are read as untyped maps (Map.class); each cast states the shape the assertion
// beside it then checks, so a wrong shape fails that assertion with a ClassCastException.
@SuppressWarnings("unchecked")
class CustomerLimitsIntegrationTest {
    private static TestDatabase database;
    private static StartedApplication app;
    private static JsonMapper json;
    private static final String RATE_KEY = randomKey();
    private static final String SIGNING_KEY = randomKey();
    private static final String ENCRYPTION_KEY = randomKey();

    private static String randomKey() {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        return java.util.HexFormat.of().formatHex(key);
    }

    @BeforeAll
    static void start() {
        database = TestDatabase.createEmpty(TestPostgres.shared());
        app = startApp();
        json = app.bean(JsonMapper.class);
    }

    private static StartedApplication startApp() {
        return database.start(
                "--spring.profiles.active=test,local",
                "--spring.flyway.locations=classpath:db/migration,classpath:db/local",
                "--salon.outbox.enabled=true",
                "--salon.outbox.poll-interval=50ms",
                "--salon.jobs.enabled=false",
                "--salon.identity.challenge-key=" + RATE_KEY,
                "--salon.identity.signing-key=" + SIGNING_KEY,
                "--salon.identity.encryption-key=" + ENCRYPTION_KEY,
                "--salon.identity.codes.daily-limit=5",
                "--salon.identity.codes.device-limit=100",
                "--salon.identity.codes.address-limit=100");
    }

    @AfterAll
    static void stop() {
        app.close();
    }

    @BeforeEach
    void reset() {
        call("alpha", "DELETE", "/local/identity", null, null);
        call(null, "DELETE", "/local/clock", null, null);
    }

    private Http.Exchange call(String salon, String method, String path, Object body, String token) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        if (token != null) headers.put("Authorization", "Bearer " + token);
        return app.http()
                .sendOnHost(
                        method,
                        (salon == null ? "localhost" : salon + ".localhost") + ":" + app.port(),
                        path,
                        headers,
                        body == null ? null : json.writeValueAsString(body));
    }

    private void status(Http.Exchange r, int expected) {
        assertThat(r.status()).withFailMessage("%s", r.body()).isEqualTo(expected);
    }

    private Map<String, Object> body(Http.Exchange r) {
        return json.readValue(r.body(), Map.class);
    }

    private Http.Exchange request(int number) {
        return call(
                "alpha",
                "POST",
                "/api/v1/identity/customers/challenges",
                Map.of(
                        "mobile",
                        "+9478" + String.format(Locale.ROOT, "%07d", number),
                        "channel",
                        "sms",
                        "deviceLabel",
                        "Budget device"),
                null);
    }

    private String owner() {
        String password = UUID.randomUUID() + " synthetic",
                secret = app.bean(IdentityCrypto.class).newTotpSecret();
        status(
                call(
                        "alpha",
                        "POST",
                        "/local/identity/seed",
                        Map.of(
                                "accounts",
                                List.of(Map.of(
                                        "email",
                                        "owner@budget-test.example",
                                        "password",
                                        password,
                                        "role",
                                        "OWNER",
                                        "totpSecret",
                                        secret))),
                        null),
                200);
        Map<String, Object> c = body(call(
                "alpha",
                "POST",
                "/api/v1/identity/staff/sign-in",
                Map.of("identifier", "owner@budget-test.example", "password", password, "deviceLabel", "Owner device"),
                null));
        String code = app.bean(IdentityCrypto.class)
                .totpCode(secret, app.bean(java.time.Clock.class).instant().getEpochSecond() / 30);
        Http.Exchange verified = call(
                "alpha",
                "POST",
                "/api/v1/identity/staff/mfa/verify",
                Map.of("challengeId", c.get("challengeId"), "challengeToken", c.get("challengeToken"), "code", code),
                null);
        status(verified, 200);
        return (String) body(verified).get("accessToken");
    }

    @Test
    void dailyBudgetIsAtomicAndAlertOnceVisibleOnlyToItsOwner() throws Exception {
        String owner = owner();
        long before = database.asOwner()
                .queryForObject(
                        "SELECT count(*) FROM audit_entry WHERE" + " action='identity.code_volume_alert'", Long.class);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int n = 0; n < 9; n++) {
                int number = n + 100;
                futures.add(pool.submit(() -> request(number).status()));
            }
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> f : futures) codes.add(f.get());
            assertThat(codes.stream().filter(n -> n == 200).count()).isEqualTo(5);
            assertThat(codes.stream().filter(n -> n == 429).count()).isEqualTo(4);
        }
        assertThat(database.asOwner()
                        .queryForObject(
                                "SELECT count(*) FROM audit_entry WHERE" + " action='identity.code_volume_alert'",
                                Long.class))
                .isEqualTo(before + 1);
        assertThat(database.asOwner().queryForObject("SELECT count(*) FROM identity_customer_challenge", Long.class))
                .isEqualTo(5);
        Http.Exchange audits = call("alpha", "GET", "/api/v1/identity/audit?size=100", null, owner);
        status(audits, 200);
        assertThat(audits.body()).contains("identity.code_volume_alert").doesNotContain("+9478");
        status(call("beta", "GET", "/api/v1/identity/audit", null, owner), 404);
        Http.Exchange meter = call(null, "GET", "/actuator/metrics/salon.identity.code.volume.alerts", null, null);
        status(meter, 200);
        assertThat(meter.body()).contains("tenant.id");
    }

    @Test
    void midnightResetsDailyWindowButNotShortNumberWindow() {
        status(call(null, "PUT", "/local/clock", Map.of("instant", "2026-10-13T23:59:50Z"), null), 200);
        for (int n = 0; n < 3; n++) status(request(200), 200);
        status(request(201), 200);
        status(request(202), 200);
        Http.Exchange denied = request(203);
        status(denied, 429);
        assertThat(Long.parseLong(denied.headers().get("retry-after"))).isBetween(9L, 11L);
        status(call(null, "PUT", "/local/clock", Map.of("instant", "2026-10-14T00:00:10Z"), null), 200);
        status(request(200), 429);
        status(request(203), 200);
        assertThat(database.asOwner()
                        .queryForObject(
                                "SELECT attempts FROM identity_rate_bucket WHERE" + " dimension='customer-day'",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void acceptedBudgetAndNumberLimitSurviveApiRestart() {
        for (int n = 0; n < 3; n++) status(request(300), 200);
        app.close();
        app = startApp();
        json = app.bean(JsonMapper.class);
        status(request(300), 429);
        status(request(301), 200);
        status(request(302), 200);
        status(request(303), 429);
    }

    @Test
    void unconfiguredNonlocalRefusesDeliveryAndHasNoCaptureHooks() {
        TestDatabase db = TestDatabase.createEmpty(TestPostgres.shared());
        try (StartedApplication nonlocal = db.start()) {
            TestSalons.TestSalon salon = new TestSalons(db.asOwner()).create("Provider unavailable", "#111111");
            String payload = json.writeValueAsString(
                    Map.of("mobile", "+94771234567", "channel", "sms", "deviceLabel", "Test browser"));
            Http.Exchange request = nonlocal.http()
                    .postJsonOnHost(salon.host(nonlocal.port()), "/api/v1/identity/customers/challenges", payload);
            status(request, 503);
            assertThat(request.body()).contains("provider_unavailable");
            status(
                    nonlocal.http()
                            .getOnHost(
                                    salon.host(nonlocal.port()),
                                    "/local/identity/captures/" + UUID.randomUUID(),
                                    Map.of()),
                    404);
            assertThat(db.asOwner().queryForObject("SELECT count(*) FROM outbox_message", Long.class))
                    .isZero();
        }
    }
}
