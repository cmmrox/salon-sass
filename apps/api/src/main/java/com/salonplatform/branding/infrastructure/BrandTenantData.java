package com.salonplatform.branding.infrastructure;

import com.salonplatform.shared.infrastructure.PlatformDatabase;
import com.salonplatform.shared.lifecycle.TenantDataModule;
import com.salonplatform.shared.lifecycle.TenantTableRows;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Branding's exporter and eraser for a closed salon (PLT-06): its published name and colour. */
@Component
class BrandTenantData implements TenantDataModule {

    private static final Table BRAND = new Table("salon_brand", "tenant_id", Map.of());

    private final JdbcTemplate jdbc;
    private final ObjectProvider<PlatformDatabase> platform;

    BrandTenantData(JdbcTemplate jdbc, ObjectProvider<PlatformDatabase> platform) {
        this.jdbc = jdbc;
        this.platform = platform;
    }

    @Override
    public String module() {
        return "branding";
    }

    @Override
    public List<Table> tables() {
        return List.of(BRAND);
    }

    @Override
    public List<String> export(String table) {
        return TenantTableRows.export(jdbc, checked(table));
    }

    @Override
    public int erase(String table, UUID salonId, Instant at, int maxRows) {
        return TenantTableRows.erase(
                platform.getObject(),
                "branding_erase_salon_rows",
                checked(table).name(),
                salonId,
                at,
                maxRows);
    }

    private static Table checked(String table) {
        if (!BRAND.name().equals(table)) {
            throw new IllegalArgumentException("not a branding table: " + table);
        }
        return BRAND;
    }
}
