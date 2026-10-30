package com.salonplatform.platform.service.impl;

import com.salonplatform.platform.adapter.ExportKey;
import com.salonplatform.platform.domain.OperatorPermission;
import com.salonplatform.platform.dto.ExportView;
import com.salonplatform.platform.dto.LinkView;
import com.salonplatform.platform.repository.PlatformAudit;
import com.salonplatform.platform.repository.SalonExportStore;
import com.salonplatform.platform.repository.SalonExportStore.Stored;
import com.salonplatform.platform.service.ExportDownloadService;
import com.salonplatform.shared.persistence.PlatformDatabase;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.NotFoundException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class ExportDownloadServiceImpl implements ExportDownloadService {

    private final PlatformDatabase database;
    private final SalonExportStore store;
    private final OperatorWork work;
    private final OperatorAccess access;
    private final PlatformAudit audit;
    private final ExportKey key;

    ExportDownloadServiceImpl(
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

    @Override
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

    @Override
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

    @Override
    public LinkView linkView(String token) {
        if (!ExportKey.tokenShaped(token)) {
            throw new NotFoundException();
        }
        return database.inTransaction(status -> store.readyByTokenHash(ExportKey.tokenHash(token))
                .map(export -> new LinkView(export.readyAt(), export.deleteAt(), export.sizeBytes(), export.sha256()))
                .orElseThrow(NotFoundException::new));
    }

    @Override
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
