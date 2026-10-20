package com.salonplatform.shared.lifecycle;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One module's tenant tables, registered so that a closed salon's data can be exported and, after its
 * retention, erased (PLT-06; docs/architecture/operator-console.md, "Suspension, closure, export and
 * retention"). Every module that owns a tenant table registers one of these as a bean; platform's
 * export and deletion jobs find them all and never read another module's tables themselves.
 * {@code shared} owns the question and each module answers it, because platform already depends on
 * identity and branding and could not be depended on by them.
 *
 * <p>A table owned by a later feature is part of that feature's definition of done: a developer test
 * fails when a table with a {@code tenant_id} column is registered by no module.
 */
public interface TenantDataModule {

    /** A tenant table and the columns its export leaves out, each with the reason the manifest gives. */
    record Table(String name, String orderBy, Map<String, String> excluded) {

        public Table {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(orderBy, "orderBy");
            excluded = Map.copyOf(excluded);
            if (!name.matches("[a-z][a-z0-9_]{1,62}") || !orderBy.matches("[a-z][a-z0-9_]{1,62}")) {
                throw new IllegalArgumentException("a table and its order column are plain lowercase names");
            }
        }
    }

    /** The module's name, as the export manifest shows it ({@code identity}, {@code branding}). */
    String module();

    /** The module's tenant tables, in the order they are erased: a table before any table it references. */
    List<Table> tables();

    /**
     * Every row of one of the module's tables for the salon of the caller's tenant transaction, as JSON
     * objects, without the excluded columns. Called inside a read-only tenant transaction with that one
     * salon set, so row-level security limits the read to it.
     *
     * @param table one of {@link #tables()}
     * @return one JSON object per row
     */
    List<String> export(String table);

    /**
     * Removes at most {@code maxRows} rows of one of the module's tables for a closed salon whose
     * retention has run out at {@code at}, inside the caller's platform transaction, through the
     * module's own guarded database function.
     *
     * @return how many rows were removed; 0 when none is left
     */
    int erase(String table, UUID salonId, Instant at, int maxRows);
}
