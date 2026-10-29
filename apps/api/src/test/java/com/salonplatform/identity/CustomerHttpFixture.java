package com.salonplatform.identity;

import static org.assertj.core.api.Assertions.*;

import com.salonplatform.identity.infrastructure.IdentityCrypto;
import com.salonplatform.testing.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;

/** Real local-profile HTTP fixture. Every case begins with isolated synthetic identity state. */
// JSON bodies are read as untyped maps (Map.class); each cast states the shape the assertion
// beside it then checks, so a wrong shape fails that assertion with a ClassCastException.
@SuppressWarnings("unchecked")
abstract class CustomerHttpFixture {
    protected static StartedApplication app;
    protected static TestDatabase database;
    protected static JsonMapper json;

    @BeforeAll
    static void start() {
        database = TestDatabase.createEmpty(TestPostgres.shared());
        app = database.start(
                "--spring.profiles.active=test,local",
                "--spring.flyway.locations=classpath:db/migration,classpath:db/local",
                "--salon.outbox.enabled=true",
                "--salon.outbox.poll-interval=50ms",
                "--salon.jobs.enabled=false");
        json = app.bean(JsonMapper.class);
    }

    @AfterAll
    static void stop() {
        app.close();
    }

    @BeforeEach
    void reset() {
        call("alpha", "DELETE", "/local/clock", null, null, null);
        for (String salon : List.of("alpha", "beta", "gamma")) {
            status(call(salon, "DELETE", "/local/identity/customers", null, null, null), 204);
            for (String channel : List.of("sms", "whatsapp"))
                status(
                        call(
                                salon,
                                "PUT",
                                "/local/identity/providers/" + channel,
                                Map.of("failures", 0, "delayMs", 0),
                                null,
                                null),
                        204);
        }
    }

    protected record Requested(UUID id, String cookie) {}

    protected Http.Exchange call(String salon, String method, String path, Object body, String token, String cookie) {
        String host = (salon == null ? "localhost" : salon + ".localhost") + ":" + app.port();
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        if (token != null) headers.put("Authorization", "Bearer " + token);
        if (cookie != null) headers.put("Cookie", cookie);
        return app.http().sendOnHost(method, host, path, headers, body == null ? null : json.writeValueAsString(body));
    }

    protected Map<String, Object> body(Http.Exchange response) {
        return json.readValue(response.body(), Map.class);
    }

    protected void status(Http.Exchange response, int expected) {
        assertThat(response.status())
                .withFailMessage("HTTP %s: %s", response.status(), response.body())
                .isEqualTo(expected);
    }

    protected String mobile(int n) {
        return "+9477" + String.format(Locale.ROOT, "%07d", n);
    }

    protected Requested request(String salon, String mobile, String channel, String cookie) {
        Http.Exchange response = call(
                salon,
                "POST",
                "/api/v1/identity/customers/challenges",
                Map.of("mobile", mobile, "channel", channel, "deviceLabel", "Synthetic browser"),
                null,
                cookie);
        status(response, 200);
        String received = response.headers().get("set-cookie");
        if (received != null) cookie = received.split(";", 2)[0];
        return new Requested(UUID.fromString((String) body(response).get("challengeId")), cookie);
    }

    protected Map<String, Object> captured(String salon, Requested request) throws Exception {
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime() < limit) {
            Http.Exchange r = call(salon, "GET", "/local/identity/captures/" + request.id(), null, null, null);
            if (r.status() == 200) return body(r);
            assertThat(r.status()).isEqualTo(404);
            Thread.sleep(25);
        }
        throw new AssertionError("Local capture did not arrive");
    }

    protected Http.Exchange verify(String salon, Requested request, String code, String cookie) {
        return call(
                salon,
                "POST",
                "/api/v1/identity/customers/sign-in",
                Map.of("challengeId", request.id(), "code", code),
                null,
                cookie);
    }

    protected Map<String, Object> signIn(String salon, int number) throws Exception {
        Requested r = request(salon, mobile(number), "sms", null);
        Http.Exchange verified = verify(salon, r, (String) captured(salon, r).get("code"), r.cookie());
        status(verified, 200);
        return body(verified);
    }

    protected String access(Map<String, Object> tokens) {
        return (String) tokens.get("accessToken");
    }

    protected void advance(long seconds) {
        status(
                call(
                        null,
                        "PUT",
                        "/local/clock",
                        Map.of("instant", Instant.now().plusSeconds(seconds).toString()),
                        null,
                        null),
                200);
    }

    protected String staff(String role) {
        String password = UUID.randomUUID() + " synthetic",
                secret = app.bean(IdentityCrypto.class).newTotpSecret();
        Map<String, Object> account = new HashMap<>();
        account.put("email", role.toLowerCase(Locale.ROOT) + "@customer-test.example");
        account.put("password", password);
        account.put("role", role);
        if (role.equals("OWNER")) account.put("totpSecret", secret);
        status(call("alpha", "POST", "/local/identity/seed", Map.of("accounts", List.of(account)), null, null), 200);
        Http.Exchange sign = call(
                "alpha",
                "POST",
                "/api/v1/identity/staff/sign-in",
                Map.of("identifier", account.get("email"), "password", password, "deviceLabel", "Staff browser"),
                null,
                null);
        status(sign, 200);
        Map<String, Object> result = body(sign);
        if (result.get("credentials") != null) return access((Map<String, Object>) result.get("credentials"));
        String code =
                app.bean(IdentityCrypto.class).totpCode(secret, Instant.now().getEpochSecond() / 30);
        Http.Exchange verify = call(
                "alpha",
                "POST",
                "/api/v1/identity/staff/mfa/verify",
                Map.of(
                        "challengeId",
                        result.get("challengeId"),
                        "challengeToken",
                        result.get("challengeToken"),
                        "code",
                        code),
                null,
                null);
        status(verify, 200);
        return access(body(verify));
    }
}
