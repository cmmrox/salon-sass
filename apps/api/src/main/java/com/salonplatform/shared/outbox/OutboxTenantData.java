package com.salonplatform.shared.outbox;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.salonplatform.shared.infrastructure.PlatformDatabase;
import com.salonplatform.shared.lifecycle.TenantDataModule;
import com.salonplatform.shared.lifecycle.TenantTableRows;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The salon's outgoing messages in a closed salon's export, and their removal after the retention
 * (PLT-06). Only the delivery record is exported: the payload (which may hold an encrypted recipient
 * envelope or a link credential) and the delivery lease are left out.
 */
@Component
class OutboxTenantData implements TenantDataModule {

    private static final Table OUTBOX = new Table("outbox_message", "created_at", Map.of(
            "payload", "an encrypted or credential-bearing delivery payload, never exported",
            "lease_token", "a dispatcher's internal claim token, never exported"));

    private final JdbcTemplate jdbc;
    private final ObjectProvider<PlatformDatabase> platform;

    OutboxTenantData(JdbcTemplate jdbc, ObjectProvider<PlatformDatabase> platform) {
        this.jdbc = jdbc;
        this.platform = platform;
    }

    @Override
    public String module() {
        return "outbox";
    }

    @Override
    public List<Table> tables() {
        return List.of(OUTBOX);
    }

    @Override
    public List<String> export(String table) {
        return TenantTableRows.export(jdbc, checked(table));
    }

    @Override
    public int erase(String table, UUID salonId, Instant at, int maxRows) {
        return TenantTableRows.erase(platform.getObject(), "outbox_erase_salon_rows", checked(table).name(), salonId, at, maxRows);
    }

    private static Table checked(String table) {
        if (!OUTBOX.name().equals(table)) {
            throw new IllegalArgumentException("not an outbox table: " + table);
        }
        return OUTBOX;
    }
}
