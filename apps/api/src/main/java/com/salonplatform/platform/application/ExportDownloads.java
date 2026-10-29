package com.salonplatform.platform.application;

import com.salonplatform.platform.domain.OperatorPermission;
import com.salonplatform.platform.infrastructure.ExportKey;
import com.salonplatform.platform.infrastructure.PlatformAudit;
import com.salonplatform.platform.infrastructure.SalonExportStore;
import com.salonplatform.platform.infrastructure.SalonExportStore.Stored;
import com.salonplatform.shared.infrastructure.PlatformDatabase;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.NotFoundException;
import io.swagger.v3.oas.annotations.media.Schema;
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
    private final SalonExportStore store;
    private final OperatorWork work;
    private final OperatorAccess access;
    private final PlatformAudit audit;
    private final ExportKey key;

    public ExportDownloads(
            PlatformDatabase database,
            SalonExportStore store,
            OperatorWork work,
            OperatorAccess access,
            PlatformAudit audit,
            ExportKey key) {
        this.database = database;
        this.store = store;
        this.work = work;
        this.access = access;
        this.audit = audit;
        this.key = key;
    }

    /** The export of a salon, for an Admin; 404 when the salon is unknown or has none. */
    public ExportView status(UUID salonId) {
        return work.run(() -> {
            access.require(OperatorPermission.EXPORT_READ);
            return store.statusView(salonId)
                    .map(export -> new ExportView(
                            export.status(),
                            export.createdAt(),
                            export.readyAt(),
                            export.deleteAt(),
                            export.sizeBytes(),
                            export.sha256()))
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
            Stored row = store.archiveOfSalon(salonId).orElseThrow(NotFoundException::new);
            if ("pending".equals(row.status())) {
                throw new ApplicationProblemException(409, ErrorCode.INVALID_STATE);
            }
            if (!"ready".equals(row.status())) {
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
        return database.inTransaction(status -> store.readyByTokenHash(ExportKey.tokenHash(token))
                .map(export -> new LinkView(export.readyAt(), export.deleteAt(), export.sizeBytes(), export.sha256()))
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
            Stored row =
                    store.readyArchiveByTokenHash(ExportKey.tokenHash(token)).orElseThrow(NotFoundException::new);
            Archive archive = open(row);
            audit.record(
                    PlatformAudit.SYSTEM,
                    "export-link",
                    "export.downloaded",
                    "salon",
                    row.salonId().toString(),
                    downloadDetails(row, "link"));
            return archive;
        });
    }

    private Archive open(Stored row) {
        byte[] bytes = key.open(row.sealedArchive(), "export-archive:" + row.id());
        String sha = ExportKey.sha256(bytes);
        if (!sha.equals(row.sha256())) {
            throw new IllegalStateException("A stored export does not match its checksum");
        }
        return new Archive(bytes, "salon-export-" + row.slug() + ".zip", sha);
    }

    private static Map<String, Object> downloadDetails(Stored row, String via) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("exportId", row.id().toString());
        details.put("via", via);
        details.put("sizeBytes", row.sizeBytes());
        return details;
    }
}
