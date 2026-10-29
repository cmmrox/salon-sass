package com.salonplatform.platform.infrastructure;

import static com.salonplatform.platform.infrastructure.PlatformTimes.time;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The local reset's statements, as the owning role (the migration credentials, which exist only on a
 * developer's machine in the {@code local} profile): the platform rows of a salon created after the
 * synthetic baseline, removed with the platform audit's and the registry's append-only triggers lifted
 * inside that transaction only. Named direct-SQL exception 4 of docs/engineering/backend.md, set-based
 * removal, on platform tables with no {@code tenant_id}; the salon's tenant rows are erased by each owning
 * module's eraser, never here.
 */
@Component
@Profile("local")
public class LocalResetStore {

    /** A registry row as the reset sorts salons. */
    public record Salon(UUID id, String status, Instant deleteAt) {}

    private final JdbcClient owner;
    private final TransactionTemplate ownerTransactions;

    LocalResetStore(Environment environment) {
        DriverManagerDataSource source = new DriverManagerDataSource(
                environment.getRequiredProperty("spring.datasource.url"),
                environment.getRequiredProperty("spring.flyway.user"),
                environment.getRequiredProperty("spring.flyway.password"));
        this.owner = JdbcClient.create(source);
        this.ownerTransactions = new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    /** Every salon in the registry, the oldest first. */
    public List<Salon> salons() {
        return owner.sql("SELECT id, status, delete_at FROM tenant ORDER BY created_at")
                .query((row, n) -> new Salon(
                        row.getObject("id", UUID.class),
                        row.getString("status"),
                        PlatformTimes.instant(row.getTimestamp("delete_at"))))
                .list();
    }

    /** Closes an open salon at once, so that the erasers accept it at {@code deleteAt}. */
    public void close(UUID id, Instant closeAt, Instant deleteAt) {
        owner.sql("""
                        UPDATE tenant SET status = 'closed', closed_at = ?, delete_at = ?, updated_at = ? WHERE id = ?
                        """).params(time(closeAt), time(deleteAt), time(closeAt), id).update();
    }

    /** Brings every job occurrence pushed past {@code now} back to it; returns how many. */
    public int rescheduleJobs(Instant now) {
        return owner.sql("UPDATE scheduled_job SET next_run_at = ?, updated_at = ? WHERE next_run_at > ?")
                .params(time(now), time(now), time(now))
                .update();
    }

    /** Removes the salon's platform rows, its registry row last, in one transaction of its own. */
    public void removePlatformRows(UUID id) {
        ownerTransactions.executeWithoutResult(tx -> {
            statement("ALTER TABLE platform_audit_entry DISABLE TRIGGER platform_audit_entry_append_only");
            statement("ALTER TABLE tenant DISABLE TRIGGER tenant_lifecycle");
            String invoices = "SELECT i.id FROM invoice i WHERE i.salon_id = ?";
            remove("DELETE FROM provider_event WHERE invoice_id IN (" + invoices + ")", id);
            remove("DELETE FROM billing_reminder WHERE invoice_id IN (" + invoices + ")", id);
            remove("""
                    DELETE FROM provisioning_receipt WHERE subject_id = ?
                        OR subject_id IN (SELECT s.id FROM subscription s WHERE s.salon_id = ?)
                        OR subject_id IN (SELECT p.id FROM invoice_payment p JOIN invoice i ON i.id = p.invoice_id
                                           WHERE i.salon_id = ?)
                    """, id, id, id);
            remove("DELETE FROM invoice_payment WHERE invoice_id IN (" + invoices + ")", id);
            remove("DELETE FROM charge_attempt WHERE invoice_id IN (" + invoices + ")", id);
            remove("""
                    DELETE FROM platform_audit_entry WHERE subject_id = ? OR details ->> 'salonId' = ?
                        OR subject_id IN (SELECT i.id::text FROM invoice i WHERE i.salon_id = ?::uuid)
                    """, id.toString(), id.toString(), id.toString());
            remove("DELETE FROM invoice WHERE salon_id = ?", id);
            remove("DELETE FROM subscription WHERE salon_id = ?", id);
            remove(
                    "DELETE FROM salon_export_part WHERE export_id IN (SELECT e.id FROM salon_export e WHERE e.salon_id = ?)",
                    id);
            remove("DELETE FROM salon_export WHERE salon_id = ?", id);
            remove("DELETE FROM salon_lifecycle_event WHERE salon_id = ?", id);
            statement("ALTER TABLE support_session DISABLE TRIGGER support_session_guarded");
            remove(
                    "DELETE FROM support_entry_code WHERE session_id IN (SELECT s.id FROM support_session s WHERE s.salon_id = ?)",
                    id);
            remove("DELETE FROM support_session WHERE salon_id = ?", id);
            statement("ALTER TABLE support_session ENABLE TRIGGER support_session_guarded");
            remove("DELETE FROM tenant WHERE id = ?", id);
            statement("ALTER TABLE tenant ENABLE TRIGGER tenant_lifecycle");
            statement("ALTER TABLE platform_audit_entry ENABLE TRIGGER platform_audit_entry_append_only");
        });
    }

    private void remove(String sql, Object... params) {
        owner.sql(sql).params(params).update();
    }

    private void statement(String sql) {
        owner.sql(sql).update();
    }
}
