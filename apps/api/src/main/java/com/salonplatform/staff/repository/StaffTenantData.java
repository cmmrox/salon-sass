package com.salonplatform.staff.repository;

import com.salonplatform.shared.lifecycle.TenantDataModule;
import com.salonplatform.shared.lifecycle.TenantTableRows;
import com.salonplatform.shared.persistence.PlatformDatabase;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Staff's exporter and eraser for a closed salon (PLT-06): its opening hours, holiday reviews and choices, and
 * special closures. A closure's reason is the salon's own note and is exported with it; nothing is secret.
 */
@Component
class StaffTenantData implements TenantDataModule {

    /** In the order they are erased: each table before any table it references (choices before reviews). */
    private static final List<Table> TABLES = List.of(
            new Table("staff_holiday_choice", "id", Map.of()),
            new Table("staff_holiday_review", "id", Map.of()),
            new Table("staff_closure", "id", Map.of()),
            new Table("staff_opening_hours", "id", Map.of()));

    private final JdbcClient jdbc;
    private final ObjectProvider<PlatformDatabase> platform;

    StaffTenantData(JdbcClient jdbc, ObjectProvider<PlatformDatabase> platform) {
        this.jdbc = jdbc;
        this.platform = platform;
    }

    @Override
    public String module() {
        return "staff";
    }

    @Override
    public List<Table> tables() {
        return TABLES;
    }

    @Override
    public List<String> export(String table) {
        return TenantTableRows.export(jdbc, checked(table));
    }

    @Override
    public int erase(String table, UUID salonId, Instant at, int maxRows) {
        return TenantTableRows.erase(
                platform.getObject(), "staff_erase_salon_rows", checked(table).name(), salonId, at, maxRows);
    }

    private static Table checked(String table) {
        return TABLES.stream()
                .filter(known -> known.name().equals(table))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("not a staff table: " + table));
    }
}
