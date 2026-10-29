package com.salonplatform.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.identity.domain.StaffAccount;
import com.salonplatform.identity.domain.StaffRole;
import com.salonplatform.identity.infrastructure.IdentityCrypto;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.testing.Http;
import com.salonplatform.testing.TestSalons;
import com.salonplatform.testing.TestSalons.TestSalon;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Billing fixtures over real HTTP and PostgreSQL: plans and salons through the operator API, unbilled
 * legacy salons and their staff written the way the local fixtures write them, and staff tokens
 * through the real sign-in (Owners with a known authenticator).
 */
final class BillingTestSupport {

    final OperatorTestSupport console;
    private String admin;
    private Instant adminSince;
    private final Http http;
    private final int port;
    private final JdbcTemplate owner;
    private final TestSalons salons;
    private final IdentityCrypto identity;
    private final JsonMapper json;
    private final PlatformTransactionManager manager;
    private final EntityManager em;
    private final Clock clock;
    private final Map<UUID, String> totp = new HashMap<>();
    final String password = "synthetic staff " + UUID.randomUUID();

    BillingTestSupport(
            OperatorTestSupport console,
            Http http,
            int port,
            JdbcTemplate owner,
            IdentityCrypto identity,
            JsonMapper json,
            PlatformTransactionManager manager,
            EntityManager em,
            Clock clock) {
        this.console = console;
        this.http = http;
        this.port = port;
        this.owner = owner;
        this.salons = new TestSalons(owner);
        this.identity = identity;
        this.json = json;
        this.manager = manager;
        this.em = em;
        this.clock = clock;
    }

    /** An Admin's access token, signed in again whenever the clock has moved past its five minutes. */
    String admin() {
        Instant now = clock.instant();
        if (admin == null || now.isBefore(adminSince) || !now.isBefore(adminSince.plusSeconds(240))) {
            admin = console.token(console.create("OPERATOR_ADMIN"));
            adminSince = now;
        }
        return admin;
    }

    Map<String, Object> body(Http.Exchange exchange) {
        return console.body(exchange);
    }

    static void status(Http.Exchange exchange, int expected) {
        assertThat(exchange.status())
                .withFailMessage("%s %s", exchange.status(), exchange.body())
                .isEqualTo(expected);
    }

    /** A fresh admin token (after the clock has moved past a session's life). */
    String freshAdmin() {
        return console.token(console.create("OPERATOR_ADMIN"));
    }

    String plan(int staffSeats) {
        Map<String, Object> plan = OperatorTestSupport.plan(OperatorTestSupport.uniqueKey("bill"));
        plan.put("staffSeats", staffSeats);
        Http.Exchange created = console.callOn(
                console.host(),
                "POST",
                "/plans",
                plan,
                admin(),
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        status(created, 201);
        return (String) body(created).get("id");
    }

    /** A salon created by the operator API with a subscription; returns its detail. */
    Map<String, Object> provisioned(String planId, long amount, String method, String timeZone) {
        String slug = OperatorTestSupport.uniqueKey("bl");
        Map<String, Object> request = OperatorTestSupport.salon(
                slug, planId, "owner-" + slug + "@example.test", "synthetic owner " + UUID.randomUUID());
        request.put("timeZone", timeZone);
        request.put("subscription", Map.of("monthlyAmount", amount, "paymentMethod", method));
        Http.Exchange created = console.callOn(
                console.host(),
                "POST",
                "/salons",
                request,
                admin(),
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        status(created, 201);
        Http.Exchange detail = console.call("GET", "/salons/" + body(created).get("id"), null, admin());
        status(detail, 200);
        return body(detail);
    }

    /** An unbilled legacy salon (no subscription, no currency, no Owner contact) on a plan. */
    TestSalon legacy(String planId) {
        TestSalon salon = salons.create("Billing legacy", "#334455");
        if (planId != null) {
            owner.update("UPDATE tenant SET plan_id = ?::uuid WHERE id = ?", planId, salon.id());
        }
        return salon;
    }

    TestSalon testSalon(UUID id) {
        String slug = owner.queryForObject("SELECT slug FROM tenant WHERE id = ?", String.class, id);
        return new TestSalon(id, slug, null, null);
    }

    Http.Exchange startSubscription(UUID salon, Map<String, Object> request, String key) {
        return console.callOn(
                console.host(),
                "POST",
                "/salons/" + salon + "/subscription",
                request,
                admin(),
                Map.of("Idempotency-Key", key));
    }

    /** A staff account of the salon, written as the identity fixtures write it. */
    StaffAccount staff(TestSalon salon, StaffRole role, String email) throws Exception {
        return TenantContext.callAs(
                salons.asCurrent(salon),
                () -> new TransactionTemplate(manager).execute(status -> {
                    StaffAccount a = StaffAccount.create(
                            email, null, identity.encodePassword(password), role, false, clock.instant());
                    if (role == StaffRole.OWNER) {
                        String secret = identity.newTotpSecret();
                        totp.put(a.getId(), secret);
                        a.enrollMfa(identity.encrypt("totp:" + salon.id() + ":" + a.getId(), secret), a.getCreatedAt());
                    }
                    em.persist(a);
                    return a;
                }));
    }

    Http.Exchange identityCall(TestSalon salon, String method, String path, Object request, String token) {
        return salonCall(salon, method, "/api/v1/identity" + path, request, token);
    }

    Http.Exchange salonCall(TestSalon salon, String method, String path, Object request, String token) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        if (token != null) {
            headers.put("Authorization", "Bearer " + token);
        }
        return http.sendOnHost(
                method, salon.host(port), path, headers, request == null ? null : json.writeValueAsString(request));
    }

    private final Map<UUID, java.util.Set<Long>> used = new HashMap<>();

    /** The staff member's access token through the real sign-in (with MFA for an Owner). */
    // JSON is read as an untyped map (Map.class); the cast states the shape the assertions then check.
    @SuppressWarnings("unchecked")
    Map<String, Object> tokens(TestSalon salon, StaffAccount account) {
        Http.Exchange sign = identityCall(
                salon,
                "POST",
                "/staff/sign-in",
                Map.of("identifier", account.getEmail(), "password", password, "deviceLabel", "JUnit billing"),
                null);
        status(sign, 200);
        Map<String, Object> result = body(sign);
        if (result.get("credentials") != null) {
            return (Map<String, Object>) result.get("credentials");
        }
        long current = clock.instant().getEpochSecond() / 30;
        java.util.Set<Long> steps = used.computeIfAbsent(account.getId(), id -> new java.util.HashSet<>());
        long step = java.util.stream.LongStream.of(current, current - 1, current + 1)
                .filter(steps::add)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("every step in the window is used for this Owner"));
        Http.Exchange verify = identityCall(
                salon,
                "POST",
                "/staff/mfa/verify",
                Map.of(
                        "challengeId",
                        result.get("challengeId"),
                        "challengeToken",
                        result.get("challengeToken"),
                        "code",
                        identity.totpCode(totp.get(account.getId()), step)),
                null);
        status(verify, 200);
        return body(verify);
    }

    String token(TestSalon salon, StaffAccount account) {
        return (String) tokens(salon, account).get("accessToken");
    }

    static Map<String, Object> staffRequest(String email, String role, String password) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("email", email);
        request.put("password", password);
        request.put("role", role);
        return request;
    }

    static String email(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8).toLowerCase(Locale.ROOT) + "@example.test";
    }

    Instant now() {
        return clock.instant();
    }
}
