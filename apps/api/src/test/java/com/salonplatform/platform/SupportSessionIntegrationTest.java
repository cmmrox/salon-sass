package com.salonplatform.platform;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.salonplatform.identity.domain.StaffAccount;
import com.salonplatform.identity.domain.StaffRole;
import com.salonplatform.identity.infrastructure.IdentityCrypto;
import com.salonplatform.platform.application.OperatorAccounts;
import com.salonplatform.platform.application.SupportSessions;
import com.salonplatform.platform.infrastructure.OperatorCrypto;
import com.salonplatform.platform.infrastructure.SupportCrypto;
import com.salonplatform.testing.Http;
import com.salonplatform.testing.IntegrationTest;
import com.salonplatform.testing.MovableClock;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import com.salonplatform.testing.TestSalons.TestSalon;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F03-S04 over real HTTP and PostgreSQL with a movable application clock: starting a session (AC-1),
 * the single-use entry code (AC-2), the read-only support view (AC-3), where the support credential is
 * refused (AC-4), every way a session ends (AC-5), the salon's audit trail (AC-6), the Owner's Support
 * access (AC-7), the console's oversight (AC-8) and separation of simultaneous sessions (AC-9). Every
 * check uses at least two salons: what is done in one leaves the other unchanged.
 */
class SupportSessionIntegrationTest extends IntegrationTest {

    static final MovableClock CLOCK = new MovableClock(Instant.now().truncatedTo(ChronoUnit.SECONDS));

    @TestConfiguration
    static class Overrides {
        @Bean
        @Primary
        Clock movableClock() {
            return CLOCK;
        }
    }

    @Autowired OperatorAccounts accounts;
    @Autowired OperatorCrypto crypto;
    @Autowired IdentityCrypto identityCrypto;
    @Autowired JsonMapper json;
    @Autowired Environment environment;
    @Autowired PlatformTransactionManager manager;
    @Autowired SupportSessions sessions;
    @PersistenceContext EntityManager em;

    private final JdbcTemplate owner = TestDatabase.of(TestPostgres.shared()).asOwner();
    private BillingTestSupport billing;
    private OperatorTestSupport console;

    @BeforeEach
    void setUp() {
        CLOCK.set(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        console = new OperatorTestSupport(http(), port(), accounts, crypto, json, environment, CLOCK);
        billing = new BillingTestSupport(console, http(), port(), owner, identityCrypto, json, manager, em, CLOCK);
    }

    // --- fixtures -----------------------------------------------------------------------------------

    private record Fixture(TestSalon salon, StaffAccount ownerAccount, StaffAccount manager, StaffAccount receptionist) {
    }

    private Fixture fixture() throws Exception {
        TestSalon salon = billing.legacy(null);
        StaffAccount ownerAccount = billing.staff(salon, StaffRole.OWNER, BillingTestSupport.email("own"));
        StaffAccount manager = billing.staff(salon, StaffRole.MANAGER, BillingTestSupport.email("mgr"));
        StaffAccount receptionist = billing.staff(salon, StaffRole.RECEPTIONIST, BillingTestSupport.email("rec"));
        owner.update("UPDATE tenant SET name = ? WHERE id = ?", "Support " + salon.slug(), salon.id());
        return new Fixture(salon, ownerAccount, manager, receptionist);
    }

    private record Support(OperatorTestSupport.Operator operator, String token) {
    }

    private Support support() {
        OperatorTestSupport.Operator operator = console.create("OPERATOR_SUPPORT");
        return new Support(operator, console.token(operator));
    }

    private Http.Exchange start(UUID salon, Object request, String token) {
        // Each start a second later than the last, so lists ordered by start time are deterministic.
        CLOCK.advance(Duration.ofSeconds(1));
        return console.call("POST", "/salons/" + salon + "/support-sessions", request, token);
    }

    private Map<String, Object> started(UUID salon, String token) {
        Http.Exchange created = start(salon, Map.of("reason", "Owner reported a missing staff member", "durationMinutes", 30),
                token);
        BillingTestSupport.status(created, 201);
        return console.body(created);
    }

    private Http.Exchange enter(TestSalon salon, String code) {
        return billing.salonCall(salon, "POST", "/api/v1/support/entry", Map.of("code", code), null);
    }

    private String credential(TestSalon salon, String code) {
        Http.Exchange entered = enter(salon, code);
        BillingTestSupport.status(entered, 200);
        return (String) console.body(entered).get("accessToken");
    }

    private static String code(Map<String, Object> entry) {
        return (String) entry.get("entryCode");
    }

    private static String code(Http.Exchange problem) {
        return problem.body().replaceAll(".*\"code\":\"([a-z_]+)\".*", "$1");
    }

    private static void refused(Http.Exchange exchange, int status, String code) {
        BillingTestSupport.status(exchange, status);
        assertThat(code(exchange)).withFailMessage("%s", exchange.body()).isEqualTo(code);
    }

    private int count(String sql, Object... args) {
        Integer count = owner.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }

    private List<Map<String, Object>> salonAudit(TestSalon salon) {
        return owner.queryForList("""
                SELECT actor_kind, actor_id, action, subject_id, reason, after_values::text AS after_values
                  FROM audit_entry WHERE tenant_id = ? AND action LIKE 'support.%' ORDER BY created_at, id
                """, salon.id());
    }

    // --- AC-1 -----------------------------------------------------------------------------------------

    @Test
    void supportStartsASessionWithAReasonAndADurationAndFieldErrorsOtherwise() throws Exception {
        Fixture fixture = fixture();
        Support support = support();
        UUID salon = fixture.salon().id();

        refused(start(salon, Map.of("reason", "too short", "durationMinutes", 30), support.token()), 400, "bad_request");
        assertThat(start(salon, Map.of("reason", "too short"), support.token()).body()).contains("\"field\":\"reason\"");
        for (Object minutes : List.of(4, 121, "30", 30.5)) {
            Http.Exchange bad = start(salon, Map.of("reason", "A reason long enough", "durationMinutes", minutes),
                    support.token());
            refused(bad, 400, "bad_request");
            assertThat(bad.body()).contains("\"field\":\"durationMinutes\"");
        }
        Http.Exchange unknown = start(salon, Map.of("reason", "A reason long enough", "salonId", UUID.randomUUID()),
                support.token());
        refused(unknown, 400, "bad_request");
        assertThat(count("SELECT count(*) FROM support_session WHERE salon_id = ?", salon)).isZero();

        Http.Exchange created = start(salon, Map.of("reason", "  Owner asked for help with staff  "), support.token());
        BillingTestSupport.status(created, 201);
        Map<String, Object> entry = console.body(created);
        assertThat(Instant.parse((String) entry.get("expiresAt"))).isEqualTo(CLOCK.instant().plus(Duration.ofMinutes(30)));
        assertThat(Instant.parse((String) entry.get("codeExpiresAt"))).isEqualTo(CLOCK.instant().plusSeconds(60));
        assertThat(code(entry)).matches("[A-Za-z0-9_-]{43}");
        assertThat((String) entry.get("entryUrl")).isEqualTo(
                "https://" + fixture.salon().slug() + ".localhost/workspace/support#" + code(entry));
        assertThat(owner.queryForObject("SELECT reason FROM support_session WHERE id = ?::uuid", String.class,
                entry.get("id"))).isEqualTo("Owner asked for help with staff");
        // Only the code's hash is stored.
        assertThat(count("SELECT count(*) FROM support_entry_code WHERE code_hash = ?", SupportCrypto.codeHash(code(entry))))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM support_entry_code WHERE code_hash = ?", code(entry))).isZero();

        // One open session per operator and salon.
        refused(start(salon, Map.of("reason", "Another look at the staff list"), support.token()), 409,
                "support_session_active");
        // Admin cannot start one.
        refused(start(salon, Map.of("reason", "Admin wants to look around"), billing.admin()), 403, "permission_denied");
        // Unknown salon.
        refused(start(UUID.randomUUID(), Map.of("reason", "Nobody is at this salon"), support.token()), 404, "not_found");
    }

    @Test
    void sessionsAreRefusedForSuspendedOrClosedSalonsAndAllowedForReadOnlyAndPastDueOnes() throws Exception {
        Support support = support();
        TestSalon suspended = billing.legacy(null);
        owner.update("UPDATE tenant SET status = 'suspended' WHERE id = ?", suspended.id());
        refused(start(suspended.id(), Map.of("reason", "Look at a suspended salon"), support.token()), 409, "invalid_state");
        TestSalon closed = billing.legacy(null);
        Instant now = CLOCK.instant().truncatedTo(ChronoUnit.MILLIS);
        owner.update("UPDATE tenant SET status = 'closed', closed_at = ?, delete_at = ? WHERE id = ?",
                java.sql.Timestamp.from(now), java.sql.Timestamp.from(now.plus(Duration.ofDays(30))), closed.id());
        refused(start(closed.id(), Map.of("reason", "Look at a closed salon"), support.token()), 409, "invalid_state");
        assertThat(count("SELECT count(*) FROM support_session WHERE salon_id IN (?, ?)", suspended.id(), closed.id()))
                .isZero();

        TestSalon readOnly = billing.legacy(null);
        owner.update("UPDATE tenant SET access_state = 'read_only' WHERE id = ?", readOnly.id());
        Map<String, Object> entry = started(readOnly.id(), support.token());
        String token = credential(readOnly, code(entry));
        BillingTestSupport.status(billing.identityCall(readOnly, "GET", "/me", null, token), 200);
        // A mutation is refused as a support one, before the salon's read-only rule.
        refused(billing.identityCall(readOnly, "POST", "/staff",
                BillingTestSupport.staffRequest(BillingTestSupport.email("ro"), "RECEPTIONIST", billing.password), token),
                403, "support_read_only");
        BillingTestSupport.status(billing.salonCall(readOnly, "DELETE", "/api/v1/support/session", null, token), 204);
    }

    @Test
    void simultaneousStartsByOneOperatorYieldOneSession() throws Exception {
        Fixture fixture = fixture();
        Support support = support();
        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                results.add(pool.submit(() -> start(fixture.salon().id(),
                        Map.of("reason", "Simultaneous start attempt"), support.token()).status()));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
            assertThat(statuses).filteredOn(status -> status == 201).hasSize(1);
            assertThat(statuses).filteredOn(status -> status == 409).hasSize(4);
        }
        finally {
            pool.shutdownNow();
        }
    }

    // --- AC-2 -----------------------------------------------------------------------------------------

    @Test
    void anEntryCodeOpensItsSalonOnceWithinSixtySecondsAndNothingElse() throws Exception {
        Fixture fixture = fixture();
        Fixture other = fixture();
        Support support = support();
        Map<String, Object> entry = started(fixture.salon().id(), support.token());

        // Another salon's host, the console host and the bare platform host issue nothing.
        refused(enter(other.salon(), code(entry)), 401, "invalid_code");
        refused(http().sendOnHost("POST", console.host(), "/api/v1/support/entry", Map.of("Content-Type",
                "application/json"), json.writeValueAsString(Map.of("code", code(entry)))), 404, "not_found");
        refused(http().sendOnHost("POST", "localhost:" + port(), "/api/v1/support/entry", Map.of("Content-Type",
                "application/json"), json.writeValueAsString(Map.of("code", code(entry)))), 404, "not_found");
        // Altered and malformed codes.
        String altered = (code(entry).charAt(0) == 'A' ? "B" : "A") + code(entry).substring(1);
        refused(enter(fixture.salon(), altered), 401, "invalid_code");
        refused(enter(fixture.salon(), "short"), 401, "invalid_code");

        Http.Exchange entered = enter(fixture.salon(), code(entry));
        BillingTestSupport.status(entered, 200);
        assertThat(entered.headers().get("cache-control")).contains("no-store");
        Map<String, Object> credential = console.body(entered);
        assertThat(credential.keySet()).containsExactlyInAnyOrder("accessToken", "sessionId", "issuedAt", "expiresAt");
        assertThat(credential.get("sessionId")).isEqualTo(entry.get("id"));
        assertThat(credential.get("expiresAt")).isEqualTo(entry.get("expiresAt"));
        // Reused.
        refused(enter(fixture.salon(), code(entry)), 401, "invalid_code");

        // A fresh code expires after 60 seconds.
        Http.Exchange fresh = console.call("POST", "/support-sessions/" + entry.get("id") + "/entry-codes", null,
                support.token());
        BillingTestSupport.status(fresh, 201);
        CLOCK.advance(Duration.ofSeconds(60));
        refused(enter(fixture.salon(), code(console.body(fresh))), 401, "invalid_code");
        Http.Exchange inTime = console.call("POST", "/support-sessions/" + entry.get("id") + "/entry-codes", null,
                support.token());
        CLOCK.advance(Duration.ofSeconds(59));
        BillingTestSupport.status(enter(fixture.salon(), code(console.body(inTime))), 200);
        // Another operator cannot mint a code for this session.
        refused(console.call("POST", "/support-sessions/" + entry.get("id") + "/entry-codes", null, support().token()),
                404, "not_found");
    }

    @Test
    void simultaneousExchangesOfOneCodeIssueOneCredential() throws Exception {
        Fixture fixture = fixture();
        Map<String, Object> entry = started(fixture.salon().id(), support().token());
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                calls.add(() -> enter(fixture.salon(), code(entry)).status());
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : pool.invokeAll(calls)) {
                statuses.add(result.get());
            }
            assertThat(statuses).filteredOn(status -> status == 200).hasSize(1);
            assertThat(statuses).filteredOn(status -> status == 401).hasSize(5);
        }
        finally {
            pool.shutdownNow();
        }
    }

    // --- AC-3 and AC-4 ----------------------------------------------------------------------------------

    @Test
    void theSupportViewReadsWhatTheOwnerReadsAndChangesNothing() throws Exception {
        Fixture fixture = fixture();
        TestSalon salon = fixture.salon();
        Map<String, Object> entry = started(salon.id(), support().token());
        String token = credential(salon, code(entry));

        Http.Exchange me = billing.identityCall(salon, "GET", "/me", null, token);
        BillingTestSupport.status(me, 200);
        Map<String, Object> principal = console.body(me);
        assertThat(principal.get("kind")).isEqualTo("support");
        assertThat(principal.get("expiresAt")).isEqualTo(entry.get("expiresAt"));
        assertThat(principal.get("role")).isNull();
        assertThat(principal.get("staffId")).isNull();
        @SuppressWarnings("unchecked")
        List<String> permissions = (List<String>) principal.get("permissions");
        assertThat(permissions).containsExactlyInAnyOrderElementsOf(StaffRole.SUPPORT_READS);
        assertThat(StaffRole.OWNER.permissions(false)).containsAll(StaffRole.SUPPORT_READS);

        Http.Exchange staff = billing.identityCall(salon, "GET", "/staff", null, token);
        BillingTestSupport.status(staff, 200);
        assertThat(staff.body()).contains(fixture.ownerAccount().email).doesNotContain("passwordHash", "mfaSecret");
        BillingTestSupport.status(billing.identityCall(salon, "GET", "/audit", null, token), 200);
        BillingTestSupport.status(billing.salonCall(salon, "GET", "/api/v1/salon/entitlements", null, token), 200);
        BillingTestSupport.status(billing.salonCall(salon, "GET", "/api/v1/salon/invoices", null, token), 200);
        // Own-account and Owner-only management reads are not the support view's.
        refused(billing.identityCall(salon, "GET", "/sessions", null, token), 403, "permission_denied");
        refused(billing.salonCall(salon, "GET", "/api/v1/salon/support-sessions", null, token), 403, "permission_denied");

        int staffBefore = count("SELECT count(*) FROM identity_staff WHERE tenant_id = ?", salon.id());
        String role = owner.queryForObject("SELECT role FROM identity_staff WHERE id = ?", String.class,
                fixture.manager().id);
        List<Http.Exchange> mutations = List.of(
                billing.identityCall(salon, "POST", "/staff",
                        BillingTestSupport.staffRequest(BillingTestSupport.email("sup"), "RECEPTIONIST", billing.password),
                        token),
                billing.identityCall(salon, "PATCH", "/staff/" + fixture.manager().id,
                        Map.of("version", 0, "role", "RECEPTIONIST"), token),
                billing.identityCall(salon, "DELETE", "/staff/" + fixture.manager().id, null, token),
                billing.identityCall(salon, "DELETE", "/sessions", null, token),
                billing.identityCall(salon, "PUT", "/staff/password",
                        Map.of("oldPassword", billing.password, "newPassword", "another synthetic password"), token),
                billing.identityCall(salon, "POST", "/staff/mfa/challenges", null, token),
                billing.identityCall(salon, "POST", "/sessions/refresh", Map.of("refreshToken", token), token),
                billing.salonCall(salon, "POST", "/api/v1/support/entry", Map.of("code", code(entry)), token),
                billing.salonCall(salon, "DELETE", "/api/v1/salon/support-sessions/" + entry.get("id"), null, token),
                billing.salonCall(salon, "POST", "/api/v1/nothing-here", Map.of(), token));
        for (Http.Exchange mutation : mutations) {
            refused(mutation, 403, "support_read_only");
        }
        assertThat(count("SELECT count(*) FROM identity_staff WHERE tenant_id = ?", salon.id())).isEqualTo(staffBefore);
        assertThat(owner.queryForObject("SELECT role FROM identity_staff WHERE id = ?", String.class,
                fixture.manager().id)).isEqualTo(role);
        assertThat(count("SELECT count(*) FROM support_session WHERE id = ?::uuid AND ended_at IS NULL", entry.get("id")))
                .isEqualTo(1);
        // Nothing the support view reads carries a password, an MFA secret or a token.
        assertThat(me.body()).doesNotContain("password", "mfaSecret", "accessToken", "refreshToken");
    }

    @Test
    void theSupportCredentialIsRefusedEverywhereButItsSalonsWorkspace() throws Exception {
        Fixture fixture = fixture();
        Fixture other = fixture();
        Support support = support();
        Map<String, Object> entry = started(fixture.salon().id(), support.token());
        String token = credential(fixture.salon(), code(entry));

        // Operator endpoints and the console.
        refused(console.call("GET", "/me", null, token), 401, "invalid_session");
        refused(console.call("GET", "/support-sessions", null, token), 401, "invalid_session");
        // Customer endpoints.
        BillingTestSupport.status(billing.identityCall(fixture.salon(), "GET", "/customers/me", null, token), 403);
        // Another salon's host.
        refused(billing.identityCall(other.salon(), "GET", "/me", null, token), 404, "not_found");
        refused(billing.identityCall(other.salon(), "GET", "/staff", null, token), 404, "not_found");
        // The bare API address, which resolves a salon only from a staff or customer token.
        BillingTestSupport.status(http().sendOnHost("GET", "localhost:" + port(), "/api/v1/identity/me",
                Map.of("Authorization", "Bearer " + token), null), 401);
        // An altered credential is no support credential.
        refused(billing.identityCall(fixture.salon(), "GET", "/me", null, token.substring(0, token.length() - 2) + "xx"),
                401, "invalid_session");

        // A salon staff credential cannot start or use support sessions.
        String ownerToken = billing.token(fixture.salon(), fixture.ownerAccount());
        refused(start(fixture.salon().id(), Map.of("reason", "Staff trying to start one"), ownerToken), 401,
                "invalid_session");
        refused(console.call("POST", "/support-sessions/" + entry.get("id") + "/entry-codes", null, ownerToken), 401,
                "invalid_session");
        refused(billing.salonCall(fixture.salon(), "DELETE", "/api/v1/support/session", null, ownerToken), 401,
                "invalid_session");
        // An operator credential alone opens no workspace.
        refused(billing.identityCall(fixture.salon(), "GET", "/me", null, support.token()), 401, "invalid_session");
        refused(billing.identityCall(fixture.salon(), "GET", "/staff", null, billing.admin()), 401, "invalid_session");
    }

    // --- AC-5 -----------------------------------------------------------------------------------------

    @Test
    void everyEndTakesEffectOnTheNextRequestAndIsNeverReopened() throws Exception {
        Fixture fixture = fixture();
        TestSalon salon = fixture.salon();
        String ownerToken = billing.token(salon, fixture.ownerAccount());

        // The operator ends it in the workspace.
        Support first = support();
        Map<String, Object> one = started(salon.id(), first.token());
        String token = credential(salon, code(one));
        BillingTestSupport.status(billing.salonCall(salon, "DELETE", "/api/v1/support/session", null, token), 204);
        refused(billing.identityCall(salon, "GET", "/me", null, token), 401, "invalid_session");
        refused(billing.salonCall(salon, "DELETE", "/api/v1/support/session", null, token), 401, "invalid_session");
        refused(console.call("POST", "/support-sessions/" + one.get("id") + "/entry-codes", null, first.token()), 409,
                "invalid_state");

        // The operator ends it in the console, with an unused code outstanding.
        Map<String, Object> two = started(salon.id(), first.token());
        String second = credential(salon, code(two));
        Map<String, Object> spare = console.body(console.call("POST", "/support-sessions/" + two.get("id")
                + "/entry-codes", null, first.token()));
        BillingTestSupport.status(console.call("DELETE", "/support-sessions/" + two.get("id"), null, first.token()), 204);
        refused(billing.identityCall(salon, "GET", "/staff", null, second), 401, "invalid_session");
        refused(enter(salon, code(spare)), 401, "invalid_code");
        refused(console.call("DELETE", "/support-sessions/" + two.get("id"), null, first.token()), 409, "invalid_state");

        // An Admin revokes it.
        Map<String, Object> three = started(salon.id(), first.token());
        String third = credential(salon, code(three));
        // Another Support operator cannot end it.
        refused(console.call("DELETE", "/support-sessions/" + three.get("id"), null, support().token()), 404, "not_found");
        BillingTestSupport.status(console.call("DELETE", "/support-sessions/" + three.get("id"), null, billing.admin()), 204);
        refused(billing.identityCall(salon, "GET", "/me", null, third), 401, "invalid_session");

        // The Owner revokes it in the workspace.
        Map<String, Object> four = started(salon.id(), first.token());
        String fourth = credential(salon, code(four));
        BillingTestSupport.status(billing.salonCall(salon, "DELETE", "/api/v1/salon/support-sessions/" + four.get("id"),
                null, ownerToken), 204);
        refused(billing.identityCall(salon, "GET", "/me", null, fourth), 401, "invalid_session");
        refused(billing.salonCall(salon, "DELETE", "/api/v1/salon/support-sessions/" + four.get("id"), null, ownerToken),
                409, "invalid_state");

        // It expires.
        Http.Exchange shortOne = start(salon.id(), Map.of("reason", "A short look at the audit", "durationMinutes", 5),
                first.token());
        BillingTestSupport.status(shortOne, 201);
        String fiveId = (String) console.body(shortOne).get("id");
        String fiveCode = code(console.body(shortOne));
        String fifth = credential(salon, fiveCode);
        CLOCK.advance(Duration.ofMinutes(5).minusSeconds(1));
        BillingTestSupport.status(billing.identityCall(salon, "GET", "/me", null, fifth), 200);
        CLOCK.advance(Duration.ofSeconds(1));
        refused(billing.identityCall(salon, "GET", "/me", null, fifth), 401, "invalid_session");
        assertThat(sessions.expire(CLOCK.instant())).isGreaterThanOrEqualTo(1);
        assertThat(sessions.expire(CLOCK.instant())).isZero();
        assertThat(owner.queryForObject("SELECT ended_by FROM support_session WHERE id = ?::uuid", String.class, fiveId))
                .isEqualTo("expiry");

        // How each one ended, and no ended session can be changed by any role.
        assertThat(owner.queryForList("SELECT ended_by FROM support_session WHERE salon_id = ? ORDER BY started_at, id",
                String.class, salon.id())).containsExactly("operator", "operator", "admin", "owner", "expiry");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> owner.update(
                "UPDATE support_session SET ended_at = NULL, ended_by = NULL WHERE id = ?::uuid", fiveId))
                .rootCause().hasMessageContaining("never changed");
    }

    // --- AC-6 and AC-7 ----------------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void theSalonsAuditShowsStartRequestsAndEndToOwnerAndManagerOnly() throws Exception {
        Fixture fixture = fixture();
        Fixture other = fixture();
        TestSalon salon = fixture.salon();
        Support support = support();
        Map<String, Object> entry = started(salon.id(), support.token());
        CLOCK.advance(Duration.ofSeconds(1));
        String token = credential(salon, code(entry));
        CLOCK.advance(Duration.ofSeconds(1));
        BillingTestSupport.status(billing.identityCall(salon, "GET", "/staff", null, token), 200);
        CLOCK.advance(Duration.ofSeconds(1));
        refused(billing.identityCall(salon, "DELETE", "/staff/" + fixture.manager().id, null, token), 403,
                "support_read_only");
        CLOCK.advance(Duration.ofSeconds(1));
        BillingTestSupport.status(billing.salonCall(salon, "DELETE", "/api/v1/support/session", null, token), 204);

        List<Map<String, Object>> rows = salonAudit(salon);
        List<String> actions = rows.stream().map(row -> (String) row.get("action")).toList();
        assertThat(actions.get(0)).isEqualTo("support.session_started");
        assertThat(actions).contains("support.session_entered", "support.request", "support.session_ended");
        // The end is recorded, and so is the request that ended it.
        assertThat(rows).anyMatch(row -> String.valueOf(row.get("after_values")).contains("\"route\": \"/api/v1/support/session\"")
                && String.valueOf(row.get("after_values")).contains("\"status\": 204"));
        Map<String, Object> startRow = rows.get(0);
        assertThat(startRow.get("actor_kind")).isEqualTo("support");
        assertThat(startRow.get("reason")).isEqualTo("Owner reported a missing staff member");
        assertThat((String) startRow.get("after_values")).contains("Synthetic OPERATOR_SUPPORT", entry.get("expiresAt")
                .toString());
        assertThat(rows.stream().map(row -> String.valueOf(row.get("after_values"))))
                .anyMatch(after -> after.contains("\"route\": \"/api/v1/identity/staff\"") && after.contains("\"status\": 200"))
                .anyMatch(after -> after.contains("\"route\": \"/api/v1/identity/staff/{id}\"") && after.contains("\"method\": \"DELETE\"")
                        && after.contains("\"status\": 403"))
                .anyMatch(after -> after.contains("\"endedBy\": \"operator\""));
        // No operator email, raw path id, code or credential in the salon's trail.
        String all = rows.toString();
        assertThat(all).doesNotContain(support.operator().email(), fixture.manager().id.toString(), code(entry), token);
        assertThat(salonAudit(other.salon())).isEmpty();

        // Owner and Manager see the entries; other roles do not; another salon's list has none.
        for (StaffAccount reader : List.of(fixture.ownerAccount(), fixture.manager())) {
            Http.Exchange audit = billing.identityCall(salon, "GET", "/audit?size=100", null, billing.token(salon, reader));
            BillingTestSupport.status(audit, 200);
            List<Map<String, Object>> items = (List<Map<String, Object>>) console.body(audit).get("items");
            assertThat(items).anyMatch(item -> "support.session_started".equals(item.get("action"))
                    && ((Map<String, Object>) item.get("after")).get("operatorName").equals("Synthetic OPERATOR_SUPPORT")
                    && ((Map<String, Object>) item.get("after")).get("reason").equals("Owner reported a missing staff member"));
            assertThat(items).anyMatch(item -> "support.request".equals(item.get("action"))
                    && "/api/v1/identity/staff".equals(((Map<String, Object>) item.get("after")).get("route")));
            assertThat(items).anyMatch(item -> "support.session_ended".equals(item.get("action"))
                    && "operator".equals(((Map<String, Object>) item.get("after")).get("endedBy")));
        }
        refused(billing.identityCall(salon, "GET", "/audit", null, billing.token(salon, fixture.receptionist())), 403,
                "permission_denied");
        Http.Exchange otherAudit = billing.identityCall(other.salon(), "GET", "/audit?size=100", null,
                billing.token(other.salon(), other.ownerAccount()));
        assertThat(otherAudit.body()).doesNotContain("support.");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theOwnerListsAndRevokesTheSalonsSessionsAndNoOtherRoleOrSalonCan() throws Exception {
        Fixture fixture = fixture();
        Fixture other = fixture();
        TestSalon salon = fixture.salon();
        Support support = support();
        Map<String, Object> past = started(salon.id(), support.token());
        BillingTestSupport.status(console.call("DELETE", "/support-sessions/" + past.get("id"), null, support.token()), 204);
        Map<String, Object> active = started(salon.id(), support.token());
        Map<String, Object> elsewhere = started(other.salon().id(), support.token());

        String ownerToken = billing.token(salon, fixture.ownerAccount());
        Http.Exchange list = billing.salonCall(salon, "GET", "/api/v1/salon/support-sessions", null, ownerToken);
        BillingTestSupport.status(list, 200);
        List<Map<String, Object>> items = (List<Map<String, Object>>) console.body(list).get("items");
        assertThat(items).extracting(item -> item.get("id")).containsExactly(active.get("id"), past.get("id"));
        assertThat(items.get(0)).containsEntry("state", "active").containsEntry("operatorName", "Synthetic OPERATOR_SUPPORT")
                .containsEntry("reason", "Owner reported a missing staff member").containsEntry("endedAt", null);
        assertThat(items.get(1)).containsEntry("state", "ended").containsEntry("endedBy", "operator");
        assertThat(list.body()).doesNotContain(support.operator().email(), "operatorId");

        for (StaffAccount role : List.of(fixture.manager(), fixture.receptionist())) {
            String staffToken = billing.token(salon, role);
            refused(billing.salonCall(salon, "GET", "/api/v1/salon/support-sessions", null, staffToken), 403,
                    "permission_denied");
            refused(billing.salonCall(salon, "DELETE", "/api/v1/salon/support-sessions/" + active.get("id"), null,
                    staffToken), 403, "permission_denied");
        }
        // Another salon's session is not found for this Owner, and is untouched.
        refused(billing.salonCall(salon, "DELETE", "/api/v1/salon/support-sessions/" + elsewhere.get("id"), null,
                ownerToken), 404, "not_found");
        refused(billing.salonCall(salon, "DELETE", "/api/v1/salon/support-sessions/not-a-uuid", null, ownerToken), 404,
                "not_found");
        assertThat(count("SELECT count(*) FROM support_session WHERE id = ?::uuid AND ended_at IS NULL",
                elsewhere.get("id"))).isEqualTo(1);
        BillingTestSupport.status(billing.salonCall(salon, "DELETE", "/api/v1/salon/support-sessions/" + active.get("id"),
                null, ownerToken), 204);
        assertThat(salonAudit(salon)).anyMatch(row -> "staff".equals(row.get("actor_kind"))
                && fixture.ownerAccount().id.toString().equals(row.get("actor_id"))
                && String.valueOf(row.get("after_values")).contains("\"endedBy\": \"owner\""));
    }

    // --- AC-8 -----------------------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void supportFindsSalonsAndAdminOverseesEverySessionWithPlatformAudit() throws Exception {
        Fixture fixture = fixture();
        Fixture other = fixture();
        Support first = support();
        Support second = support();
        BillingTestSupport.status(console.call("GET", "/salons?q=" + fixture.salon().slug(), null, first.token()), 200);
        BillingTestSupport.status(console.call("GET", "/salons/" + fixture.salon().id(), null, first.token()), 200);
        refused(console.call("GET", "/plans", null, first.token()), 403, "permission_denied");

        Map<String, Object> a = started(fixture.salon().id(), first.token());
        Map<String, Object> b = started(other.salon().id(), second.token());

        Http.Exchange ownList = console.call("GET", "/support-sessions", null, first.token());
        BillingTestSupport.status(ownList, 200);
        Map<String, Object> own = console.body(ownList);
        assertThat((List<Map<String, Object>>) own.get("items")).extracting(item -> item.get("id"))
                .containsExactly(a.get("id"));
        Http.Exchange all = console.call("GET", "/support-sessions?salonId=" + other.salon().id() + "&state=active", null,
                billing.admin());
        BillingTestSupport.status(all, 200);
        List<Map<String, Object>> items = (List<Map<String, Object>>) console.body(all).get("items");
        assertThat(items).extracting(item -> item.get("id")).containsExactly(b.get("id"));
        Map<String, Object> row = items.get(0);
        assertThat((Map<String, Object>) row.get("salon")).containsEntry("slug", other.salon().slug());
        assertThat((Map<String, Object>) row.get("operator")).containsEntry("displayName", "Synthetic OPERATOR_SUPPORT");
        assertThat(row).containsEntry("reason", "Owner reported a missing staff member").containsEntry("state", "active");
        refused(console.call("GET", "/support-sessions?state=open", null, billing.admin()), 400, "bad_request");
        refused(console.call("GET", "/support-sessions?salonId=nope", null, billing.admin()), 400, "bad_request");

        BillingTestSupport.status(console.call("DELETE", "/support-sessions/" + b.get("id"), null, billing.admin()), 204);
        BillingTestSupport.status(console.call("DELETE", "/support-sessions/" + a.get("id"), null, first.token()), 204);
        List<String> actions = owner.queryForList("""
                SELECT action FROM platform_audit_entry WHERE subject_id IN (?, ?) ORDER BY created_at, id
                """, String.class, a.get("id"), b.get("id"));
        assertThat(actions).contains("support.session_started", "support.session_revoked", "support.session_ended");
        List<String> details = owner.queryForList("SELECT details::text FROM platform_audit_entry WHERE subject_id IN (?, ?)",
                String.class, a.get("id"), b.get("id"));
        assertThat(details.toString()).doesNotContain(first.operator().email(), code(a), code(b));
        assertThat(owner.queryForList("""
                SELECT action || ':' || coalesce(trace_id, 'none') FROM platform_audit_entry WHERE subject_id IN (?, ?)
                """, String.class, a.get("id"), b.get("id"))).allMatch(trace -> trace.matches(".*:[0-9a-f]{32}"));
    }

    // --- AC-9 -----------------------------------------------------------------------------------------

    @Test
    void simultaneousSessionsStaySeparate() throws Exception {
        Fixture fixture = fixture();
        Fixture other = fixture();
        Support first = support();
        Support second = support();
        String a = credential(fixture.salon(), code(started(fixture.salon().id(), first.token())));
        Map<String, Object> bEntry = started(fixture.salon().id(), second.token());
        String b = credential(fixture.salon(), code(bEntry));
        String c = credential(other.salon(), code(started(other.salon().id(), first.token())));

        refused(billing.identityCall(other.salon(), "GET", "/me", null, a), 404, "not_found");
        refused(billing.identityCall(fixture.salon(), "GET", "/me", null, c), 404, "not_found");
        BillingTestSupport.status(console.call("DELETE", "/support-sessions/" + bEntry.get("id"), null, second.token()),
                204);
        refused(billing.identityCall(fixture.salon(), "GET", "/me", null, b), 401, "invalid_session");
        BillingTestSupport.status(billing.identityCall(fixture.salon(), "GET", "/me", null, a), 200);
        BillingTestSupport.status(billing.identityCall(other.salon(), "GET", "/me", null, c), 200);
        BillingTestSupport.status(billing.salonCall(fixture.salon(), "DELETE", "/api/v1/support/session", null, a), 204);
        BillingTestSupport.status(billing.identityCall(other.salon(), "GET", "/me", null, c), 200);
    }

    @Test
    void aDisabledOperatorsSessionStopsAndASuspendedSalonRefusesIt() throws Exception {
        Fixture fixture = fixture();
        OperatorTestSupport.Operator operator = console.create("OPERATOR_SUPPORT");
        String token = credential(fixture.salon(), code(started(fixture.salon().id(), console.token(operator))));
        owner.update("UPDATE tenant SET status = 'suspended' WHERE id = ?", fixture.salon().id());
        refused(billing.identityCall(fixture.salon(), "GET", "/me", null, token), 403, "salon_suspended");
        owner.update("UPDATE tenant SET status = 'active' WHERE id = ?", fixture.salon().id());
        BillingTestSupport.status(billing.identityCall(fixture.salon(), "GET", "/me", null, token), 200);
        owner.update("UPDATE operator_user SET active = false WHERE email = ?", operator.email());
        refused(billing.identityCall(fixture.salon(), "GET", "/me", null, token), 401, "invalid_session");
    }
}
