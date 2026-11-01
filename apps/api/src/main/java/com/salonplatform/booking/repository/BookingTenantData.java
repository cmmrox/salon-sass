package com.salonplatform.booking.repository;

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

/** Booking's exporter and eraser for a closed salon (PLT-06): its booking rule versions. Nothing is secret. */
@Component
class BookingTenantData implements TenantDataModule {

    private static final List<Table> TABLES = List.of(new Table("booking_rules_version", "id", Map.of()));

    private final JdbcClient jdbc;
    private final ObjectProvider<PlatformDatabase> platform;

    BookingTenantData(JdbcClient jdbc, ObjectProvider<PlatformDatabase> platform) {
        this.jdbc = jdbc;
        this.platform = platform;
    }

    @Override
    public String module() {
        return "booking";
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
                platform.getObject(), "booking_erase_salon_rows", checked(table).name(), salonId, at, maxRows);
    }

    private static Table checked(String table) {
        return TABLES.stream()
                .filter(known -> known.name().equals(table))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("not a booking table: " + table));
    }
}
