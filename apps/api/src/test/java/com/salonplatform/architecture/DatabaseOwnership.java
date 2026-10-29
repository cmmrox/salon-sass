package com.salonplatform.architecture;

import com.salonplatform.shared.infrastructure.ModuleOwnership;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The table rules of the ownership map (docs/engineering/backend.md, "Ownership of tables and paths"), read
 * from a migrated database's catalogue rather than from the migration files, so that they see what the
 * migrations actually produced. Each rule answers the breaches it finds, one sentence each, so that a test
 * can show it both a database that keeps it and one that does not.
 */
final class DatabaseOwnership {

    /** The one table no module owns: Flyway's record of the migrations it applied. */
    static final String MIGRATION_HISTORY = "flyway_schema_history";

    /** The one foreign key allowed between modules: a salon's row naming its salon by {@code tenant_id}. */
    private static final List<String> SALON_COLUMNS = List.of("tenant_id");

    private static final String SALON_TABLE = "tenant";

    private static final String TABLES = """
            SELECT c.relname
              FROM pg_class c
             WHERE c.relnamespace = 'public'::regnamespace AND c.relkind IN ('r', 'p') AND NOT c.relispartition
            """;

    private static final String FOREIGN_KEYS = """
            SELECT k.conrelid::regclass::text AS from_table,
                   array_to_string(ARRAY(SELECT a.attname FROM unnest(k.conkey) WITH ORDINALITY AS c(number, position)
                                           JOIN pg_attribute a ON a.attrelid = k.conrelid AND a.attnum = c.number
                                          ORDER BY c.position), ',') AS columns,
                   k.confrelid::regclass::text AS to_table
              FROM pg_constraint k
             WHERE k.contype = 'f' AND k.connamespace = 'public'::regnamespace
            """;

    /** A foreign key: the table that holds it, its columns in order, and the table it refers to. */
    record ForeignKey(String from, List<String> columns, String to) {}

    private DatabaseOwnership() {}

    /** The application's tables in the public schema, Flyway's history left out. */
    static Set<String> tables(Connection connection) throws SQLException {
        Set<String> tables = new TreeSet<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(TABLES)) {
            while (rows.next()) {
                tables.add(rows.getString(1));
            }
        }
        tables.remove(MIGRATION_HISTORY);
        return tables;
    }

    static List<ForeignKey> foreignKeys(Connection connection) throws SQLException {
        List<ForeignKey> keys = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(FOREIGN_KEYS)) {
            while (rows.next()) {
                keys.add(new ForeignKey(
                        rows.getString("from_table"),
                        Arrays.asList(rows.getString("columns").split(",")),
                        rows.getString("to_table")));
            }
        }
        return keys;
    }

    /** Every table in the database is in the map, and every table in the map is in the database. */
    static List<String> tablesWithoutExactlyOneOwner(ModuleOwnership ownership, Set<String> tables) {
        List<String> breaches = new ArrayList<>();
        tables.stream()
                .filter(table -> ownership.ownerOfTable(table).isEmpty())
                .forEach(table -> breaches.add("table " + table + " is in the database but in no module's map entry"));
        ownership
                .modules()
                .forEach(module -> module.tables().stream()
                        .filter(table -> !tables.contains(table))
                        .forEach(table -> breaches.add("table " + table + " is in " + module.name()
                                + "'s map entry but not in the database")));
        return breaches;
    }

    /** No foreign key joins two modules' tables, except a salon's row naming its salon. */
    static List<String> foreignKeysBetweenModules(ModuleOwnership ownership, List<ForeignKey> keys) {
        Function<String, String> owner = table -> ownership.ownerOfTable(table).orElse("no module");
        return keys.stream()
                .filter(key -> !owner.apply(key.from()).equals(owner.apply(key.to())))
                .filter(key -> !(key.columns().equals(SALON_COLUMNS) && key.to().equals(SALON_TABLE)))
                .map(key -> "foreign key " + key.from() + key.columns() + " (" + owner.apply(key.from())
                        + ") refers to " + key.to() + " (" + owner.apply(key.to()) + ")")
                .toList();
    }

    /**
     * A table that was not there after the last migration before CR-021 carries its module's prefix; the
     * tables named before it keep their names (CR-021 decision 4). A table in no module's entry is the first
     * rule's breach, not this one's.
     */
    static List<String> newTablesWithoutTheirModulesPrefix(
            ModuleOwnership ownership, Set<String> tables, Set<String> namedBeforePrefixes) {
        Map<String, Optional<String>> newTables = tables.stream()
                .filter(table -> !namedBeforePrefixes.contains(table))
                .collect(Collectors.toMap(table -> table, ownership::ownerOfTable));
        return newTables.entrySet().stream()
                .filter(table -> table.getValue().isPresent())
                .filter(table -> !table.getKey().startsWith(table.getValue().get() + "_"))
                .map(table -> "table " + table.getKey() + " is new but does not start with "
                        + table.getValue().get() + "_")
                .sorted()
                .toList();
    }
}
