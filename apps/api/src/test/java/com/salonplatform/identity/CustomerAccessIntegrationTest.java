package com.salonplatform.identity;

import static org.assertj.core.api.Assertions.*;

import com.salonplatform.testing.Http;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

// JSON bodies are read as untyped maps (Map.class); each cast states the shape the assertion
// beside it then checks, so a wrong shape fails that assertion with a ClassCastException.
@SuppressWarnings("unchecked")
class CustomerAccessIntegrationTest extends CustomerHttpFixture {
    @Test
    void bothChannelsCaptureEncryptedMessagesAndOnlyPublishChallengeMetadata() throws Exception {
        for (String channel : List.of("sms", "whatsapp")) {
            Requested r = request("alpha", mobile(channel.equals("sms") ? 1 : 2), channel, null);
            assertThat(r.cookie()).startsWith("salon_device=");
            Map<String, Object> capture = captured("alpha", r);
            assertThat(capture.get("channel")).isEqualTo(channel);
            assertThat(capture.keySet())
                    .containsExactlyInAnyOrder("challengeId", "channel", "code", "attempt", "deliveredAt");
            String code = (String) capture.get("code");
            Map<String, Object> row = database.asOwner()
                    .queryForMap(
                            "SELECT encrypted_recipient,code_hash,device_hash,message_id"
                                    + " FROM identity_customer_challenge WHERE id=?",
                            r.id());
            assertThat(row.toString()).doesNotContain(mobile(channel.equals("sms") ? 1 : 2), code, r.cookie());
            String payload = database.asOwner()
                    .queryForObject(
                            "SELECT payload::text FROM outbox_message WHERE id=?", String.class, row.get("message_id"));
            assertThat(payload).doesNotContain(code, mobile(channel.equals("sms") ? 1 : 2));
        }
        Http.Exchange response = call(
                "alpha",
                "POST",
                "/api/v1/identity/customers/challenges",
                Map.of("mobile", mobile(3), "channel", "sms", "deviceLabel", "Browser"),
                null,
                null);
        status(response, 200);
        assertThat(body(response).keySet()).containsExactlyInAnyOrder("challengeId", "expiresAt");
        assertThat(response.headers().get("set-cookie"))
                .contains("HttpOnly", "SameSite=Lax", "Path=/")
                .doesNotContain("Domain=", "Secure");
    }

    @Test
    void invalidInputsNeverDeliverAndHaveFieldErrors() {
        long before = database.asOwner().queryForObject("SELECT count(*) FROM outbox_message", Long.class);
        for (Map<String, Object> input : List.<Map<String, Object>>of(
                Map.of("mobile", "bad", "channel", "sms", "deviceLabel", "Browser"),
                Map.of("mobile", mobile(10), "channel", "email", "deviceLabel", "Browser"),
                Map.of("mobile", mobile(10), "channel", "sms", "deviceLabel", 123))) {
            Http.Exchange response = call("alpha", "POST", "/api/v1/identity/customers/challenges", input, null, null);
            status(response, 400);
            assertThat(response.body()).contains("errors");
        }
        assertThat(database.asOwner().queryForObject("SELECT count(*) FROM outbox_message", Long.class))
                .isEqualTo(before);
    }

    @Test
    void sameNumberIsTwoIndependentSalonsAndReusePreservesCustomer() throws Exception {
        Map<String, Object> a = signIn("alpha", 20), b = signIn("beta", 20);
        Http.Exchange alpha = call("alpha", "GET", "/api/v1/identity/me", null, access(a), null),
                beta = call("beta", "GET", "/api/v1/identity/me", null, access(b), null);
        status(alpha, 200);
        status(beta, 200);
        assertThat(body(alpha).get("id")).isNotEqualTo(body(beta).get("id"));
        assertThat(body(alpha))
                .containsEntry("kind", "customer")
                .containsEntry("role", null)
                .containsEntry("staffId", null)
                .containsEntry("scope", "own");
        assertThat((List<String>) body(alpha).get("permissions"))
                .containsExactlyInAnyOrder("session.manage", "profile.manage", "data.export", "account.delete");
        Map<String, Object> again = signIn("alpha", 20);
        assertThat(body(call("alpha", "GET", "/api/v1/identity/me", null, access(again), null))
                        .get("id"))
                .isEqualTo(body(alpha).get("id"));
        assertThat(Instant.parse((String) a.get("refreshExpiresAt")))
                .isAfter(Instant.now().plusSeconds(29 * 86400));
        status(call("beta", "GET", "/api/v1/identity/me", null, access(a), null), 404);
        status(call(null, "GET", "/api/v1/identity/me", null, access(a), null), 200);
        status(call("console", "GET", "/api/v1/identity/me", null, access(a), null), 404);
    }

    @Test
    void customerAndStaffCannotCrossIdentityActions() throws Exception {
        Map<String, Object> customer = signIn("alpha", 30);
        for (String path : List.of("/staff", "/audit"))
            status(call("alpha", "GET", "/api/v1/identity" + path, null, access(customer), null), 401);
        status(
                call(
                        "alpha",
                        "PUT",
                        "/api/v1/identity/staff/password",
                        Map.of(
                                "oldPassword",
                                UUID.randomUUID().toString(),
                                "newPassword",
                                UUID.randomUUID().toString()),
                        access(customer),
                        null),
                401);
        String staff = staff("RECEPTIONIST");
        status(
                call("alpha", "DELETE", "/api/v1/identity/sessions/" + customer.get("sessionId"), null, staff, null),
                404);
        status(
                call("alpha", "DELETE", "/api/v1/identity/sessions/" + UUID.randomUUID(), null, access(customer), null),
                404);
    }

    @Test
    void wrongDeviceResendWrongGuessesAndConsumeAreFailClosed() throws Exception {
        Requested first = request("alpha", mobile(40), "sms", null);
        String code = (String) captured("alpha", first).get("code");
        status(verify("alpha", first, code, null), 401);
        status(verify("alpha", first, code, "salon_device=forged"), 401);
        status(verify("beta", first, code, first.cookie()), 404);
        Requested resend = request("alpha", mobile(40), "whatsapp", first.cookie());
        status(verify("alpha", first, code, first.cookie()), 401);
        String current = (String) captured("alpha", resend).get("code");
        String wrong = String.format(Locale.ROOT, "%06d", (Integer.parseInt(current) + 1) % 1000000);
        for (int n = 0; n < 5; n++) status(verify("alpha", resend, wrong, resend.cookie()), 401);
        status(verify("alpha", resend, current, resend.cookie()), 401);
        assertThat(database.asOwner()
                        .queryForObject(
                                "SELECT attempts FROM identity_customer_challenge WHERE" + " id=?",
                                Integer.class,
                                resend.id()))
                .isEqualTo(5);
        Requested good = request("alpha", mobile(41), "sms", null);
        String valid = (String) captured("alpha", good).get("code");
        status(verify("alpha", good, valid, good.cookie()), 200);
        status(verify("alpha", good, valid, good.cookie()), 401);
    }

    @Test
    void simultaneousVerificationCreatesOneCustomerAndSession() throws Exception {
        Requested r = request("alpha", mobile(50), "sms", null);
        String code = (String) captured("alpha", r).get("code");
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer>
                    a =
                            pool.submit(() -> {
                                start.await();
                                return verify("alpha", r, code, r.cookie()).status();
                            }),
                    b =
                            pool.submit(() -> {
                                start.await();
                                return verify("alpha", r, code, r.cookie()).status();
                            });
            start.countDown();
            assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(200, 401);
        }
        assertThat(database.asOwner()
                        .queryForObject(
                                "SELECT count(*) FROM identity_customer WHERE mobile=?", Long.class, mobile(50)))
                .isEqualTo(1);
    }

    @Test
    void customersOwnTheirDevicesAndRefreshReplayEndsFamily() throws Exception {
        Map<String, Object> first = signIn("alpha", 60), second = signIn("alpha", 61);
        Http.Exchange sessions = call("alpha", "GET", "/api/v1/identity/sessions?size=1", null, access(first), null);
        status(sessions, 200);
        assertThat((List<?>) body(sessions).get("items")).hasSize(1);
        assertThat(body(sessions).get("total")).isEqualTo(1);
        status(
                call(
                        "alpha",
                        "DELETE",
                        "/api/v1/identity/sessions/" + second.get("sessionId"),
                        null,
                        access(first),
                        null),
                404);
        Object refresh = Map.of("refreshToken", first.get("refreshToken"));
        Http.Exchange renewed = call("alpha", "POST", "/api/v1/identity/sessions/refresh", refresh, null, null);
        status(renewed, 200);
        status(call("alpha", "GET", "/api/v1/identity/me", null, access(first), null), 401);
        status(call("alpha", "POST", "/api/v1/identity/sessions/refresh", refresh, null, null), 401);
        status(call("alpha", "GET", "/api/v1/identity/me", null, access(body(renewed)), null), 401);
        status(call("alpha", "DELETE", "/api/v1/identity/sessions", null, access(second), null), 204);
        status(call("alpha", "GET", "/api/v1/identity/me", null, access(second), null), 401);
    }

    @Test
    void codeAccessAndAbsoluteSessionsExpireWithClock() throws Exception {
        Requested r = request("alpha", mobile(70), "sms", null);
        String code = (String) captured("alpha", r).get("code");
        advance(301);
        Http.Exchange expired = verify("alpha", r, code, r.cookie());
        status(expired, 401);
        assertThat(body(expired).get("code")).isEqualTo("code_expired");
        status(call(null, "DELETE", "/local/clock", null, null, null), 200);
        Map<String, Object> tokens = signIn("alpha", 71);
        advance(301);
        status(call("alpha", "GET", "/api/v1/identity/me", null, access(tokens), null), 401);
        status(
                call(
                        "alpha",
                        "POST",
                        "/api/v1/identity/sessions/refresh",
                        Map.of("refreshToken", tokens.get("refreshToken")),
                        null,
                        null),
                200);
        advance(31 * 86400);
        status(
                call(
                        "alpha",
                        "POST",
                        "/api/v1/identity/sessions/refresh",
                        Map.of("refreshToken", tokens.get("refreshToken")),
                        null,
                        null),
                401);
    }

    @Test
    void numberCeilingHoldsConcurrentlyAndDeniedRequestsDoNotEnqueue() throws Exception {
        Requested initial = request("alpha", mobile(80), "sms", null);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int n = 0; n < 5; n++)
                futures.add(pool.submit(() -> call(
                                "alpha",
                                "POST",
                                "/api/v1/identity/customers/challenges",
                                Map.of(
                                        "mobile",
                                        mobile(80),
                                        "channel",
                                        "sms",
                                        "deviceLabel",
                                        "Browser",
                                        "deviceId",
                                        UUID.randomUUID().toString()),
                                null,
                                "salon_device=forged")
                        .status()));
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : futures) statuses.add(f.get());
            assertThat(statuses.stream().filter(v -> v == 200).count()).isEqualTo(2);
            assertThat(statuses.stream().filter(v -> v == 429).count()).isEqualTo(3);
        }
        assertThat(database.asOwner()
                        .queryForObject(
                                "SELECT attempts FROM identity_rate_bucket WHERE"
                                        + " dimension='customer-number' AND key_hash=(SELECT"
                                        + " number_hash FROM identity_customer_challenge WHERE"
                                        + " id=?)",
                                Integer.class,
                                initial.id()))
                .isEqualTo(3);
        long rateRowsBefore =
                database.asOwner().queryForObject("SELECT count(*) FROM identity_rate_bucket", Long.class);
        Http.Exchange denied = call(
                "alpha",
                "POST",
                "/api/v1/identity/customers/challenges",
                Map.of("mobile", mobile(80), "channel", "sms", "deviceLabel", "Browser"),
                null,
                null);
        status(denied, 429);
        assertThat(denied.headers().get("retry-after")).isEqualTo("900");
        assertThat(database.asOwner().queryForObject("SELECT count(*) FROM identity_rate_bucket", Long.class))
                .isEqualTo(rateRowsBefore);
    }

    @Test
    void retryAfterUsesTheRemainingShortWindow() {
        status(call(null, "PUT", "/local/clock", Map.of("instant", "2026-10-13T12:00:00Z"), null, null), 200);
        for (int n = 0; n < 3; n++) request("alpha", mobile(160), "sms", null);
        status(call(null, "PUT", "/local/clock", Map.of("instant", "2026-10-13T12:14:58Z"), null, null), 200);
        Http.Exchange denied = call(
                "alpha",
                "POST",
                "/api/v1/identity/customers/challenges",
                Map.of("mobile", mobile(160), "channel", "sms", "deviceLabel", "Browser"),
                null,
                null);
        status(denied, 429);
        assertThat(Long.parseLong(denied.headers().get("retry-after"))).isBetween(1L, 3L);
        status(call(null, "PUT", "/local/clock", Map.of("instant", "2026-10-13T12:15:01Z"), null, null), 200);
        request("alpha", mobile(160), "sms", null);
    }

    @Test
    void deviceAndTrustedAddressLimitsCannotBeResetByForwardedHeaders() {
        Requested device = request("alpha", mobile(90), "sms", null);
        for (int n = 1; n < 10; n++) request("alpha", mobile(90 + n), "sms", device.cookie());
        status(
                call(
                        "alpha",
                        "POST",
                        "/api/v1/identity/customers/challenges",
                        Map.of("mobile", mobile(101), "channel", "sms", "deviceLabel", "other"),
                        null,
                        device.cookie()),
                429);
        for (int n = 0; n < 10; n++) request("alpha", mobile(110 + n), "sms", null);
        Http.Exchange refused = app.http()
                .sendOnHost(
                        "POST",
                        "alpha.localhost:" + app.port(),
                        "/api/v1/identity/customers/challenges",
                        Map.of(
                                "Content-Type",
                                "application/json",
                                "X-Forwarded-For",
                                "192.0.2.99",
                                "Cookie",
                                "salon_device=forged"),
                        json.writeValueAsString(Map.of(
                                "mobile",
                                mobile(120),
                                "channel",
                                "sms",
                                "deviceLabel",
                                "Browser",
                                "address",
                                "192.0.2.99")));
        status(refused, 429);
    }

    @Test
    void retryAfterFailureCapturesWithoutAnotherRequestAndExpiredDeliveryCancels() throws Exception {
        status(
                call("alpha", "PUT", "/local/identity/providers/sms", Map.of("failures", 1, "delayMs", 0), null, null),
                204);
        Requested r = request("alpha", mobile(130), "sms", null);
        waitDelivery(r, "PENDING", 1);
        advance(40);
        assertThat(captured("alpha", r).get("attempt")).isEqualTo(2);
        assertThat(database.asOwner()
                        .queryForObject(
                                "SELECT count(*) FROM identity_customer_challenge WHERE" + " id=?", Long.class, r.id()))
                .isEqualTo(1);
        status(call(null, "DELETE", "/local/clock", null, null, null), 200);
        status(
                call(
                        "alpha",
                        "PUT",
                        "/local/identity/providers/whatsapp",
                        Map.of("failures", 0, "delayMs", 1000),
                        null,
                        null),
                204);
        Requested expired = request("alpha", mobile(131), "whatsapp", null);
        waitDelivery(expired, "SENDING", 1);
        advance(301);
        waitDelivery(expired, "CANCELLED", 1);
        status(call("alpha", "GET", "/local/identity/captures/" + expired.id(), null, null, null), 404);
    }

    @Test
    void silentProviderTimesOutThenRecoversThroughOutbox() throws Exception {
        status(
                call(
                        "alpha",
                        "PUT",
                        "/local/identity/providers/sms",
                        Map.of("failures", 0, "delayMs", 12000),
                        null,
                        null),
                204);
        Requested r = request("alpha", mobile(140), "sms", null);
        waitDelivery(r, "PENDING", 1);
        status(
                call("alpha", "PUT", "/local/identity/providers/sms", Map.of("failures", 0, "delayMs", 0), null, null),
                204);
        advance(40);
        assertThat(captured("alpha", r).get("attempt")).isEqualTo(2);
    }

    @Test
    void resetDuringProviderCallDoesNotRecreateCaptureOrIdentity() throws Exception {
        status(
                call(
                        "alpha",
                        "PUT",
                        "/local/identity/providers/sms",
                        Map.of("failures", 0, "delayMs", 700),
                        null,
                        null),
                204);
        Requested r = request("alpha", mobile(150), "sms", null);
        waitDelivery(r, "SENDING", 1);
        status(call("alpha", "DELETE", "/local/identity/customers", null, null, null), 204);
        Thread.sleep(1000);
        status(call("alpha", "GET", "/local/identity/captures/" + r.id(), null, null, null), 404);
        assertThat(database.asOwner().queryForObject("SELECT count(*) FROM identity_customer_challenge", Long.class))
                .isZero();
    }

    private void waitDelivery(Requested r, String status, int attempts) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(14);
        while (System.nanoTime() < deadline) {
            Http.Exchange response = call("alpha", "GET", "/local/identity/deliveries/" + r.id(), null, null, null);
            if (response.status() == 200
                    && status.equals(body(response).get("status"))
                    && ((Number) body(response).get("attempts")).intValue() >= attempts) return;
            Thread.sleep(25);
        }
        throw new AssertionError("Delivery did not reach " + status);
    }
}
