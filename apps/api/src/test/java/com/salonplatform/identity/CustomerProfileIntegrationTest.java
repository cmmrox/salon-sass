package com.salonplatform.identity;

import static org.assertj.core.api.Assertions.*;

import com.salonplatform.identity.local.*;
import com.salonplatform.identity.service.CustomerAccess;
import com.salonplatform.identity.service.IdentityWork;
import com.salonplatform.shared.audit.Actor;
import com.salonplatform.shared.audit.AuditAction;
import com.salonplatform.shared.audit.AuditTrail;
import com.salonplatform.shared.outbox.Outbox;
import com.salonplatform.shared.tenancy.*;
import com.salonplatform.testing.Http;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Personal-data boundaries and privacy effects exercised over real HTTP and PostgreSQL. */
// JSON bodies are read as untyped maps (Map.class); each cast states the shape the assertion
// beside it then checks, so a wrong shape fails that assertion with a ClassCastException.
@SuppressWarnings("unchecked")
class CustomerProfileIntegrationTest extends CustomerHttpFixture {
    @BeforeEach
    void isolateProfileFixtures() {
        // This class owns its disposable database. Reset immutable delivery fixtures separately;
        // the product's reset intentionally retains delivered history and audit.
        for (String salon : List.of("alpha", "beta", "gamma"))
            status(call(salon, "DELETE", "/local/identity", null, null, null), 204);
        database.asOwner().update("DELETE FROM outbox_message WHERE channel IN ('sms','whatsapp')");
    }

    private static final String ME = "/api/v1/identity/customers/me";

    private Map<String, Object> edit(long version) {
        Map<String, Object> b = new HashMap<>();
        b.put("version", version);
        b.put("name", "  Synthetic Customer  ");
        b.put("email", "  COPY@synthetic.example  ");
        b.put("birthday", "2000-02-29");
        b.put("preferredLanguage", "si");
        b.put("notificationPreferences", Map.of("sms", false, "whatsapp", true, "email", false));
        b.put("marketingConsent", true);
        return b;
    }

    private Map<String, Object> profile(String salon, Map<String, Object> token) {
        var r = call(salon, "GET", ME, null, access(token), null);
        status(r, 200);
        return body(r);
    }

    private CurrentTenant tenant(String salon) {
        return new CurrentTenant(
                UUID.fromString(
                        salon.equals("alpha")
                                ? "a1a1a1a1-0000-4000-8000-000000000001"
                                : "b2b2b2b2-0000-4000-8000-000000000002"),
                salon,
                ZoneId.of("Asia/Colombo"));
    }

    @Test
    void ownerAndManagerAuditExposeOnlyProfileVersionsAndChangedFieldNames() throws Exception {
        var customer = signIn("alpha", 231);
        var initial = profile("alpha", customer);
        status(call("alpha", "PUT", ME, edit(0), access(customer), null), 200);
        for (String role : List.of("OWNER", "MANAGER")) {
            String token = staff(role);
            var response =
                    call("alpha", "GET", "/api/v1/identity/audit?subjectId=" + initial.get("id"), null, token, null);
            status(response, 200);
            List<Map<String, Object>> entries =
                    (List<Map<String, Object>>) body(response).get("items");
            var updated = entries.stream()
                    .filter(e -> "identity.profile_updated".equals(e.get("action")))
                    .findFirst()
                    .orElseThrow();
            assertThat((Map<String, Object>) updated.get("before"))
                    .containsExactlyInAnyOrderEntriesOf(Map.of("version", 0));
            assertThat((Map<String, Object>) updated.get("after"))
                    .containsOnlyKeys("version", "changedFields")
                    .containsEntry("version", 1);
            assertThat((List<String>) ((Map<String, Object>) updated.get("after")).get("changedFields"))
                    .containsExactly(
                            "name",
                            "email",
                            "birthday",
                            "preferredLanguage",
                            "notificationPreferences",
                            "marketingConsent");
            assertThat(response.body())
                    .doesNotContain(
                            mobile(231),
                            "Synthetic Customer",
                            "copy@synthetic.example",
                            "2000-02-29",
                            access(customer),
                            (String) customer.get("refreshToken"));
            status(
                    call("beta", "GET", "/api/v1/identity/audit?subjectId=" + initial.get("id"), null, token, null),
                    404);
        }
    }

    @Test
    void profileAuditAllowlistRejectsUnexpectedValuesAndFieldNames() throws Exception {
        String subject = UUID.randomUUID().toString();
        String unexpected = UUID.randomUUID() + "@synthetic.example";
        TenantContext.callAs(
                tenant("alpha"),
                () -> app.bean(IdentityWork.class).run(() -> {
                    app.bean(AuditTrail.class)
                            .record(new AuditAction(
                                    Actor.system("synthetic"),
                                    "identity.profile_updated",
                                    "customer",
                                    subject,
                                    null,
                                    Map.of("version", unexpected, "email", unexpected),
                                    Map.of(
                                            "version",
                                            1L,
                                            "changedFields",
                                            List.of("name", unexpected, 123),
                                            "profile",
                                            Map.of("email", unexpected))));
                    return null;
                }));
        String manager = staff("MANAGER");
        var response = call("alpha", "GET", "/api/v1/identity/audit?subjectId=" + subject, null, manager, null);
        status(response, 200);
        var entry = ((List<Map<String, Object>>) body(response).get("items")).getFirst();
        assertThat((Map<String, Object>) entry.get("before")).isEmpty();
        assertThat((Map<String, Object>) entry.get("after"))
                .containsExactlyInAnyOrderEntriesOf(Map.of("version", 1, "changedFields", List.of("name")));
        assertThat(response.body()).doesNotContain(unexpected);
    }

    @Test
    void emptyProfileHasExplicitDefaultsAndOnlyTheOwnCustomerCanReachIt() throws Exception {
        var a = signIn("alpha", 201);
        var b = signIn("beta", 201);
        var other = signIn("alpha", 202);
        var p = profile("alpha", a);
        assertThat(p)
                .containsEntry("name", "")
                .containsEntry("email", null)
                .containsEntry("birthday", null)
                .containsEntry("preferredLanguage", "en")
                .containsEntry("marketingConsent", false)
                .containsEntry("mobile", mobile(201));
        assertThat((Map<String, Object>) p.get("notificationPreferences"))
                .containsExactlyInAnyOrderEntriesOf(Map.of("sms", true, "whatsapp", true, "email", true));
        assertThat(p.get("id"))
                .isNotEqualTo(profile("alpha", other).get("id"))
                .isNotEqualTo(profile("beta", b).get("id"));
        String manager = staff("MANAGER");
        for (String path : List.of(ME, ME + "/export")) {
            status(call("beta", "GET", path, null, access(a), null), 404);
            status(call("alpha", "GET", path, null, "forged", null), 401);
            status(call("alpha", "GET", path, null, manager, null), 401);
            status(call(null, "GET", path, null, access(a), null), 200);
        }
    }

    @Test
    void updatePersistsNormalizedCompleteRepresentationAndOmittedOptionalsClear() throws Exception {
        var a = signIn("alpha", 203);
        var p = profile("alpha", a);
        var r = call("alpha", "PUT", ME, edit(((Number) p.get("version")).longValue()), access(a), null);
        status(r, 200);
        var changed = body(r);
        assertThat(changed)
                .containsEntry("name", "Synthetic Customer")
                .containsEntry("email", "copy@synthetic.example")
                .containsEntry("birthday", "2000-02-29")
                .containsEntry("marketingConsent", true)
                .containsEntry("preferredLanguage", "si");
        assertThat(((Number) changed.get("version")).longValue()).isEqualTo(1);
        assertThat(profile("alpha", a)).isEqualTo(changed);
        var clear = edit(1);
        clear.remove("email");
        clear.remove("birthday");
        status(call("alpha", "PUT", ME, clear, access(a), null), 200);
        assertThat(profile("alpha", a)).containsEntry("email", null).containsEntry("birthday", null);
    }

    @Test
    void invalidValuesTypesMissingRequiredAndInjectedFieldsLeaveProfileUnchanged() throws Exception {
        var a = signIn("alpha", 204);
        var original = profile("alpha", a);
        List<Map.Entry<String, Object>> bad = List.of(
                Map.entry("name", " "),
                Map.entry("name", "x".repeat(121)),
                Map.entry("email", "invalid"),
                Map.entry("birthday", "2999-01-01"),
                Map.entry("birthday", "2000-02-30"),
                Map.entry("preferredLanguage", "fr"),
                Map.entry("version", "0"),
                Map.entry("name", 123),
                Map.entry("marketingConsent", 1),
                Map.entry("mobile", mobile(205)),
                Map.entry("id", UUID.randomUUID().toString()),
                Map.entry("tenantId", tenant("beta").id().toString()),
                Map.entry(
                        "notificationPreferences",
                        Map.of("sms", true, "whatsapp", false, "email", true, "unknown", true)));
        for (var field : bad) {
            var update = edit(0);
            update.put(field.getKey(), field.getValue());
            var r = call("alpha", "PUT", ME, update, access(a), null);
            status(r, 400);
            assertThat(body(r)).containsEntry("code", "bad_request");
            assertThat((List<?>) body(r).get("errors")).as(field.getKey()).isNotEmpty();
            assertThat(profile("alpha", a)).isEqualTo(original);
        }
        for (String field :
                List.of("version", "name", "preferredLanguage", "notificationPreferences", "marketingConsent")) {
            var update = edit(0);
            update.remove(field);
            status(call("alpha", "PUT", ME, update, access(a), null), 400);
        }
        for (String channel : List.of("sms", "whatsapp", "email")) {
            var update = edit(0);
            Map<String, Object> preferences = new HashMap<>(Map.of("sms", true, "whatsapp", true, "email", true));
            preferences.remove(channel);
            update.put("notificationPreferences", preferences);
            status(call("alpha", "PUT", ME, update, access(a), null), 400);
        }
        assertThat(profile("alpha", a)).isEqualTo(original);
    }

    @Test
    void birthdayUsesTheCurrentSalonDateAcrossUtcMidnight() throws Exception {
        status(call(null, "PUT", "/local/clock", Map.of("instant", "2026-10-13T20:00:00Z"), null, null), 200);
        var alpha = signIn("alpha", 206);
        var gamma = signIn("gamma", 206);
        var update = edit(0);
        update.put("birthday", "2026-10-14");
        status(call("alpha", "PUT", ME, update, access(alpha), null), 200);
        var r = call("gamma", "PUT", ME, update, access(gamma), null);
        status(r, 400);
        assertThat((List<?>) body(r).get("errors")).isNotEmpty();
        assertThat(profile("gamma", gamma)).containsEntry("birthday", null);
    }

    @Test
    void staleAndConcurrentVersionsCannotLoseSuccessfulData() throws Exception {
        var a = signIn("alpha", 207);
        var first = edit(0);
        first.put("name", "First");
        status(call("alpha", "PUT", ME, first, access(a), null), 200);
        status(call("alpha", "PUT", ME, edit(0), access(a), null), 409);
        assertThat(profile("alpha", a)).containsEntry("name", "First");
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Http.Exchange>> jobs = new ArrayList<>();
            for (String name : List.of("Winner A", "Winner B")) {
                jobs.add(pool.submit(() -> {
                    start.await();
                    var update = edit(1);
                    update.put("name", name);
                    return call("alpha", "PUT", ME, update, access(a), null);
                }));
            }
            start.countDown();
            var replies = new ArrayList<Http.Exchange>();
            for (var job : jobs) replies.add(job.get());
            assertThat(replies.stream().map(Http.Exchange::status)).containsExactlyInAnyOrder(200, 409);
            var winner =
                    replies.stream().filter(v -> v.status() == 200).findFirst().orElseThrow();
            assertThat(profile("alpha", a)).isEqualTo(body(winner));
        }
    }

    @Test
    void pooledConcurrentProfileReadsNeverMixSalonOrCustomer() throws Exception {
        var a = signIn("alpha", 208);
        var b = signIn("beta", 208);
        var aId = profile("alpha", a).get("id");
        var bId = profile("beta", b).get("id");
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> jobs = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                boolean alpha = i % 2 == 0;
                jobs.add(pool.submit(() -> assertThat(profile(alpha ? "alpha" : "beta", alpha ? a : b))
                        .containsEntry("id", alpha ? aId : bId)));
            }
            for (var job : jobs) job.get();
        }
    }

    @Test
    void exportIsAnAllowlistedNoStoreDownloadOfOnlyOwnProfileAndDevicesAndAuditsRights() throws Exception {
        var a = signIn("alpha", 209);
        var second = signIn("alpha", 209);
        var other = signIn("alpha", 210);
        var b = signIn("beta", 209);
        status(call("alpha", "PUT", ME, edit(0), access(a), null), 200);
        var r = call("alpha", "GET", ME + "/export", null, access(second), null);
        status(r, 200);
        assertThat(r.headers().get("cache-control")).contains("no-store");
        assertThat(r.headers().get("content-disposition")).isEqualTo("attachment; filename=\"customer-data.json\"");
        var export = body(r);
        assertThat(export.keySet()).containsExactlyInAnyOrder("generatedAt", "profile", "devices");
        assertThat(Instant.parse((String) export.get("generatedAt")))
                .isBefore(Instant.now().plusSeconds(1));
        assertThat(export.get("profile")).isEqualTo(profile("alpha", a));
        var devices = (List<Map<String, Object>>) export.get("devices");
        assertThat(devices).hasSize(2);
        assertThat(devices.stream().map(v -> v.get("id")))
                .containsExactlyInAnyOrder(a.get("sessionId"), second.get("sessionId"));
        for (var device : devices)
            assertThat(device.keySet())
                    .containsExactlyInAnyOrder("id", "deviceLabel", "createdAt", "expiresAt", "current");
        assertThat(r.body())
                .doesNotContain(
                        (String) a.get("accessToken"),
                        (String) a.get("refreshToken"),
                        (String) other.get("sessionId"),
                        (String) b.get("sessionId"),
                        "credential",
                        "codeHash",
                        "mfaSecret",
                        "tenantId",
                        "audit");
        assertThat(database.asOwner()
                        .queryForObject(
                                "SELECT count(*) FROM audit_entry WHERE"
                                        + " action='identity.data_exported' AND subject_id=?",
                                Long.class,
                                profile("alpha", a).get("id")))
                .isEqualTo(1);
    }

    @Test
    void confirmedDeletionErasesPersonalDataAndCredentialsButKeepsCountersAndOtherSalon() throws Exception {
        var a = signIn("alpha", 211);
        var second = signIn("alpha", 211);
        var b = signIn("beta", 211);
        var original = profile("alpha", a);
        var beta = profile("beta", b);
        var owner = database.asOwner();
        status(call("alpha", "PUT", ME, edit(0), access(a), null), 200);
        var refresh = call(
                "alpha",
                "POST",
                "/api/v1/identity/sessions/refresh",
                Map.of("refreshToken", second.get("refreshToken")),
                null,
                null);
        status(refresh, 200);
        second = body(refresh);
        for (Object confirm : List.of(false, "true", 1)) {
            var r = call("alpha", "DELETE", ME, Map.of("confirm", confirm), access(a), null);
            status(r, 400);
            assertThat(profile("alpha", a)).containsEntry("name", "Synthetic Customer");
        }
        status(call("alpha", "DELETE", ME, Map.of("confirm", true), staff("MANAGER"), null), 401);
        status(call("beta", "DELETE", ME, Map.of("confirm", true), access(a), null), 404);
        status(call("alpha", "DELETE", ME, Map.of("confirm", true), access(a), null), 204);
        for (var t : List.of(a, second)) {
            status(call("alpha", "GET", ME, null, access(t), null), 401);
            status(
                    call(
                            "alpha",
                            "POST",
                            "/api/v1/identity/sessions/refresh",
                            Map.of("refreshToken", t.get("refreshToken")),
                            null,
                            null),
                    401);
        }
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM identity_customer WHERE id=?", Long.class, UUID.fromString((String)
                                original.get("id"))))
                .isZero();
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM identity_session WHERE customer_account_id=?",
                        Long.class,
                        UUID.fromString((String) original.get("id"))))
                .isZero();
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM identity_customer_challenge WHERE" + " tenant_id=?",
                        Long.class,
                        tenant("alpha").id()))
                .isZero();
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM outbox_message WHERE tenant_id=? AND channel" + " in ('sms','whatsapp')",
                        Long.class,
                        tenant("alpha").id()))
                .isZero();
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM identity_rate_bucket WHERE tenant_id=? AND"
                                + " dimension like 'customer-%'",
                        Long.class, tenant("alpha").id()))
                .isPositive();
        assertThat(profile("beta", b)).isEqualTo(beta);
        var fresh = signIn("alpha", 211);
        assertThat(profile("alpha", fresh))
                .containsEntry("name", "")
                .containsEntry("email", null)
                .containsEntry("birthday", null)
                .containsEntry("marketingConsent", false);
        assertThat(profile("alpha", fresh).get("id")).isNotEqualTo(original.get("id"));
        status(
                call(
                        "alpha",
                        "POST",
                        "/api/v1/identity/customers/challenges",
                        Map.of("mobile", mobile(211), "channel", "sms", "deviceLabel", "New device"),
                        null,
                        null),
                429);
        var audit = owner.queryForList(
                "SELECT action,actor_id,subject_id,before_values::text,after_values::text"
                        + " FROM audit_entry WHERE subject_id=?",
                original.get("id"));
        String stored = json.writeValueAsString(audit);
        assertThat(stored)
                .contains("identity.profile_updated", "identity.account_deleted", "changedFields", "version")
                .doesNotContain(mobile(211), "Synthetic Customer", "copy@synthetic.example", "2000-02-29");
    }

    @Test
    void deletingAfterExpiredChallengeCleanupStillErasesRetainedDeliveryAndOlderCapture() throws Exception {
        var a = signIn("alpha", 212);
        var other = signIn("alpha", 213);
        var owner = database.asOwner();
        List<UUID> ids = owner.queryForList(
                "SELECT id FROM identity_customer_challenge WHERE tenant_id=? ORDER BY id",
                UUID.class,
                tenant("alpha").id());
        assertThat(ids).hasSize(2);
        Class<?> captureClass = Class.forName("com.salonplatform.identity.local.CustomerMessageCapture");
        Object capture = app.bean(captureClass);
        var field = captureClass.getDeclaredField("captures");
        field.setAccessible(true);
        assertThat((Map<?, ?>) field.get(capture)).hasSize(2);
        advance(301);
        var refreshed = call(
                "alpha",
                "POST",
                "/api/v1/identity/sessions/refresh",
                Map.of("refreshToken", a.get("refreshToken")),
                null,
                null);
        status(refreshed, 200);
        a = body(refreshed);
        var cleanup = app.bean(CustomerAccess.class);
        var work = app.bean(IdentityWork.class);
        TenantContext.callAs(
                tenant("alpha"),
                () -> work.run(() -> {
                    cleanup.cleanup(app.bean(Clock.class).instant());
                    return null;
                }));
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM identity_customer_challenge WHERE" + " tenant_id=?",
                        Long.class,
                        tenant("alpha").id()))
                .isZero();
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM outbox_message WHERE tenant_id=? AND" + " channel='sms'",
                        Long.class,
                        tenant("alpha").id()))
                .isEqualTo(2);
        status(call("alpha", "DELETE", ME, Map.of("confirm", true), access(a), null), 204);
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM outbox_message WHERE tenant_id=? AND" + " channel='sms'",
                        Long.class,
                        tenant("alpha").id()))
                .isEqualTo(1);
        long removedBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (((Map<?, ?>) field.get(capture)).size() != 1 && System.nanoTime() < removedBy) Thread.sleep(20);
        assertThat((Map<?, ?>) field.get(capture)).hasSize(1);
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM identity_customer WHERE tenant_id=?",
                        Long.class,
                        tenant("alpha").id()))
                .isEqualTo(1);
    }

    @Test
    void recognizedCorruptEnvelopeAbortsAllPrivacyEffectsAndOrdinaryOtherPayloadIsPreserved() throws Exception {
        var a = signIn("alpha", 214);
        var p = profile("alpha", a);
        var owner = database.asOwner();
        UUID message = owner.queryForObject(
                "SELECT message_id FROM identity_customer_challenge WHERE tenant_id=?",
                UUID.class,
                tenant("alpha").id());
        UUID challenge = owner.queryForObject(
                "SELECT id FROM identity_customer_challenge WHERE tenant_id=?",
                UUID.class,
                tenant("alpha").id());
        var work = app.bean(IdentityWork.class);
        var outbox = app.bean(Outbox.class);
        UUID ordinary = TenantContext.callAs(
                tenant("alpha"), () -> work.run(() -> outbox.enqueue("sms", Map.of("otherModule", "nonidentity"))));
        String payload =
                owner.queryForObject("SELECT payload::text FROM outbox_message WHERE id=?", String.class, message);
        owner.update(
                "UPDATE outbox_message SET payload=?::jsonb WHERE id=?",
                json.writeValueAsString(Map.of("challengeId", challenge, "encrypted", "invalid")),
                message);
        var failed = call("alpha", "DELETE", ME, Map.of("confirm", true), access(a), null);
        status(failed, 500);
        assertThat(body(failed)).containsEntry("code", "internal_error");
        assertThat(profile("alpha", a)).isEqualTo(p);
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM outbox_message WHERE tenant_id=?",
                        Long.class,
                        tenant("alpha").id()))
                .isEqualTo(2);
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM audit_entry WHERE"
                                + " action='identity.account_deleted' AND subject_id=?",
                        Long.class,
                        p.get("id")))
                .isZero();
        owner.update("UPDATE outbox_message SET payload=?::jsonb WHERE id=?", payload, message);
        status(call("alpha", "DELETE", ME, Map.of("confirm", true), access(a), null), 204);
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM outbox_message WHERE tenant_id=? AND id=?",
                        Long.class,
                        tenant("alpha").id(),
                        ordinary))
                .isEqualTo(1);
    }

    @Test
    void inFlightProviderCannotCaptureOrResurrectAfterDeletionCommits() throws Exception {
        var a = signIn("alpha", 215);
        status(
                call(
                        "alpha",
                        "PUT",
                        "/local/identity/providers/whatsapp",
                        Map.of("failures", 0, "delayMs", 1000),
                        null,
                        null),
                204);
        Requested pending = request("alpha", mobile(215), "whatsapp", null);
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        boolean sending = false;
        while (System.nanoTime() < limit) {
            var view = call("alpha", "GET", "/local/identity/deliveries/" + pending.id(), null, null, null);
            if (view.status() == 200 && "SENDING".equals(body(view).get("status"))) {
                sending = true;
                break;
            }
            Thread.sleep(20);
        }
        assertThat(sending).isTrue();
        status(call("alpha", "DELETE", ME, Map.of("confirm", true), access(a), null), 204);
        Thread.sleep(1200);
        status(call("alpha", "GET", "/local/identity/captures/" + pending.id(), null, null, null), 404);
        status(call("alpha", "GET", "/local/identity/deliveries/" + pending.id(), null, null, null), 404);
        status(verify("alpha", pending, "000000", pending.cookie()), 404);
        assertThat(database.asOwner()
                        .queryForObject(
                                "SELECT count(*) FROM outbox_message WHERE tenant_id=? AND"
                                        + " channel in ('sms','whatsapp')",
                                Long.class,
                                tenant("alpha").id()))
                .isZero();
        assertThat(database.asOwner()
                        .queryForObject(
                                "SELECT count(*) FROM identity_customer WHERE tenant_id=?",
                                Long.class,
                                tenant("alpha").id()))
                .isZero();
    }

    @Test
    void deletionSerializesAgainstValidCodeVerificationAndRefresh() throws Exception {
        var a = signIn("alpha", 216);
        var refreshDevice = signIn("alpha", 216);
        Requested pending = request("alpha", mobile(216), "sms", null);
        String code = (String) captured("alpha", pending).get("code");
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch start = new CountDownLatch(1);
            var delete = pool.submit(() -> {
                start.await();
                return call("alpha", "DELETE", ME, Map.of("confirm", true), access(a), null);
            });
            var verify = pool.submit(() -> {
                start.await();
                return verify("alpha", pending, code, pending.cookie());
            });
            var refresh = pool.submit(() -> {
                start.await();
                return call(
                        "alpha",
                        "POST",
                        "/api/v1/identity/sessions/refresh",
                        Map.of("refreshToken", refreshDevice.get("refreshToken")),
                        null,
                        null);
            });
            start.countDown();
            status(delete.get(), 204);
            var verification = verify.get();
            assertThat(verification.status()).isIn(200, 404);
            var renewal = refresh.get();
            assertThat(renewal.status()).isIn(200, 401);
            for (var response : List.of(verification, renewal))
                if (response.status() == 200) status(call("alpha", "GET", ME, null, access(body(response)), null), 401);
        }
        assertThat(database.asOwner()
                        .queryForObject(
                                "SELECT count(*) FROM identity_customer WHERE tenant_id=?",
                                Long.class,
                                tenant("alpha").id()))
                .isZero();
        assertThat(database.asOwner()
                        .queryForObject(
                                "SELECT count(*) FROM identity_session WHERE tenant_id=?" + " AND kind='customer'",
                                Long.class,
                                tenant("alpha").id()))
                .isZero();
    }

    @Test
    void privacyErasureTraversesBoundedPagesWithoutSkippingDeletedRows() throws Exception {
        var a = signIn("alpha", 217);
        var owner = database.asOwner();
        UUID source = owner.queryForObject(
                "SELECT message_id FROM identity_customer_challenge WHERE tenant_id=?",
                UUID.class,
                tenant("alpha").id());
        for (int i = 0; i < 110; i++)
            owner.update(
                    "INSERT INTO"
                            + " outbox_message(id,tenant_id,channel,payload,status,attempts,next_attempt_at,created_at,updated_at)"
                            + " SELECT"
                            + " ?,tenant_id,channel,payload,'cancelled',0,next_attempt_at,created_at,updated_at"
                            + " FROM outbox_message WHERE id=?",
                    UUID.randomUUID(),
                    source);
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM outbox_message WHERE tenant_id=?",
                        Long.class,
                        tenant("alpha").id()))
                .isEqualTo(111);
        status(call("alpha", "DELETE", ME, Map.of("confirm", true), access(a), null), 204);
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM outbox_message WHERE tenant_id=? AND channel IN ('sms','whatsapp')",
                        Long.class,
                        tenant("alpha").id()))
                .isZero();
    }

    @Test
    void aSameDeviceRefreshCanInvalidateDeletionAuthorityButCannotSurviveSuccessfulDeletion() throws Exception {
        var a = signIn("alpha", 218);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch start = new CountDownLatch(1);
            var deletion = pool.submit(() -> {
                start.await();
                return call("alpha", "DELETE", ME, Map.of("confirm", true), access(a), null);
            });
            var refresh = pool.submit(() -> {
                start.await();
                return call(
                        "alpha",
                        "POST",
                        "/api/v1/identity/sessions/refresh",
                        Map.of("refreshToken", a.get("refreshToken")),
                        null,
                        null);
            });
            start.countDown();
            var deleted = deletion.get();
            var renewed = refresh.get();
            assertThat(deleted.status()).isIn(204, 401);
            if (deleted.status() == 401) {
                status(renewed, 200);
                var token = body(renewed);
                assertThat(profile("alpha", token)).containsEntry("name", "");
                status(call("alpha", "DELETE", ME, Map.of("confirm", true), access(token), null), 204);
            } else if (renewed.status() == 200)
                status(call("alpha", "GET", ME, null, access(body(renewed)), null), 401);
            else status(renewed, 401);
        }
        assertThat(database.asOwner()
                        .queryForObject(
                                "SELECT count(*) FROM identity_customer WHERE tenant_id=?",
                                Long.class,
                                tenant("alpha").id()))
                .isZero();
    }
}
