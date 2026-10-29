package com.salonplatform.platform.application;

import com.salonplatform.platform.domain.OperatorPermission;
import com.salonplatform.platform.infrastructure.ExportKey;
import com.salonplatform.platform.infrastructure.PlatformAudit;
import com.salonplatform.shared.api.ApplicationProblemException;
import com.salonplatform.shared.api.ErrorCode;
import com.salonplatform.shared.api.NotFoundException;
import com.salonplatform.shared.infrastructure.PlatformDatabase;
import com.salonplatform.shared.lifecycle.TenantDataModule;
import com.salonplatform.shared.observability.Traces;
import com.salonplatform.shared.outbox.OutboxLifecycle;
import com.salonplatform.shared.tenancy.CurrentTenant;
import com.salonplatform.shared.tenancy.TenantContext;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * A closed salon's export (PLT-06; docs/architecture/operator-console.md, "Export"). The export job
 * builds it table by table: each registered module's exporter reads one of its tables in a read-only
 * tenant transaction with that one salon set, and the rows are stored, encrypted, as one part in a
 * platform transaction of their own, so a run that stops part-way resumes from the next table. Once
 * every table has its part, one platform transaction holding the export's row lock writes the ZIP
 * (a JSON file per table and a manifest of tables, row counts, the salon's profile, the export instant
 * and the excluded secret columns), stores it encrypted with its SHA-256, issues the download token
 * (only its hash is kept), writes the Owner's email to the outbox and records the platform audit. A
 * repeated or concurrent run finds the export ready and does nothing more, so there is one archive.
 */
@Service
public class SalonExports {

    private static final Logger log = LoggerFactory.getLogger(SalonExports.class);

    static final String EMAIL_KIND = "export.ready";

    @Schema(requiredProperties = {"status", "createdAt", "readyAt", "deleteAt", "sizeBytes", "sha256"})
    public record ExportView(
            @Schema(allowableValues = {"pending", "ready", "deleted"})
            String status,

            Instant createdAt,

            @Schema(
                    types = {"string", "null"},
                    format = "date-time",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            Instant readyAt,

            Instant deleteAt,

            @Schema(
                    types = {"integer", "null"},
                    format = "int64",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            Long sizeBytes,

            @Schema(
                    types = {"string", "null"},
                    pattern = "^[0-9a-f]{64}$",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String sha256) {}

    @Schema(requiredProperties = {"readyAt", "deleteAt", "sizeBytes", "sha256"})
    public record LinkView(Instant readyAt, Instant deleteAt, long sizeBytes, String sha256) {}

    /** A downloadable archive: its bytes, the file name to save it as and its checksum. */
    public record Archive(byte[] bytes, String fileName, String sha256) {}

    /** What one pass of the export job did. */
    @Schema(hidden = true)
    public record Pass(Instant at, List<ExportPass> exports, List<UUID> failed) {}

    public record ExportPass(UUID salonId, UUID exportId, int tablesStored, boolean ready) {}

    private record Pending(UUID exportId, UUID salonId, String slug, ZoneId zone) {}

    private record Part(String table, String module, int rows, byte[] content) {}

    private final PlatformDatabase database;
    private final OperatorWork work;
    private final OperatorAccess access;
    private final PlatformAudit audit;
    private final ExportKey key;
    private final List<TenantDataModule> modules;
    private final TransactionTemplate tenantReads;
    private final LifecycleFaults faults;
    private final Traces traces;
    private final JsonMapper json;
    private final Clock clock;
    private final String platformDomain;
    private final String linkOrigin;

    public SalonExports(
            PlatformDatabase database,
            OperatorWork work,
            OperatorAccess access,
            PlatformAudit audit,
            ExportKey key,
            List<TenantDataModule> modules,
            PlatformTransactionManager tenantTransactions,
            LifecycleFaults faults,
            Traces traces,
            JsonMapper json,
            Clock clock,
            @Value("${salon.platform-domain}") String platformDomain,
            @Value("${salon.export.link-origin:https://%s}") String linkOrigin) {
        this.database = database;
        this.work = work;
        this.access = access;
        this.audit = audit;
        this.key = key;
        this.modules = modules.stream()
                .sorted(Comparator.comparing(TenantDataModule::module))
                .toList();
        this.tenantReads = new TransactionTemplate(tenantTransactions);
        this.tenantReads.setReadOnly(true);
        this.tenantReads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.faults = faults;
        this.traces = traces;
        this.json = json;
        this.clock = clock;
        this.platformDomain = platformDomain.strip().toLowerCase(Locale.ROOT);
        this.linkOrigin = linkOrigin;
    }

    /** Every registered tenant table, in the order exported and listed in the manifest. */
    public List<Map.Entry<TenantDataModule, TenantDataModule.Table>> tables() {
        List<Map.Entry<TenantDataModule, TenantDataModule.Table>> all = new ArrayList<>();
        for (TenantDataModule module : modules) {
            for (TenantDataModule.Table table : module.tables()) {
                all.add(Map.entry(module, table));
            }
        }
        return all;
    }

    // --- reads ---------------------------------------------------------------------------------

    /** The export of a salon, for an Admin; 404 when the salon is unknown or has none. */
    public ExportView status(UUID salonId) {
        return work.run(() -> {
            access.require(OperatorPermission.EXPORT_READ);
            return database
                    .jdbc()
                    .query(
                            """
                    SELECT status, created_at, ready_at, delete_at, size_bytes, sha256 FROM salon_export WHERE salon_id = ?
                    """,
                            (row, n) -> new ExportView(
                                    row.getString("status"),
                                    row.getTimestamp("created_at").toInstant(),
                                    instant(row.getTimestamp("ready_at")),
                                    row.getTimestamp("delete_at").toInstant(),
                                    (Long) row.getObject("size_bytes"),
                                    row.getString("sha256")),
                            salonId)
                    .stream()
                    .findFirst()
                    .orElseThrow(NotFoundException::new);
        });
    }

    /**
     * The same archive the Owner downloads, for an Admin: 404 when there is none or it was deleted, 409
     * {@code invalid_state} while it is being built. Each download is audited.
     */
    public Archive archiveForOperator(UUID salonId) {
        return work.run(() -> {
            var operator = access.require(OperatorPermission.EXPORT_READ);
            Map<String, Object> row = database.jdbc().queryForList("""
                    SELECT e.id, e.status, e.archive, e.sha256, e.size_bytes, t.slug
                      FROM salon_export e JOIN tenant t ON t.id = e.salon_id WHERE e.salon_id = ?
                    """, salonId).stream()
                    .findFirst()
                    .orElseThrow(NotFoundException::new);
            if ("pending".equals(row.get("status"))) {
                throw new ApplicationProblemException(409, ErrorCode.INVALID_STATE);
            }
            if (!"ready".equals(row.get("status"))) {
                throw new NotFoundException();
            }
            Archive archive = open(row);
            audit.record(
                    PlatformAudit.OPERATOR,
                    operator.id().toString(),
                    "export.downloaded",
                    "salon",
                    salonId.toString(),
                    downloadDetails(row, "console"));
            return archive;
        });
    }

    /** What the Owner's download page shows for a token; 404 for any token that does not open a ready archive. */
    public LinkView linkView(String token) {
        if (!ExportKey.tokenShaped(token)) {
            throw new NotFoundException();
        }
        return database.inTransaction(status -> database
                .jdbc()
                .query(
                        """
                SELECT ready_at, delete_at, size_bytes, sha256 FROM salon_export WHERE token_hash = ? AND status = 'ready'
                """,
                        (row, n) -> new LinkView(
                                row.getTimestamp("ready_at").toInstant(),
                                row.getTimestamp("delete_at").toInstant(),
                                row.getLong("size_bytes"),
                                row.getString("sha256")),
                        ExportKey.tokenHash(token))
                .stream()
                .findFirst()
                .orElseThrow(NotFoundException::new));
    }

    /**
     * The archive a download link opens, until deletion; each download is audited. A malformed, wrong,
     * altered or expired token is 404, exactly as one for a salon that never existed.
     */
    public Archive archiveForToken(String token) {
        if (!ExportKey.tokenShaped(token)) {
            throw new NotFoundException();
        }
        return database.inTransaction(status -> {
            Map<String, Object> row = database.jdbc().queryForList("""
                    SELECT e.id, e.salon_id, e.status, e.archive, e.sha256, e.size_bytes, t.slug
                      FROM salon_export e JOIN tenant t ON t.id = e.salon_id
                     WHERE e.token_hash = ? AND e.status = 'ready'
                    """, ExportKey.tokenHash(token)).stream()
                    .findFirst()
                    .orElseThrow(NotFoundException::new);
            Archive archive = open(row);
            audit.record(
                    PlatformAudit.SYSTEM,
                    "export-link",
                    "export.downloaded",
                    "salon",
                    row.get("salon_id").toString(),
                    downloadDetails(row, "link"));
            return archive;
        });
    }

    private Archive open(Map<String, Object> row) {
        UUID exportId = (UUID) row.get("id");
        byte[] bytes = key.open((byte[]) row.get("archive"), "export-archive:" + exportId);
        String sha = ExportKey.sha256(bytes);
        if (!sha.equals(row.get("sha256"))) {
            throw new IllegalStateException("A stored export does not match its checksum");
        }
        return new Archive(bytes, "salon-export-" + row.get("slug") + ".zip", sha);
    }

    private static Map<String, Object> downloadDetails(Map<String, Object> row, String via) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("exportId", row.get("id").toString());
        details.put("via", via);
        details.put("sizeBytes", row.get("size_bytes"));
        return details;
    }

    // --- the job -------------------------------------------------------------------------------

    /** One pass of the export job at {@code now}: every pending export of a closed salon. */
    public Pass run(Instant now) {
        try {
            return traces.inPlatformSpan("job.salon-export", () -> pass(now));
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Pass pass(Instant now) {
        List<Pending> pending = database.inTransaction(status -> database.jdbc()
                .query(
                        """
                SELECT e.id, e.salon_id, t.slug, t.time_zone FROM salon_export e JOIN tenant t ON t.id = e.salon_id
                 WHERE e.status = 'pending' AND t.status = 'closed'
                 ORDER BY e.created_at, e.id
                """,
                        (row, n) -> new Pending(
                                row.getObject("id", UUID.class),
                                row.getObject("salon_id", UUID.class),
                                row.getString("slug"),
                                ZoneId.of(row.getString("time_zone")))));
        List<ExportPass> exports = new ArrayList<>();
        List<UUID> failed = new ArrayList<>();
        int stored = 0;
        for (Pending export : pending) {
            int before = stored;
            try {
                stored = storeParts(export, stored);
                boolean ready = assemble(export, now);
                exports.add(new ExportPass(export.salonId(), export.exportId(), stored - before, ready));
            } catch (RuntimeException ex) {
                failed.add(export.salonId());
                log.error("The export job failed for one salon; the next run resumes it", ex);
            }
        }
        log.info("Export pass: {} pending, {} failed", pending.size(), failed.size());
        return new Pass(now, exports, failed);
    }

    /** Stores the part of every table not stored yet; returns the run's running count of stored tables. */
    private int storeParts(Pending export, int storedSoFar) {
        int stored = storedSoFar;
        CurrentTenant salon = new CurrentTenant(export.salonId(), export.slug(), export.zone());
        for (Map.Entry<TenantDataModule, TenantDataModule.Table> entry : tables()) {
            TenantDataModule module = entry.getKey();
            String table = entry.getValue().name();
            Boolean needed = database.inTransaction(
                    status -> database.jdbc().queryForObject("""
                    SELECT e.status = 'pending' AND NOT EXISTS (SELECT 1 FROM salon_export_part p
                                                                 WHERE p.export_id = e.id AND p.table_name = ?)
                      FROM salon_export e WHERE e.id = ?
                    """, Boolean.class, table, export.exportId()));
            if (!Boolean.TRUE.equals(needed)) {
                continue;
            }
            List<String> rows;
            try {
                rows = TenantContext.callAs(salon, () -> tenantReads.execute(status -> module.export(table)));
            } catch (RuntimeException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
            byte[] content = ("[" + String.join(",", rows) + "]").getBytes(StandardCharsets.UTF_8);
            byte[] sealed = key.seal(content, "export-part:" + export.exportId() + ":" + table);
            Instant at = clock.instant();
            int count = rows.size();
            // The export row is share-locked first: a concurrent run that is writing the archive holds it
            // for update, so this waits for it and then stores nothing for an export that is no longer
            // pending (its parts are already gone).
            database.inTransaction(status -> {
                String state = database
                        .jdbc()
                        .queryForList(
                                "SELECT status FROM salon_export WHERE id = ? FOR SHARE",
                                String.class,
                                export.exportId())
                        .stream()
                        .findFirst()
                        .orElse(null);
                if (!"pending".equals(state)) {
                    return 0;
                }
                return database.jdbc()
                        .update(
                                """
                        INSERT INTO salon_export_part (export_id, table_name, module, row_count, content, created_at,
                            updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (export_id, table_name) DO NOTHING
                        """,
                                export.exportId(),
                                table,
                                module.module(),
                                count,
                                sealed,
                                Timestamp.from(at),
                                Timestamp.from(at));
            });
            stored++;
            faults.exportTableStored(stored);
        }
        return stored;
    }

    /** Writes the archive once every table has its part; false when the export is not complete yet. */
    private boolean assemble(Pending export, Instant now) {
        return database.inTransaction(status -> {
            String state = database
                    .jdbc()
                    .queryForList(
                            "SELECT status FROM salon_export WHERE id = ? FOR UPDATE", String.class, export.exportId())
                    .stream()
                    .findFirst()
                    .orElse(null);
            if (!"pending".equals(state)) {
                return "ready".equals(state);
            }
            Map<String, Part> parts = new HashMap<>();
            database.jdbc()
                    .query(
                            "SELECT table_name, module, row_count, content FROM salon_export_part WHERE export_id = ?",
                            (row, n) -> parts.put(
                                    row.getString("table_name"),
                                    new Part(
                                            row.getString("table_name"),
                                            row.getString("module"),
                                            row.getInt("row_count"),
                                            row.getBytes("content"))),
                            export.exportId());
            List<Map.Entry<TenantDataModule, TenantDataModule.Table>> tables = tables();
            if (tables.stream()
                    .anyMatch(entry -> !parts.containsKey(entry.getValue().name()))) {
                return false;
            }
            Map<String, Object> salon = database.jdbc().queryForMap("""
                    SELECT id, slug, name, country, currency, time_zone, default_language, contact_email, contact_phone,
                           address, owner_email, created_at, closed_at, delete_at
                      FROM tenant WHERE id = ?
                    """, export.salonId());
            byte[] zip = zip(export, tables, parts, salon, now);
            String sha = ExportKey.sha256(zip);
            String token = key.newToken();
            Timestamp at = Timestamp.from(now);
            database.jdbc()
                    .update(
                            """
                    UPDATE salon_export SET status = 'ready', ready_at = ?, size_bytes = ?, sha256 = ?, archive = ?,
                        token_hash = ?, updated_at = ?
                     WHERE id = ?
                    """,
                            at,
                            (long) zip.length,
                            sha,
                            key.seal(zip, "export-archive:" + export.exportId()),
                            ExportKey.tokenHash(token),
                            at,
                            export.exportId());
            database.jdbc().update("DELETE FROM salon_export_part WHERE export_id = ?", export.exportId());
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("exportId", export.exportId().toString());
            details.put("tables", tables.size());
            details.put("sizeBytes", zip.length);
            details.put("sha256", sha);
            audit.record(
                    PlatformAudit.SYSTEM,
                    "export",
                    "export.ready",
                    "salon",
                    export.salonId().toString(),
                    details,
                    now);
            String owner = (String) salon.get("owner_email");
            if (owner != null) {
                enqueueEmail(
                        export,
                        owner,
                        (String) salon.get("name"),
                        token,
                        sha,
                        ((Timestamp) salon.get("delete_at")).toInstant(),
                        now);
            }
            audit.record(
                    PlatformAudit.SYSTEM,
                    "export",
                    "export.email_sent",
                    "salon",
                    export.salonId().toString(),
                    Map.of("exportId", export.exportId().toString(), "recipient", owner == null ? "none" : "owner"),
                    now);
            return true;
        });
    }

    private byte[] zip(
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

    /**
     * The Owner contact's export-ready email on the closed salon's {@code platform-email} channel. Its
     * link carries the token, so the links are sealed with the export key: the token is stored nowhere
     * in plain form; the delivering channel opens them.
     */
    private void enqueueEmail(
            Pending export, String recipient, String name, String token, String sha, Instant deleteAt, Instant now) {
        String link = String.format(Locale.ROOT, linkOrigin, platformDomain) + "/exports#" + token;
        String until =
                DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.ENGLISH).format(deleteAt.atZone(export.zone()));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("recipient", recipient);
        payload.put("subject", "Your salon's data export is ready");
        payload.put("kind", EMAIL_KIND);
        payload.put("exportId", export.exportId().toString());
        payload.put(
                "body",
                "The data export of " + (name == null ? "your salon" : name) + " is ready. Download it "
                        + "from the link in this email until " + until + ", when the salon's data and this export are "
                        + "deleted for good. The archive's SHA-256 checksum is " + sha + ".");
        payload.put(
                "sealedLinks",
                Base64.getEncoder()
                        .encodeToString(
                                key.seal(json.writeValueAsBytes(List.of(link)), "export-link:" + export.exportId())));
        database.jdbc()
                .queryForObject(
                        "SELECT outbox_enqueue_for_salon(?, ?, ?, ?::jsonb, ?, ?)::text",
                        String.class,
                        export.salonId(),
                        UUID.randomUUID(),
                        OutboxLifecycle.PLATFORM_EMAIL,
                        json.writeValueAsString(payload),
                        traces.currentTraceParent().orElse(null),
                        Timestamp.from(now));
    }

    /** The export-ready email's links, opened with the export key (for the delivering channel). */
    public List<String> openLinks(String exportId, String sealed) {
        byte[] plain = key.open(Base64.getDecoder().decode(sealed), "export-link:" + exportId);
        return List.of(json.readValue(plain, String[].class));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    /** Whether a salon's export is ready (deletion waits for it). */
    Optional<String> exportStatus(UUID salonId) {
        return database
                .jdbc()
                .queryForList("SELECT status FROM salon_export WHERE salon_id = ?", String.class, salonId)
                .stream()
                .findFirst();
    }
}
