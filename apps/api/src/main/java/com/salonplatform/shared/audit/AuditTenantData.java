package com.salonplatform.shared.audit;

import com.salonplatform.shared.infrastructure.PlatformDatabase;
import com.salonplatform.shared.lifecycle.TenantDataModule;
import com.salonplatform.shared.lifecycle.TenantTableRows;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The salon's audit trail in a closed salon's export, and its removal after the retention (PLT-06).
 * Secret values were never stored in it ({@link SecretValues}).
 */
@Component
class AuditTenantData implements TenantDataModule {

    private static final TenantDataModule.Table AUDIT =
            new TenantDataModule.Table("audit_entry", "created_at", Map.of());

    private final JdbcClient jdbc;
    private final ObjectProvider<PlatformDatabase> platform;

    AuditTenantData(JdbcClient jdbc, ObjectProvider<PlatformDatabase> platform) {
        this.jdbc = jdbc;
        this.platform = platform;
    }

    @Override
    public String module() {
        return "audit";
    }

    @Override
    public List<Table> tables() {
        return List.of(AUDIT);
    }

    @Override
    public List<String> export(String table) {
        return TenantTableRows.export(jdbc, checked(table));
    }

    @Override
    public int erase(String table, UUID salonId, Instant at, int maxRows) {
        return TenantTableRows.erase(
                platform.getObject(), "audit_erase_salon_rows", checked(table).name(), salonId, at, maxRows);
    }

    private static TenantDataModule.Table checked(String table) {
        if (!AUDIT.name().equals(table)) {
            throw new IllegalArgumentException("not an audit table: " + table);
        }
        return AUDIT;
    }
}
