package com.salonplatform.platform.application;

import com.salonplatform.platform.domain.OperatorPermission;
import com.salonplatform.platform.infrastructure.ExportKey;
import com.salonplatform.platform.infrastructure.PlatformAudit;
import com.salonplatform.shared.infrastructure.PlatformDatabase;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.NotFoundException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * A closed salon's export, downloaded (PLT-06; docs/architecture/operator-console.md, "Export"): its
 * status and archive for an Admin in the console, and the archive the Owner's emailed link opens until
 * deletion. Every download is audited; any link that does not open a ready archive is not found.
 */
@Service
public class ExportDownloads {

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

    private final PlatformDatabase database;
    private final OperatorWork work;
    private final OperatorAccess access;
    private final PlatformAudit audit;
    private final ExportKey key;

    public ExportDownloads(
            PlatformDatabase database, OperatorWork work, OperatorAccess access, PlatformAudit audit, ExportKey key) {
        this.database = database;
        this.work = work;
        this.access = access;
        this.audit = audit;
        this.key = key;
    }

    /** The export of a salon, for an Admin; 404 when the salon is unknown or has none. */
    public ExportView status(UUID salonId) {
        return work.run(() -> {
            access.require(OperatorPermission.EXPORT_READ);
            return database
                    .jdbcTemplate()
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
            Map<String, Object> row = database.jdbcTemplate().queryForList("""
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
                .jdbcTemplate()
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
            Map<String, Object> row = database.jdbcTemplate().queryForList("""
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

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
