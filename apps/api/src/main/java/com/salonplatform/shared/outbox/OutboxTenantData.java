package com.salonplatform.shared.outbox;

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
 * The salon's outgoing messages and events, and which subscriber processed which event, in a closed salon's
 * export, and their removal after the retention (PLT-06). Only a message's delivery record is exported: the
 * payload (which may hold an encrypted recipient envelope or a link credential) and the delivery lease are
 * left out. The processed records are erased first, because they refer to their events.
 */
@Component
class OutboxTenantData implements TenantDataModule {

    private static final Table OUTBOX = new Table(
            "outbox_message",
            "created_at",
            Map.of(
                    "payload", "an encrypted or credential-bearing delivery payload, never exported",
                    "lease_token", "a dispatcher's internal claim token, never exported"));

    private static final Table PROCESSED_EVENTS = new Table("shared_processed_event", "created_at", Map.of());

    /** In the order they are erased. */
    private static final List<Table> TABLES = List.of(PROCESSED_EVENTS, OUTBOX);

    private final JdbcClient jdbc;
    private final ObjectProvider<PlatformDatabase> platform;

    OutboxTenantData(JdbcClient jdbc, ObjectProvider<PlatformDatabase> platform) {
        this.jdbc = jdbc;
        this.platform = platform;
    }

    @Override
    public String module() {
        return "outbox";
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
                platform.getObject(), "outbox_erase_salon_rows", checked(table).name(), salonId, at, maxRows);
    }

    private static Table checked(String table) {
        return TABLES.stream()
                .filter(owned -> owned.name().equals(table))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("not an outbox table: " + table));
    }
}
