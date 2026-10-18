package com.salonplatform.platform;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.salonplatform.identity.domain.StaffAccount;
import com.salonplatform.identity.domain.StaffRole;
import com.salonplatform.identity.infrastructure.IdentityCrypto;
import com.salonplatform.platform.application.BillingJob;
import com.salonplatform.platform.application.LifecycleFaults;
import com.salonplatform.platform.application.OperatorAccounts;
import com.salonplatform.platform.application.SalonDeletion;
import com.salonplatform.platform.application.SalonExports;
import com.salonplatform.platform.infrastructure.ExportKey;
import com.salonplatform.platform.infrastructure.OperatorCrypto;
import com.salonplatform.shared.lifecycle.TenantDataModule;
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
 * F03-S03 over real HTTP and PostgreSQL with a movable application clock: suspension and reactivation
 * (AC-1, AC-2), billing and background work while suspended (AC-3), closure (AC-4), the export and its
 * exactness (AC-5), the Owner's link and the console download (AC-6, AC-7), deletion at the 30-day
 * boundary (AC-8), interrupted, repeated and concurrent jobs (AC-9), refusals (AC-10) and audit
 * hygiene (AC-11). Every check uses two salons: what is done to one leaves the other unchanged.
 */
class SalonLifecycleIntegrationTest extends IntegrationTest {

    static final MovableClock CLOCK = new MovableClock(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    static final AtomicReference<Integer> EXPORT_AFTER = new AtomicReference<>();
    static final AtomicReference<Integer> DELETION_AFTER = new AtomicReference<>();

    @TestConfiguration
    static class Overrides {
        @Bean
        @Primary
        Clock movableClock() {
            return CLOCK;
        }

        @Bean
        @Primary
        LifecycleFaults armedFaults() {
            return new LifecycleFaults() {
                @Override
                public void exportTableStored(int written) {
                    Integer armed = EXPORT_AFTER.get();
                    if (armed != null && armed == written && EXPORT_AFTER.compareAndSet(armed, null)) {
                        throw new IllegalStateException("controlled export interruption");
                    }
                }

                @Override
                public void deletionBatchCommitted(int batches) {
                    Integer armed = DELETION_AFTER.get();
                    if (armed != null && armed == batches && DELETION_AFTER.compareAndSet(armed, null)) {
                        throw new IllegalStateException("controlled deletion interruption");
                    }
                }
            };
        }
    }

    @Autowired OperatorAccounts accounts;
    @Autowired OperatorCrypto crypto;
    @Autowired IdentityCrypto identityCrypto;
    @Autowired JsonMapper json;
    @Autowired Environment environment;
    @Autowired PlatformTransactionManager manager;
    @Autowired BillingJob billingJob;
    @Autowired SalonExports exports;
    @Autowired SalonDeletion deletion;
    @Autowired List<TenantDataModule> modules;
    @PersistenceContext EntityManager em;

    private final JdbcTemplate owner = TestDatabase.of(TestPostgres.shared()).asOwner();
    private BillingTestSupport billing;

    @BeforeEach
    void setUp() {
        CLOCK.set(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        EXPORT_AFTER.set(null);
        DELETION_AFTER.set(null);
        OperatorTestSupport console = new OperatorTestSupport(http(), port(), accounts, crypto, json, environment, CLOCK);
        billing = new BillingTestSupport(console, http(), port(), owner, identityCrypto, json, manager, em, CLOCK);
    }

    // --- fixtures -----------------------------------------------------------------------------------

    /** A legacy salon with an Owner and a Manager, an audit entry and a pending message. */
    private record Fixture(TestSalon salon, StaffAccount ownerAccount, String ownerEmail, String managerEmail) {
    }

    private Fixture fixture() throws Exception {
        TestSalon salon = billing.legacy(null);
        String ownerEmail = BillingTestSupport.email("own");
        String managerEmail = BillingTestSupport.email("mgr");
        StaffAccount ownerAccount = billing.staff(salon, StaffRole.OWNER, ownerEmail);
        billing.staff(salon, StaffRole.MANAGER, managerEmail);
        owner.update("UPDATE tenant SET owner_email = ?, name = ? WHERE id = ?", ownerEmail, "Lifecycle " + salon.slug(),
                salon.id());
        Instant now = CLOCK.instant();
        owner.update("""
                INSERT INTO audit_entry (tenant_id, id, actor_kind, actor_id, action, subject_kind, subject_id, trace_id,
                    created_at, updated_at)
                VALUES (?, ?, 'system', 'test', 'test.recorded', 'salon', ?, ?, ?, ?)
                """, salon.id(), UUID.randomUUID(), salon.id().toString(), "0123456789abcdef0123456789abcdef",
                java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
        owner.update("""
                INSERT INTO outbox_message (tenant_id, id, channel, payload, status, attempts, next_attempt_at, created_at,
                    updated_at)
                VALUES (?, ?, 'sms', '{"secret":"never-exported"}'::jsonb, 'pending', 0, ?, ?, ?)
                """, salon.id(), UUID.randomUUID(), java.sql.Timestamp.from(now), java.sql.Timestamp.from(now),
                java.sql.Timestamp.from(now));
        return new Fixture(salon, ownerAccount, ownerEmail, managerEmail);
    }

    private Map<String, Object> detail(UUID salon) {
        Http.Exchange detail = billing.console.call("GET", "/salons/" + salon, null, billing.admin());
        BillingTestSupport.status(detail, 200);
        return billing.body(detail);
    }

    private long version(UUID salon) {
        return ((Number) detail(salon).get("version")).longValue();
    }

    private Http.Exchange suspend(UUID salon, long version, String reason) {
        return billing.console.call("POST", "/salons/" + salon + "/suspension", Map.of("version", version, "reason", reason),
                billing.admin());
    }

    private Http.Exchange reactivate(UUID salon, long version, String reason) {
        return billing.console.call("DELETE", "/salons/" + salon + "/suspension",
                Map.of("version", version, "reason", reason), billing.admin());
    }

    private Http.Exchange close(UUID salon, long version, String slug, String reason) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("version", version);
        request.put("confirmSlug", slug);
        request.put("reason", reason);
        return billing.console.call("POST", "/salons/" + salon + "/closure", request, billing.admin());
    }

    private void closed(TestSalon salon) {
        BillingTestSupport.status(close(salon.id(), version(salon.id()), salon.slug(), "The owner asked us to close"), 200);
    }

    private Map<String, Object> me(TestSalon salon, String token) {
        Http.Exchange me = billing.identityCall(salon, "GET", "/me", null, token);
        return Map.of("status", me.status(), "body", me.body());
    }

    private int count(String sql, Object... args) {
        Integer count = owner.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }

    /** Rows of every registered tenant table that belong to this salon, read as the owning role. */
    private Map<String, Integer> tenantRows(UUID salon) {
        Map<String, Integer> rows = new LinkedHashMap<>();
        for (Map.Entry<TenantDataModule, TenantDataModule.Table> entry : exports.tables()) {
            String table = entry.getValue().name();
            rows.put(table, count("SELECT count(*) FROM " + table + " WHERE tenant_id = ?", salon));
        }
        return rows;
    }

    // --- AC-1, AC-2 ------------------------------------------------------------------------------------

    @Test
    void suspensionRefusesEverySalonRequestWithoutRevokingSessionsAndReactivationRestoresThem() throws Exception {
        Fixture a = fixture();
        Fixture b = fixture();
        String tokenA = billing.token(a.salon(), a.ownerAccount());
        String tokenB = billing.token(b.salon(), b.ownerAccount());
        assertThat(me(a.salon(), tokenA).get("status")).isEqualTo(200);

        Http.Exchange shortReason = suspend(a.salon().id(), version(a.salon().id()), "too short");
        BillingTestSupport.status(shortReason, 400);
        assertThat(shortReason.body()).contains("\"field\":\"reason\"");

        Http.Exchange suspended = suspend(a.salon().id(), version(a.salon().id()), "Repeated chargeback disputes");
        BillingTestSupport.status(suspended, 200);
        assertThat(billing.body(suspended).get("status")).isEqualTo("suspended");

        Map<String, Object> refused = me(a.salon(), tokenA);
        assertThat(refused.get("status")).isEqualTo(403);
        assertThat((String) refused.get("body")).contains("\"code\":\"salon_suspended\"");
        assertThat(billing.salonCall(a.salon(), "GET", "/api/v1/salon", null, null).status()).isEqualTo(403);
        assertThat(count("SELECT count(*) FROM identity_session WHERE tenant_id = ? AND revoked_at IS NOT NULL",
                a.salon().id())).isZero();
        assertThat(me(b.salon(), tokenB).get("status")).isEqualTo(200);

        Http.Exchange again = suspend(a.salon().id(), version(a.salon().id()), "Suspend it a second time");
        BillingTestSupport.status(again, 409);
        assertThat(again.body()).contains("\"code\":\"invalid_state\"");
        Http.Exchange notSuspended = reactivate(b.salon().id(), version(b.salon().id()), "It was never suspended");
        BillingTestSupport.status(notSuspended, 409);
        assertThat(notSuspended.body()).contains("\"code\":\"invalid_state\"");
        Http.Exchange stale = reactivate(a.salon().id(), version(a.salon().id()) - 1, "With an older version");
        BillingTestSupport.status(stale, 409);
        assertThat(stale.body()).contains("\"code\":\"conflict\"");

        Http.Exchange reactivated = reactivate(a.salon().id(), version(a.salon().id()), "Disputes were resolved");
        BillingTestSupport.status(reactivated, 200);
        assertThat(billing.body(reactivated).get("status")).isEqualTo("active");
        assertThat(me(a.salon(), tokenA).get("status")).isEqualTo(200);
        assertThat(owner.queryForList("SELECT kind FROM salon_lifecycle_event WHERE salon_id = ? ORDER BY created_at, kind",
                String.class, a.salon().id())).containsExactlyInAnyOrder("suspended", "reactivated");
    }

    @Test
    void reactivationRestoresReadOnlyWhenAnInvoiceIsFourteenDaysOverdueAndBillingWentOnWhileSuspended() {
        String plan = billing.plan(5);
        Map<String, Object> salon = billing.provisioned(plan, 150000, "bank_transfer", "Asia/Colombo");
        UUID id = UUID.fromString((String) salon.get("id"));
        BillingTestSupport.status(suspend(id, version(id), "Paused while the contract is reviewed"), 200);

        // Trial end: the invoice is still issued while suspended (AC-3); 14 days after its due date the
        // salon is read-only underneath its suspension.
        CLOCK.advance(Duration.ofDays(14));
        billingJob.run(CLOCK.instant());
        assertThat(count("SELECT count(*) FROM invoice WHERE salon_id = ?", id)).isEqualTo(1);
        assertThat(count("""
                SELECT count(*) FROM outbox_message WHERE tenant_id = ? AND status = 'pending' AND attempts = 0
                """, id)).isZero(); // bank transfer: no charge; reminders come at due
        CLOCK.advance(Duration.ofDays(7 + 14));
        billingJob.run(CLOCK.instant());
        assertThat(count("""
                SELECT count(*) FROM outbox_message WHERE tenant_id = ? AND channel = 'email' AND status = 'pending'
                """, id)).isGreaterThan(0); // the reminders wait unattempted while suspended

        Http.Exchange reactivated = reactivate(id, version(id), "Contract review is complete");
        BillingTestSupport.status(reactivated, 200);
        assertThat(owner.queryForObject("SELECT access_state FROM tenant WHERE id = ?", String.class, id))
                .isEqualTo("read_only");
    }

    // --- AC-4 ------------------------------------------------------------------------------------------

    @Test
    void closureNeedsTheExactSlugThenHidesTheSalonRevokesSessionsCancelsMessagesStopsBillingAndRetiresTheSlug()
            throws Exception {
        String plan = billing.plan(5);
        Map<String, Object> provisioned = billing.provisioned(plan, 150000, "card", "Asia/Colombo");
        UUID id = UUID.fromString((String) provisioned.get("id"));
        TestSalon salon = billing.testSalon(id);
        StaffAccount staff = billing.staff(salon, StaffRole.MANAGER, BillingTestSupport.email("cls"));
        String token = billing.token(salon, staff);
        Fixture other = fixture();
        String otherToken = billing.token(other.salon(), other.ownerAccount());
        owner.update("""
                INSERT INTO outbox_message (tenant_id, id, channel, payload, status, attempts, next_attempt_at, created_at,
                    updated_at)
                VALUES (?, ?, 'sms', '{}'::jsonb, 'pending', 0, now(), now(), now())
                """, id, UUID.randomUUID());

        Http.Exchange wrong = close(id, version(id), salon.slug().toUpperCase(Locale.ROOT), "The owner asked us to close");
        BillingTestSupport.status(wrong, 400);
        assertThat(wrong.body()).contains("\"field\":\"confirmSlug\"");
        assertThat(detail(id).get("status")).isEqualTo("active");

        Http.Exchange closed = close(id, version(id), salon.slug(), "The owner asked us to close");
        BillingTestSupport.status(closed, 200);
        Map<String, Object> body = billing.body(closed);
        assertThat(body.get("status")).isEqualTo("closed");
        Instant closedAt = Instant.parse((String) body.get("closedAt"));
        assertThat(Instant.parse((String) body.get("deleteAt"))).isEqualTo(closedAt.plus(Duration.ofDays(30)));

        Http.Exchange gone = billing.salonCall(salon, "GET", "/api/v1/salon", null, null);
        assertThat(gone.status()).isEqualTo(404);
        assertThat(gone.body()).contains("\"code\":\"not_found\"");
        assertThat(billing.identityCall(salon, "GET", "/me", null, token).status()).isEqualTo(404);
        assertThat(count("SELECT count(*) FROM identity_session WHERE tenant_id = ? AND revoked_at IS NULL", id)).isZero();
        assertThat(count("SELECT count(*) FROM outbox_message WHERE tenant_id = ? AND status IN ('pending', 'sending')",
                id)).isZero();
        assertThat(count("SELECT count(*) FROM outbox_message WHERE tenant_id = ? AND status = 'cancelled'", id))
                .isGreaterThan(0);
        assertThat(me(other.salon(), otherToken).get("status")).isEqualTo(200);

        Http.Exchange twice = close(id, version(id), salon.slug(), "The owner asked us to close");
        BillingTestSupport.status(twice, 409);
        assertThat(twice.body()).contains("\"code\":\"invalid_state\"");
        BillingTestSupport.status(suspend(id, version(id), "Suspend a closed salon"), 409);

        // No further invoice: past the trial end the billing job leaves the closed salon alone.
        CLOCK.advance(Duration.ofDays(15));
        billingJob.run(CLOCK.instant());
        assertThat(count("SELECT count(*) FROM invoice WHERE salon_id = ?", id)).isZero();
        Http.Exchange retired = billing.console.callOn(billing.console.host(), "POST", "/salons",
                OperatorTestSupport.salon(salon.slug(), plan, "new-" + salon.slug() + "@example.test",
                        "synthetic owner " + UUID.randomUUID()),
                billing.admin(), Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        BillingTestSupport.status(retired, 409);
        assertThat(retired.body()).contains("\"code\":\"slug_taken\"");
    }

    // --- AC-5, AC-6, AC-7, AC-11 -------------------------------------------------------------------

    @Test
    void theExportHoldsExactlyTheSalonsRowsWithoutSecretsAndItsLinkAndConsoleDownloadTheSameArchive() throws Exception {
        Fixture a = fixture();
        Fixture b = fixture();
        billing.token(a.salon(), a.ownerAccount()); // a session, so its table has a row to export
        closed(a.salon());
        Http.Exchange pending = billing.console.call("GET", "/salons/" + a.salon().id() + "/export", null,
                billing.admin());
        BillingTestSupport.status(pending, 200);
        assertThat(billing.body(pending).get("status")).isEqualTo("pending");
        BillingTestSupport.status(billing.console.call("GET", "/salons/" + a.salon().id() + "/export/archive", null,
                billing.admin()), 409);

        SalonExports.Pass pass = exports.run(CLOCK.instant());
        assertThat(pass.exports()).anySatisfy(each -> {
            assertThat(each.salonId()).isEqualTo(a.salon().id());
            assertThat(each.ready()).isTrue();
        });
        Map<String, Object> status = billing.body(billing.console.call("GET", "/salons/" + a.salon().id() + "/export",
                null, billing.admin()));
        assertThat(status.get("status")).isEqualTo("ready");
        String sha = (String) status.get("sha256");

        Binary console = binary(billing.console.host(), "/api/v1/operator/salons/" + a.salon().id() + "/export/archive",
                Map.of("Authorization", "Bearer " + billing.admin()));
        assertThat(console.status()).isEqualTo(200);
        assertThat(console.headers().get("content-disposition")).contains("attachment");
        assertThat(console.headers().get("cache-control")).contains("no-store");
        assertThat(ExportKey.sha256(console.body())).isEqualTo(sha);
        assertThat(((Number) status.get("sizeBytes")).longValue()).isEqualTo(console.body().length);

        Map<String, byte[]> files = unzip(console.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> manifest = json.readValue(files.get("manifest.json"), Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tables = (List<Map<String, Object>>) manifest.get("tables");
        Set<String> registered = new HashSet<>();
        exports.tables().forEach(entry -> registered.add(entry.getValue().name()));
        assertThat(tables).extracting(table -> table.get("name")).containsExactlyInAnyOrderElementsOf(registered);
        assertThat(manifest.get("exportedAt")).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> profile = (Map<String, Object>) manifest.get("salon");
        assertThat(profile.get("id")).isEqualTo(a.salon().id().toString());
        assertThat(manifest.get("excluded").toString()).contains("password_hash", "mfa_secret", "refresh_hash",
                "credential_hash", "encrypted_recipient", "payload");
        for (Map<String, Object> table : tables) {
            String name = (String) table.get("name");
            String content = new String(files.get("tables/" + name + ".json"), StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = json.readValue(content, List.class);
            assertThat(rows).hasSize(((Number) table.get("rows")).intValue());
            // The export-ready email is written after the archive, so it is the one row not in it.
            String later = name.equals("outbox_message") ? " AND channel <> 'platform-email'" : "";
            assertThat(rows.size()).as(name).isEqualTo(count("SELECT count(*) FROM " + name + " WHERE tenant_id = ?" + later,
                    a.salon().id()));
            assertThat(rows).allSatisfy(row -> assertThat(row.get("tenant_id")).isEqualTo(a.salon().id().toString()));
            assertThat(content).doesNotContain(b.salon().id().toString(), b.ownerEmail(), b.managerEmail(),
                    "never-exported", "pbkdf2");
            @SuppressWarnings("unchecked")
            List<String> excluded = (List<String>) table.get("excludedColumns");
            if (!excluded.isEmpty()) {
                rows.forEach(row -> assertThat(row.keySet()).doesNotContainAnyElementsOf(excluded));
            }
        }
        assertThat(new String(files.get("tables/identity_staff.json"), StandardCharsets.UTF_8)).contains(a.ownerEmail(),
                a.managerEmail());

        // The Owner's email waits on the platform-email channel with its link sealed; its token opens the
        // same archive on the platform host only.
        Map<String, Object> message = owner.queryForMap("""
                SELECT payload::text AS payload FROM outbox_message WHERE tenant_id = ? AND channel = 'platform-email'
                """, a.salon().id());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = json.readValue((String) message.get("payload"), Map.class);
        assertThat(payload.get("recipient")).isEqualTo(a.ownerEmail());
        String link = exports.openLinks((String) payload.get("exportId"), (String) payload.get("sealedLinks")).get(0);
        String token = link.substring(link.indexOf('#') + 1);
        assertThat((String) message.get("payload")).doesNotContain(token);
        assertThat(owner.queryForObject("SELECT token_hash FROM salon_export WHERE salon_id = ?", String.class,
                a.salon().id())).isEqualTo(ExportKey.tokenHash(token)).isNotEqualTo(token);

        Binary owned = binary("localhost:" + port(), "/api/v1/exports/" + token, Map.of());
        assertThat(owned.status()).isEqualTo(200);
        assertThat(ExportKey.sha256(owned.body())).isEqualTo(sha);
        Http.Exchange metadata = http().getOnHost("localhost:" + port(), "/api/v1/exports/" + token + "/metadata");
        assertThat(metadata.status()).isEqualTo(200);
        assertThat(metadata.body()).contains(sha);
        String altered = token.substring(0, 42) + (token.charAt(42) == 'A' ? 'B' : 'A');
        for (String bad : List.of(altered, "not-a-token", UUID.randomUUID().toString().replace("-", "") + "abcdefghijk")) {
            Http.Exchange refused = http().getOnHost("localhost:" + port(), "/api/v1/exports/" + bad);
            assertThat(refused.status()).isEqualTo(404);
            assertThat(refused.body()).contains("\"code\":\"not_found\"").doesNotContain(a.salon().slug());
        }
        assertThat(http().getOnHost(a.salon().host(port()), "/api/v1/exports/" + token).status()).isEqualTo(404);
        assertThat(http().getOnHost(billing.console.host(), "/api/v1/exports/" + token).status()).isEqualTo(404);
        assertThat(owner.queryForList("""
                SELECT details ->> 'via' FROM platform_audit_entry WHERE action = 'export.downloaded' AND subject_id = ?
                """, String.class, a.salon().id().toString())).containsExactlyInAnyOrder("console", "link");

        // Audit (AC-11): every lifecycle and export event, with actor and trace, and no token or contact.
        List<Map<String, Object>> audit = owner.queryForList("""
                SELECT action, actor_kind, trace_id, details::text AS details FROM platform_audit_entry
                 WHERE subject_id = ? AND action IN ('salon.closed', 'export.created', 'export.ready', 'export.email_sent',
                     'export.downloaded')
                """, a.salon().id().toString());
        assertThat(audit).extracting(row -> row.get("action")).contains("salon.closed", "export.created", "export.ready",
                "export.email_sent", "export.downloaded");
        assertThat(audit).allSatisfy(row -> {
            assertThat((String) row.get("trace_id")).matches("[0-9a-f]{32}");
            assertThat((String) row.get("details")).doesNotContain(token, a.ownerEmail(), ExportKey.tokenHash(token));
        });
    }

    // --- AC-8 ------------------------------------------------------------------------------------------

    @Test
    void deletionComesExactlyThirtyDaysAfterClosureRemovesEveryTenantRowAndKeepsPlatformRecords() throws Exception {
        String plan = billing.plan(5);
        Map<String, Object> provisioned = billing.provisioned(plan, 150000, "bank_transfer", "Asia/Colombo");
        UUID id = UUID.fromString((String) provisioned.get("id"));
        TestSalon salon = billing.testSalon(id);
        CLOCK.advance(Duration.ofDays(14));
        billingJob.run(CLOCK.instant());
        assertThat(count("SELECT count(*) FROM invoice WHERE salon_id = ?", id)).isEqualTo(1);
        Fixture other = fixture();
        Map<String, Integer> otherBefore = tenantRows(other.salon().id());
        closed(salon);
        exports.run(CLOCK.instant());
        Instant deleteAt = Instant.parse((String) detail(id).get("deleteAt"));
        Map<String, Integer> before = tenantRows(id);
        assertThat(before.get("identity_staff")).isPositive();
        assertThat(before.get("salon_brand")).isEqualTo(1);

        CLOCK.set(deleteAt.minusSeconds(1));
        SalonDeletion.Pass early = deletion.run(CLOCK.instant());
        assertThat(early.salons()).noneMatch(each -> each.salonId().equals(id));
        assertThat(tenantRows(id)).isEqualTo(before);

        CLOCK.set(deleteAt);
        deletion.run(CLOCK.instant());
        assertThat(tenantRows(id)).allSatisfy((table, rows) -> assertThat(rows).as(table).isZero());
        assertThat(owner.queryForMap("SELECT status, slug FROM tenant WHERE id = ?", id))
                .containsEntry("status", "deleted").containsEntry("slug", salon.slug());
        assertThat(owner.queryForMap("SELECT status, archive, token_hash FROM salon_export WHERE salon_id = ?", id))
                .containsEntry("status", "deleted").containsEntry("archive", null).containsEntry("token_hash", null);
        assertThat(count("SELECT count(*) FROM invoice WHERE salon_id = ?", id)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform_audit_entry WHERE subject_id = ? AND action = 'salon.created'",
                id.toString())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform_audit_entry WHERE subject_id = ? AND action = 'salon.deleted'",
                id.toString())).isEqualTo(1);
        assertThat(tenantRows(other.salon().id())).isEqualTo(otherBefore);
        assertThat(detail(id).get("status")).isEqualTo("deleted");
        Http.Exchange gone = billing.console.call("GET", "/salons/" + id + "/export/archive", null, billing.admin());
        BillingTestSupport.status(gone, 404);
    }

    // --- AC-9 ------------------------------------------------------------------------------------------

    @Test
    void anInterruptedExportOrDeletionResumesAndRepeatedOrConcurrentRunsProduceOneArchiveAndOneDeletion()
            throws Exception {
        // Other tests' pending exports are finished first, so the armed interruption meets this salon's.
        exports.run(CLOCK.instant());
        Fixture a = fixture();
        closed(a.salon());
        EXPORT_AFTER.set(2);
        SalonExports.Pass interrupted = exports.run(CLOCK.instant());
        assertThat(interrupted.failed()).contains(a.salon().id());
        assertThat(count("""
                SELECT count(*) FROM salon_export_part p JOIN salon_export e ON e.id = p.export_id WHERE e.salon_id = ?
                """, a.salon().id())).isGreaterThanOrEqualTo(2);
        assertThat(owner.queryForObject("SELECT status FROM salon_export WHERE salon_id = ?", String.class,
                a.salon().id())).isEqualTo("pending");

        List<Object> results = concurrently(4, () -> exports.run(CLOCK.instant()));
        assertThat(results).hasSize(4);
        assertThat(owner.queryForObject("SELECT status FROM salon_export WHERE salon_id = ?", String.class,
                a.salon().id())).isEqualTo("ready");
        exports.run(CLOCK.instant());
        assertThat(count("SELECT count(*) FROM platform_audit_entry WHERE subject_id = ? AND action = 'export.ready'",
                a.salon().id().toString())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox_message WHERE tenant_id = ? AND channel = 'platform-email'",
                a.salon().id())).isEqualTo(1);
        assertThat(count("""
                SELECT count(*) FROM salon_export_part p JOIN salon_export e ON e.id = p.export_id WHERE e.salon_id = ?
                """, a.salon().id())).isZero();

        Instant deleteAt = Instant.parse((String) detail(a.salon().id()).get("deleteAt"));
        // Other tests' salons due earlier are deleted first, so the armed interruption meets this salon's.
        CLOCK.set(deleteAt.minusSeconds(1));
        deletion.run(CLOCK.instant());
        CLOCK.set(deleteAt);
        DELETION_AFTER.set(1);
        SalonDeletion.Pass stopped = deletion.run(CLOCK.instant());
        assertThat(stopped.failed()).contains(a.salon().id());
        assertThat(owner.queryForObject("SELECT status FROM tenant WHERE id = ?", String.class, a.salon().id()))
                .isEqualTo("closed");
        concurrently(4, () -> deletion.run(CLOCK.instant()));
        deletion.run(CLOCK.instant());
        assertThat(tenantRows(a.salon().id())).allSatisfy((table, rows) -> assertThat(rows).as(table).isZero());
        assertThat(count("SELECT count(*) FROM platform_audit_entry WHERE subject_id = ? AND action = 'salon.deleted'",
                a.salon().id().toString())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM salon_lifecycle_event WHERE salon_id = ? AND kind = 'deleted'",
                a.salon().id())).isEqualTo(1);
    }

    // --- AC-10 -----------------------------------------------------------------------------------------

    @Test
    void supportStaffCustomersAndOtherHostsAreRefusedEveryLifecycleActionWithNothingChanged() throws Exception {
        Fixture a = fixture();
        String support = billing.console.token(billing.console.create("OPERATOR_SUPPORT"));
        String staff = billing.token(a.salon(), a.ownerAccount());
        long version = version(a.salon().id());
        String id = a.salon().id().toString();
        Map<String, Object> change = Map.of("version", version, "reason", "Someone without the right");
        Map<String, Object> closure = Map.of("version", version, "confirmSlug", a.salon().slug(), "reason",
                "Someone without the right");
        List<Object[]> calls = List.of(new Object[] {"POST", "/salons/" + id + "/suspension", change},
                new Object[] {"DELETE", "/salons/" + id + "/suspension", change},
                new Object[] {"POST", "/salons/" + id + "/closure", closure},
                new Object[] {"GET", "/salons/" + id + "/export", null},
                new Object[] {"GET", "/salons/" + id + "/export/archive", null},
                new Object[] {"GET", "/salons/" + id + "/lifecycle", null});
        for (Object[] call : calls) {
            Http.Exchange denied = billing.console.call((String) call[0], (String) call[1], call[2], support);
            assertThat(denied.status()).as("support %s %s", call[0], call[1]).isEqualTo(403);
            assertThat(denied.body()).contains("permission_denied");
            assertThat(billing.console.call((String) call[0], (String) call[1], call[2], staff).status())
                    .as("staff %s %s", call[0], call[1]).isEqualTo(401);
            for (String host : List.of(a.salon().host(port()), "localhost:" + port(), "admin.localhost:" + port(),
                    "console.localhost.evil.test:" + port())) {
                assertThat(billing.console.callOn(host, (String) call[0], (String) call[1], call[2], billing.admin(),
                        Map.of()).status()).as("%s %s on %s", call[0], call[1], host).isEqualTo(404);
            }
        }
        assertThat(detail(a.salon().id())).containsEntry("status", "active").containsEntry("version", (int) version);
        assertThat(count("SELECT count(*) FROM salon_lifecycle_event WHERE salon_id = ?", a.salon().id())).isZero();
    }

    @Test
    void historyListsEachActionWithItsReasonActorAndTimeNewestFirst() throws Exception {
        Fixture a = fixture();
        BillingTestSupport.status(suspend(a.salon().id(), version(a.salon().id()), "First suspension reason"), 200);
        CLOCK.advance(Duration.ofSeconds(5));
        BillingTestSupport.status(reactivate(a.salon().id(), version(a.salon().id()), "First reactivation reason"), 200);
        Http.Exchange history = billing.console.call("GET", "/salons/" + a.salon().id() + "/lifecycle?page=0&size=1", null,
                billing.admin());
        BillingTestSupport.status(history, 200);
        Map<String, Object> page = billing.body(history);
        assertThat(page.get("total")).isEqualTo(2);
        @SuppressWarnings("unchecked")
        Map<String, Object> first = ((List<Map<String, Object>>) page.get("items")).get(0);
        assertThat(first).containsEntry("kind", "reactivated").containsEntry("reason", "First reactivation reason");
        @SuppressWarnings("unchecked")
        Map<String, Object> actor = (Map<String, Object>) first.get("actor");
        assertThat(actor.get("kind")).isEqualTo("operator");
        assertThat(actor.get("displayName")).isNotNull();
        BillingTestSupport.status(billing.console.call("GET", "/salons/" + UUID.randomUUID() + "/lifecycle", null,
                billing.admin()), 404);
    }

    // --- Done criterion: every tenant table is registered ------------------------------------------

    @Test
    void everyTableWithATenantIdHasARegisteredExporterAndEraser() {
        List<String> tenantTables = owner.queryForList("""
                SELECT c.table_name FROM information_schema.columns c
                  JOIN information_schema.tables t ON t.table_schema = c.table_schema AND t.table_name = c.table_name
                 WHERE c.table_schema = 'public' AND c.column_name = 'tenant_id' AND t.table_type = 'BASE TABLE'
                """, String.class);
        Map<String, String> registered = new HashMap<>();
        for (TenantDataModule module : modules) {
            for (TenantDataModule.Table table : module.tables()) {
                assertThat(registered.put(table.name(), module.module())).as("registered twice: %s", table.name()).isNull();
            }
        }
        assertThat(tenantTables).isNotEmpty();
        assertThat(registered.keySet()).containsExactlyInAnyOrderElementsOf(tenantTables);
    }

    // --- helpers ---------------------------------------------------------------------------------------

    private static List<Object> concurrently(int n, Callable<Object> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(work));
            }
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get());
            }
            return results;
        }
        finally {
            pool.shutdownNow();
        }
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                files.put(entry.getName(), in.readAllBytes());
            }
        }
        return files;
    }

    private record Binary(int status, Map<String, String> headers, byte[] body) {
    }

    /** A GET on a hostname whose body is read as bytes (an archive). */
    private Binary binary(String host, String path, Map<String, String> headers) throws IOException {
        StringBuilder request = new StringBuilder("GET ").append(path).append(" HTTP/1.1\r\nHost: ").append(host)
                .append("\r\n");
        headers.forEach((name, value) -> request.append(name).append(": ").append(value).append("\r\n"));
        request.append("Accept: application/zip, application/json\r\nConnection: close\r\n\r\n");
        byte[] response;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port()), 5_000);
            socket.setSoTimeout(30_000);
            socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
            response = socket.getInputStream().readAllBytes();
        }
        int end = 0;
        while (!(response[end] == '\r' && response[end + 1] == '\n' && response[end + 2] == '\r' && response[end + 3] == '\n')) {
            end++;
        }
        String[] lines = new String(response, 0, end, StandardCharsets.ISO_8859_1).split("\r\n");
        Map<String, String> parsed = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            parsed.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
        }
        byte[] body = java.util.Arrays.copyOfRange(response, end + 4, response.length);
        assertThat(parsed.get("transfer-encoding")).as("archives are sent with a length").isNull();
        return new Binary(Integer.parseInt(lines[0].split(" ")[1]), parsed, body);
    }
}
