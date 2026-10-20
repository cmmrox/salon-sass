package com.salonplatform.shared.lifecycle;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.salonplatform.shared.infrastructure.PlatformDatabase;
import com.salonplatform.shared.tenancy.TenantContext;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The two statements an owning module's exporter and eraser run, so every module does them the same
 * way. The module names its own table; nothing here chooses one.
 */
public final class TenantTableRows {

    private TenantTableRows() {
    }

    /**
     * The current salon's rows of a table as JSON objects without the excluded columns, ordered by one
     * column, read in the caller's tenant transaction (row-level security and an explicit
     * {@code tenant_id} both limit it to that salon).
     */
    public static List<String> export(JdbcTemplate jdbc, TenantDataModule.Table table) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("A table is exported inside a tenant transaction");
        }
        UUID salon = TenantContext.resolved()
                .orElseThrow(() -> new IllegalStateException("A table is exported for one salon")).id();
        String sql = "SELECT (to_jsonb(t) - ?::text[])::text FROM " + table.name()
                + " t WHERE t.tenant_id = ? ORDER BY t." + table.orderBy();
        return jdbc.execute((ConnectionCallback<List<String>>) connection -> {
            Array excluded = connection.createArrayOf("text", table.excluded().keySet().toArray());
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setArray(1, excluded);
                statement.setObject(2, salon);
                try (ResultSet rows = statement.executeQuery()) {
                    List<String> out = new ArrayList<>();
                    while (rows.next()) {
                        out.add(rows.getString(1));
                    }
                    return out;
                }
            }
            finally {
                excluded.free();
            }
        });
    }

    /**
     * One bounded batch of a module's guarded eraser function
     * ({@code <function>(salon, table, at, maxRows) -> integer}) on the platform connection, inside the
     * caller's platform transaction.
     */
    public static int erase(PlatformDatabase database, String function, String table, UUID salonId,
            java.time.Instant at, int maxRows) {
        database.requireTransaction();
        if (!function.matches("[a-z][a-z0-9_]{1,62}")) {
            throw new IllegalArgumentException("an eraser is a plain function name");
        }
        Integer erased = database.jdbc().queryForObject("SELECT " + function + "(?, ?, ?, ?)", Integer.class,
                salonId, table, java.sql.Timestamp.from(at), maxRows);
        return erased == null ? 0 : erased;
    }
}
