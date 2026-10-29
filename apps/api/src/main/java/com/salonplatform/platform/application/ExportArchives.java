package com.salonplatform.platform.application;

import com.salonplatform.platform.application.SalonExports.Part;
import com.salonplatform.platform.application.SalonExports.Pending;
import com.salonplatform.platform.infrastructure.ExportKey;
import com.salonplatform.shared.lifecycle.TenantDataModule;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes a finished export's ZIP: a manifest of tables, row counts, the salon's profile, the export
 * instant and the excluded secret columns, and one JSON file per table, opened from its sealed part.
 */
@Component
class ExportArchives {

    private final ExportKey key;
    private final JsonMapper json;

    ExportArchives(ExportKey key, JsonMapper json) {
        this.key = key;
        this.json = json;
    }

    byte[] zip(
            Pending export,
            List<Map.Entry<TenantDataModule, TenantDataModule.Table>> tables,
            Map<String, Part> parts,
            Map<String, Object> salon,
            Instant now) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("format", "salon-export/1");
        manifest.put("exportId", export.exportId().toString());
        manifest.put("exportedAt", now.toString());
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("id", salon.get("id").toString());
        profile.put("slug", salon.get("slug"));
        profile.put("name", salon.get("name"));
        profile.put("country", salon.get("country"));
        profile.put("currency", salon.get("currency"));
        profile.put("timeZone", salon.get("time_zone"));
        profile.put("defaultLanguage", salon.get("default_language"));
        profile.put("contactEmail", salon.get("contact_email"));
        profile.put("contactPhone", salon.get("contact_phone"));
        profile.put("address", salon.get("address"));
        profile.put("ownerEmail", salon.get("owner_email"));
        profile.put(
                "createdAt", ((Timestamp) salon.get("created_at")).toInstant().toString());
        profile.put("closedAt", ((Timestamp) salon.get("closed_at")).toInstant().toString());
        profile.put("deleteAt", ((Timestamp) salon.get("delete_at")).toInstant().toString());
        manifest.put("salon", profile);
        List<Map<String, Object>> listed = new ArrayList<>();
        List<Map<String, Object>> excluded = new ArrayList<>();
        for (Map.Entry<TenantDataModule, TenantDataModule.Table> entry : tables) {
            TenantDataModule.Table table = entry.getValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", table.name());
            item.put("module", entry.getKey().module());
            item.put("file", "tables/" + table.name() + ".json");
            item.put("rows", parts.get(table.name()).rows());
            item.put(
                    "excludedColumns",
                    table.excluded().keySet().stream().sorted().toList());
            listed.add(item);
            table.excluded().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(column -> {
                        Map<String, Object> exclusion = new LinkedHashMap<>();
                        exclusion.put("table", table.name());
                        exclusion.put("column", column.getKey());
                        exclusion.put("reason", column.getValue());
                        excluded.add(exclusion);
                    });
        }
        manifest.put("tables", listed);
        manifest.put("excluded", excluded);
        manifest.put(
                "note",
                "Secrets are never exported: password hashes, MFA secrets, token and challenge hashes "
                        + "and encrypted delivery payloads are left out of every table, as listed under excluded.");
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                ZipOutputStream out = new ZipOutputStream(bytes)) {
            out.putNextEntry(new ZipEntry("manifest.json"));
            out.write(json.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
            out.closeEntry();
            for (Map.Entry<TenantDataModule, TenantDataModule.Table> entry : tables) {
                String name = entry.getValue().name();
                out.putNextEntry(new ZipEntry("tables/" + name + ".json"));
                out.write(key.open(parts.get(name).content(), "export-part:" + export.exportId() + ":" + name));
                out.closeEntry();
            }
            out.finish();
            return bytes.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("The export archive could not be written", ex);
        }
    }
}
