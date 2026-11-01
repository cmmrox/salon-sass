package com.salonplatform.catalogue.repository;

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
 * Catalogue's exporter and eraser for a closed salon (PLT-06), in erasure order: variants before their terms versions,
 * terms versions before their services, services before the categories and photos they hold, then the category list,
 * the Popular setting, the tax settings versions and the products. Nothing is secret: a VAT registration number is
 * printed on receipts.
 */
@Component
class CatalogueTenantData implements TenantDataModule {

    private static final List<Table> TABLES = List.of(
            new Table("catalogue_terms_variant", "id", Map.of()),
            new Table("catalogue_terms_version", "id", Map.of()),
            new Table("catalogue_service", "id", Map.of()),
            new Table("catalogue_photo", "id", Map.of()),
            new Table("catalogue_category", "id", Map.of()),
            new Table("catalogue_category_list", "id", Map.of()),
            new Table("catalogue_popular_setting", "id", Map.of()),
            new Table("catalogue_tax_version", "id", Map.of()),
            new Table("catalogue_product", "id", Map.of()));

    private final JdbcClient jdbc;
    private final ObjectProvider<PlatformDatabase> platform;

    CatalogueTenantData(JdbcClient jdbc, ObjectProvider<PlatformDatabase> platform) {
        this.jdbc = jdbc;
        this.platform = platform;
    }

    @Override
    public String module() {
        return "catalogue";
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
                "catalogue_erase_salon_rows",
                checked(table).name(),
                salonId,
                at,
                maxRows);
    }

    private static Table checked(String table) {
        return TABLES.stream()
                .filter(known -> known.name().equals(table))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("not a catalogue table: " + table));
    }
}
