package com.salonplatform.branding.repository;

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
 * Branding's exporter and eraser for a closed salon (PLT-06): its published name and colour, its branches,
 * its business profile and brand drafts, and its brand images. Nothing in them is secret, so no column is left out.
 */
@Component
class BrandTenantData implements TenantDataModule {

    /** In the order they are erased: each table before any table it references (contacts before branches). */
    private static final List<Table> TABLES = List.of(
            new Table("branding_branch_contact_published", "branch_id", Map.of()),
            new Table("branding_branch_contact_draft", "branch_id", Map.of()),
            new Table("branding_profile_published", "tenant_id", Map.of()),
            new Table("branding_profile_draft", "tenant_id", Map.of()),
            new Table("branding_branch", "id", Map.of()),
            new Table("branding_brand_draft", "tenant_id", Map.of()),
            new Table("salon_brand", "tenant_id", Map.of()),
            // Last: the drafts and the published brand refer to their images.
            new Table("branding_image", "id", Map.of()));

    private final JdbcClient jdbc;
    private final ObjectProvider<PlatformDatabase> platform;

    BrandTenantData(JdbcClient jdbc, ObjectProvider<PlatformDatabase> platform) {
        this.jdbc = jdbc;
        this.platform = platform;
    }

    @Override
    public String module() {
        return "branding";
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
                platform.getObject(),
                "branding_erase_salon_rows",
                checked(table).name(),
                salonId,
                at,
                maxRows);
    }

    private static Table checked(String table) {
        return TABLES.stream()
                .filter(known -> known.name().equals(table))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("not a branding table: " + table));
    }
}
