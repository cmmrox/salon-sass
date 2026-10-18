package com.salonplatform.platform.local;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.salonplatform.platform.application.OperatorAccounts;
import com.salonplatform.platform.application.SalonExports;
import com.salonplatform.platform.domain.LifecycleTerms;
import com.salonplatform.shared.infrastructure.PlatformDatabase;
import com.salonplatform.shared.jobs.TenantJobActivity;
import com.salonplatform.shared.lifecycle.TenantDataModule;
import com.salonplatform.shared.time.ResettableClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code local} only: the reset to the synthetic baseline (docs/architecture/operator-console.md,
 * "Local test hooks"). Every salon but Alpha, Beta and Gamma is removed: its tenant data through the
 * registered erasers (the same path the retention job takes), then its platform rows (billing, export,
 * lifecycle history, receipts, platform audit and the registry row or tombstone). Hook-created
 * operators are reset, the test clock returns to real time, and recurring-job occurrences the test
 * clock pushed into the future are brought back to now. Alpha, Beta and Gamma and their data are never
 * touched.
 *
 * <p>The platform runtime role cannot delete billing records, the platform audit or a tombstone, and
 * keeps those grants in every environment. This reset alone uses the owning role's connection (the
 * migration credentials, which exist only on a developer's machine in the {@code local} profile), and
 * lifts the platform audit's and the registry's append-only triggers inside its own transaction only.
 */
@Component
@Profile("local")
class LocalReset {

    private static final Logger log = LoggerFactory.getLogger(LocalReset.class);

    /** The synthetic baseline (docs/qa/test-plan.md): never removed. */
    static final Set<UUID> BASELINE = Set.of(UUID.fromString("a1a1a1a1-0000-4000-8000-000000000001"),
            UUID.fromString("b2b2b2b2-0000-4000-8000-000000000002"),
            UUID.fromString("c3c3c3c3-0000-4000-8000-000000000003"));

    record Result(int removedSalons, List<UUID> salonIds, int jobsRescheduled, String clock) {
    }

    private final PlatformDatabase platform;
    private final SalonExports exports;
    private final OperatorAccounts accounts;
    private final LocalEmailCapture emails;
    private final TenantJobActivity activity;
    private final ObjectProvider<ResettableClock> clock;
    private final JdbcTemplate owner;
    private final TransactionTemplate ownerTransactions;

    LocalReset(PlatformDatabase platform, SalonExports exports, OperatorAccounts accounts, LocalEmailCapture emails,
            TenantJobActivity activity, ObjectProvider<ResettableClock> clock, Environment environment) {
        this.platform = platform;
        this.exports = exports;
        this.accounts = accounts;
        this.emails = emails;
        this.activity = activity;
        this.clock = clock;
        DriverManagerDataSource source = new DriverManagerDataSource(
                environment.getRequiredProperty("spring.datasource.url"),
                environment.getRequiredProperty("spring.flyway.user"),
                environment.getRequiredProperty("spring.flyway.password"));
        this.owner = new JdbcTemplate(source);
        this.ownerTransactions = new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    synchronized Result reset() {
        clock.ifAvailable(ResettableClock::reset);
        Instant now = Instant.now();
        List<Map<String, Object>> salons = owner.queryForList("SELECT id, status, delete_at FROM tenant ORDER BY created_at");
        List<UUID> removed = new ArrayList<>();
        for (Map<String, Object> salon : salons) {
            UUID id = (UUID) salon.get("id");
            if (BASELINE.contains(id)) {
                continue;
            }
            erase(id, (String) salon.get("status"), (Timestamp) salon.get("delete_at"), now);
            removePlatformRows(id);
            removed.add(id);
        }
        int rescheduled = owner.update("UPDATE scheduled_job SET next_run_at = ?, updated_at = ? WHERE next_run_at > ?",
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        accounts.resetFixtures();
        emails.clearSalons(new HashSet<>(removed));
        activity.clear();
        log.info("Local reset: {} salons removed, {} job occurrences brought back to now", removed.size(), rescheduled);
        return new Result(removed.size(), removed, rescheduled, "real");
    }

    /**
     * Erases the salon's tenant data through the registered erasers. Their database functions accept only
     * a closed salon at or after its deletion instant, so an open salon is first closed (as the owner) and
     * every eraser is called at its deletion instant.
     */
    private void erase(UUID id, String status, Timestamp deleteAt, Instant now) {
        if ("deleted".equals(status)) {
            return;
        }
        Instant at;
        if ("closed".equals(status)) {
            at = deleteAt.toInstant().isAfter(now) ? deleteAt.toInstant() : now;
        }
        else {
            Instant closeAt = now;
            at = LifecycleTerms.deleteAt(closeAt);
            owner.update("""
                    UPDATE tenant SET status = 'closed', closed_at = ?, delete_at = ?, updated_at = ? WHERE id = ?
                    """, Timestamp.from(closeAt), Timestamp.from(at), Timestamp.from(closeAt), id);
        }
        for (Map.Entry<TenantDataModule, TenantDataModule.Table> entry : exports.tables()) {
            while (platform.inTransaction(tx -> entry.getKey().erase(entry.getValue().name(), id, at, 1000)) > 0) {
                // Bounded batches until the table holds nothing of the salon.
            }
        }
    }

    private void removePlatformRows(UUID id) {
        ownerTransactions.executeWithoutResult(tx -> {
            owner.execute("ALTER TABLE platform_audit_entry DISABLE TRIGGER platform_audit_entry_append_only");
            owner.execute("ALTER TABLE tenant DISABLE TRIGGER tenant_lifecycle");
            String invoices = "SELECT i.id FROM invoice i WHERE i.salon_id = ?";
            owner.update("DELETE FROM provider_event WHERE invoice_id IN (" + invoices + ")", id);
            owner.update("DELETE FROM billing_reminder WHERE invoice_id IN (" + invoices + ")", id);
            owner.update("""
                    DELETE FROM provisioning_receipt WHERE subject_id = ?
                        OR subject_id IN (SELECT s.id FROM subscription s WHERE s.salon_id = ?)
                        OR subject_id IN (SELECT p.id FROM invoice_payment p JOIN invoice i ON i.id = p.invoice_id
                                           WHERE i.salon_id = ?)
                    """, id, id, id);
            owner.update("DELETE FROM invoice_payment WHERE invoice_id IN (" + invoices + ")", id);
            owner.update("DELETE FROM charge_attempt WHERE invoice_id IN (" + invoices + ")", id);
            owner.update("""
                    DELETE FROM platform_audit_entry WHERE subject_id = ? OR details ->> 'salonId' = ?
                        OR subject_id IN (SELECT i.id::text FROM invoice i WHERE i.salon_id = ?::uuid)
                    """, id.toString(), id.toString(), id.toString());
            owner.update("DELETE FROM invoice WHERE salon_id = ?", id);
            owner.update("DELETE FROM subscription WHERE salon_id = ?", id);
            owner.update("DELETE FROM salon_export_part WHERE export_id IN (SELECT e.id FROM salon_export e WHERE e.salon_id = ?)", id);
            owner.update("DELETE FROM salon_export WHERE salon_id = ?", id);
            owner.update("DELETE FROM salon_lifecycle_event WHERE salon_id = ?", id);
            owner.update("DELETE FROM tenant WHERE id = ?", id);
            owner.execute("ALTER TABLE tenant ENABLE TRIGGER tenant_lifecycle");
            owner.execute("ALTER TABLE platform_audit_entry ENABLE TRIGGER platform_audit_entry_append_only");
        });
    }
}
