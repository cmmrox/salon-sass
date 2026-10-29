package com.salonplatform.platform.infrastructure;

import static com.salonplatform.platform.infrastructure.PlatformTimes.time;

import com.salonplatform.shared.infrastructure.PlatformDatabase;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * A closed salon's export (PLT-06): the export row, the sealed part of each tenant table while it is
 * being built, the sealed archive once it is ready, and the closed salon's profile for its manifest
 * ({@code salon_export}, {@code salon_export_part}, {@code tenant}). On the platform connection: named
 * direct-SQL exceptions 1 (platform tables with no {@code tenant_id}) and 5 (exports). The tenant rows
 * themselves are read by each owning module's exporter, never here.
 */
@Component
public class SalonExportStore {

    /** A pending export of a closed salon, with what its job needs of the salon. */
    public record Pending(UUID exportId, UUID salonId, String slug, ZoneId zone) {}

    /** A stored part: one tenant table's rows, sealed. */
    public record Part(String tableName, String module, int rowCount, byte[] content) {}

    /** The closed salon's profile, as the archive's manifest records it. */
    public record ClosedSalon(
            UUID id,
            String slug,
            String name,
            String country,
            String currency,
            String timeZone,
            String defaultLanguage,
            String contactEmail,
            String contactPhone,
            String address,
            String ownerEmail,
            Instant createdAt,
            Instant closedAt,
            Instant deleteAt) {}

    /** The finished archive: sealed, with its checksum and the token hash of its download link. */
    public record ReadyArchive(long sizeBytes, String sha256, byte[] sealedArchive, String tokenHash) {}

    /** An export as the console shows it. */
    public record Status(
            String status, Instant createdAt, Instant readyAt, Instant deleteAt, Long sizeBytes, String sha256) {}

    /** A ready export as its download page shows it. */
    public record Ready(Instant readyAt, Instant deleteAt, long sizeBytes, String sha256) {}

    /** A stored archive to download, with its salon's slug for the file name. */
    public record Stored(
            UUID id, UUID salonId, String status, byte[] sealedArchive, String sha256, Long sizeBytes, String slug) {}

    private final PlatformDatabase database;

    SalonExportStore(PlatformDatabase database) {
        this.database = database;
    }

    /** Schedules the full export of a salon being closed, in its closing transaction. */
    public void schedule(UUID exportId, UUID salonId, Instant deleteAt, Instant now) {
        database.jdbc()
                .sql("""
                        INSERT INTO salon_export (id, salon_id, status, delete_at, created_at, updated_at)
                        VALUES (?, ?, 'pending', ?, ?, ?)
                        """)
                .params(exportId, salonId, time(deleteAt), time(now), time(now))
                .update();
    }

    /** Every pending export of a closed salon, the oldest first. */
    public List<Pending> pending() {
        return database.jdbc()
                .sql("""
                        SELECT e.id, e.salon_id, t.slug, t.time_zone FROM salon_export e JOIN tenant t ON t.id = e.salon_id
                         WHERE e.status = 'pending' AND t.status = 'closed'
                         ORDER BY e.created_at, e.id
                        """)
                .query((row, n) -> new Pending(
                        row.getObject("id", UUID.class),
                        row.getObject("salon_id", UUID.class),
                        row.getString("slug"),
                        ZoneId.of(row.getString("time_zone"))))
                .list();
    }

    /** Whether the export is still pending and has no part for the table yet. */
    public boolean partNeeded(UUID exportId, String table) {
        return database.jdbc()
                .sql("""
                        SELECT e.status = 'pending' AND NOT EXISTS (SELECT 1 FROM salon_export_part p
                                                                     WHERE p.export_id = e.id AND p.table_name = ?)
                          FROM salon_export e WHERE e.id = ?
                        """)
                .params(table, exportId)
                .query(Boolean.class)
                .single();
    }

    /** The export's status, its row share-locked until the transaction ends. */
    public Optional<String> statusForShare(UUID exportId) {
        return status("SELECT status FROM salon_export WHERE id = ? FOR SHARE", exportId);
    }

    /** The export's status, its row locked for update until the transaction ends. */
    public Optional<String> statusForUpdate(UUID exportId) {
        return status("SELECT status FROM salon_export WHERE id = ? FOR UPDATE", exportId);
    }

    /** The status of the salon's export, if it has one. */
    public Optional<String> statusOfSalon(UUID salonId) {
        return status("SELECT status FROM salon_export WHERE salon_id = ?", salonId);
    }

    private Optional<String> status(String sql, UUID id) {
        return database.jdbc().sql(sql).param(id).query(String.class).list().stream()
                .findFirst();
    }

    /** Stores a table's part once; a part already stored is kept. */
    public void addPart(UUID exportId, Part part, Instant at) {
        database.jdbc()
                .sql("""
                        INSERT INTO salon_export_part (export_id, table_name, module, row_count, content, created_at,
                            updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (export_id, table_name) DO NOTHING
                        """)
                .params(exportId, part.tableName(), part.module(), part.rowCount(), part.content(), time(at), time(at))
                .update();
    }

    public List<Part> parts(UUID exportId) {
        return database.jdbc()
                .sql("SELECT table_name, module, row_count, content FROM salon_export_part WHERE export_id = ?")
                .param(exportId)
                .query((row, n) -> new Part(
                        row.getString("table_name"),
                        row.getString("module"),
                        row.getInt("row_count"),
                        row.getBytes("content")))
                .list();
    }

    public ClosedSalon closedSalon(UUID salonId) {
        return database.jdbc()
                .sql("""
                        SELECT id, slug, name, country, currency, time_zone, default_language, contact_email, contact_phone,
                               address, owner_email, created_at, closed_at, delete_at
                          FROM tenant WHERE id = ?
                        """)
                .param(salonId)
                .query((row, n) -> new ClosedSalon(
                        row.getObject("id", UUID.class),
                        row.getString("slug"),
                        row.getString("name"),
                        row.getString("country"),
                        row.getString("currency"),
                        row.getString("time_zone"),
                        row.getString("default_language"),
                        row.getString("contact_email"),
                        row.getString("contact_phone"),
                        row.getString("address"),
                        row.getString("owner_email"),
                        instant(row, "created_at"),
                        instant(row, "closed_at"),
                        instant(row, "delete_at")))
                .single();
    }

    /** Stores the finished archive and removes the parts it was built from. */
    public void markReady(UUID exportId, ReadyArchive archive, Instant at) {
        database.jdbc()
                .sql("""
                        UPDATE salon_export SET status = 'ready', ready_at = ?, size_bytes = ?, sha256 = ?, archive = ?,
                            token_hash = ?, updated_at = ?
                         WHERE id = ?
                        """)
                .params(
                        time(at),
                        archive.sizeBytes(),
                        archive.sha256(),
                        archive.sealedArchive(),
                        archive.tokenHash(),
                        time(at),
                        exportId)
                .update();
        database.jdbc()
                .sql("DELETE FROM salon_export_part WHERE export_id = ?")
                .param(exportId)
                .update();
    }

    /** Deletes the salon's archive with its data, keeping the export row as a record. */
    public void markDeleted(UUID salonId, Instant at) {
        database.jdbc().sql("""
                        UPDATE salon_export SET status = 'deleted', archive = NULL, token_hash = NULL, deleted_at = ?, updated_at = ?
                         WHERE salon_id = ? AND status <> 'deleted'
                        """).params(time(at), time(at), salonId).update();
    }

    public Optional<Status> statusView(UUID salonId) {
        return database
                .jdbc()
                .sql("""
                        SELECT status, created_at, ready_at, delete_at, size_bytes, sha256 FROM salon_export WHERE salon_id = ?
                        """)
                .param(salonId)
                .query((row, n) -> new Status(
                        row.getString("status"),
                        instant(row, "created_at"),
                        instant(row, "ready_at"),
                        instant(row, "delete_at"),
                        (Long) row.getObject("size_bytes"),
                        row.getString("sha256")))
                .list()
                .stream()
                .findFirst();
    }

    public Optional<Ready> readyByTokenHash(String tokenHash) {
        return database
                .jdbc()
                .sql("""
                        SELECT ready_at, delete_at, size_bytes, sha256 FROM salon_export WHERE token_hash = ? AND status = 'ready'
                        """)
                .param(tokenHash)
                .query((row, n) -> new Ready(
                        instant(row, "ready_at"),
                        instant(row, "delete_at"),
                        row.getLong("size_bytes"),
                        row.getString("sha256")))
                .list()
                .stream()
                .findFirst();
    }

    /** The salon's export with its archive, whatever its status. */
    public Optional<Stored> archiveOfSalon(UUID salonId) {
        return stored("""
                SELECT e.id, e.salon_id, e.status, e.archive, e.sha256, e.size_bytes, t.slug
                  FROM salon_export e JOIN tenant t ON t.id = e.salon_id WHERE e.salon_id = ?
                """, salonId);
    }

    /** The ready export a download link's token opens. */
    public Optional<Stored> readyArchiveByTokenHash(String tokenHash) {
        return stored("""
                SELECT e.id, e.salon_id, e.status, e.archive, e.sha256, e.size_bytes, t.slug
                  FROM salon_export e JOIN tenant t ON t.id = e.salon_id
                 WHERE e.token_hash = ? AND e.status = 'ready'
                """, tokenHash);
    }

    private Optional<Stored> stored(String sql, Object key) {
        return database
                .jdbc()
                .sql(sql)
                .param(key)
                .query((row, n) -> new Stored(
                        row.getObject("id", UUID.class),
                        row.getObject("salon_id", UUID.class),
                        row.getString("status"),
                        row.getBytes("archive"),
                        row.getString("sha256"),
                        (Long) row.getObject("size_bytes"),
                        row.getString("slug")))
                .list()
                .stream()
                .findFirst();
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
