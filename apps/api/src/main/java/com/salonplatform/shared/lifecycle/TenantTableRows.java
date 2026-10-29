package com.salonplatform.shared.lifecycle;

import com.salonplatform.shared.persistence.PlatformDatabase;
import com.salonplatform.shared.tenancy.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The two statements an owning module's exporter and eraser run, so every module does them the same
 * way. The module names its own table; nothing here chooses one. The export names {@code tenant_id}; the
 * eraser is a guarded {@code SECURITY DEFINER} function given the salon. Both are named direct-SQL exceptions
 * (exports, and set-based erasure).
 */
public final class TenantTableRows {

    private TenantTableRows() {}

    /**
     * The current salon's rows of a table as JSON objects without the excluded columns, ordered by one
     * column, read in the caller's tenant transaction (row-level security and an explicit
     * {@code tenant_id} both limit it to that salon).
     */
    public static List<String> export(JdbcClient jdbc, TenantDataModule.Table table) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("A table is exported inside a tenant transaction");
        }
        UUID salon = TenantContext.resolved()
                .orElseThrow(() -> new IllegalStateException("A table is exported for one salon"))
                .id();
        String sql = "SELECT (to_jsonb(t) - ?::text[])::text FROM " + table.name()
                + " t WHERE t.tenant_id = ? ORDER BY t." + table.orderBy();
        return jdbc.sql(sql)
                .params(new SqlArrayValue("text", table.excluded().keySet().toArray()), salon)
                .query(String.class)
                .list();
    }

    /**
     * One bounded batch of a module's guarded eraser function
     * ({@code <function>(salon, table, at, maxRows) -> integer}) on the platform connection, inside the
     * caller's platform transaction.
     */
    public static int erase(
            PlatformDatabase database, String function, String table, UUID salonId, Instant at, int maxRows) {
        database.requireTransaction();
        if (!function.matches("[a-z][a-z0-9_]{1,62}")) {
            throw new IllegalArgumentException("an eraser is a plain function name");
        }
        return database.jdbc()
                .sql("SELECT " + function + "(?, ?, ?, ?)")
                .params(salonId, table, Timestamp.from(at), maxRows)
                .query(Integer.class)
                .optional()
                .orElse(0);
    }
}
