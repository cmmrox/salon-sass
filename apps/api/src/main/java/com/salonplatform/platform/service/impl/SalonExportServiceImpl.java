package com.salonplatform.platform.service.impl;

import com.salonplatform.platform.adapter.ExportKey;
import com.salonplatform.platform.dto.ExportJobPass;
import com.salonplatform.platform.dto.ExportPass;
import com.salonplatform.platform.repository.PlatformAudit;
import com.salonplatform.platform.repository.PlatformOutbox;
import com.salonplatform.platform.repository.SalonExportStore;
import com.salonplatform.platform.repository.SalonExportStore.ClosedSalon;
import com.salonplatform.platform.repository.SalonExportStore.Part;
import com.salonplatform.platform.repository.SalonExportStore.Pending;
import com.salonplatform.platform.repository.SalonExportStore.ReadyArchive;
import com.salonplatform.platform.service.LifecycleFaults;
import com.salonplatform.platform.service.SalonExportService;
import com.salonplatform.shared.lifecycle.TenantDataModule;
import com.salonplatform.shared.observability.Traces;
import com.salonplatform.shared.outbox.OutboxLifecycle;
import com.salonplatform.shared.persistence.PlatformDatabase;
import com.salonplatform.shared.tenancy.CurrentTenant;
import com.salonplatform.shared.tenancy.TenantContext;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
class SalonExportServiceImpl implements SalonExportService {

    private static final Logger log = LoggerFactory.getLogger(SalonExportServiceImpl.class);

    static final String EMAIL_KIND = "export.ready";

    private final PlatformDatabase database;
    private final SalonExportStore store;
    private final PlatformOutbox outbox;
    private final PlatformAudit audit;
    private final ExportKey key;
    private final ExportArchives archives;
    private final List<TenantDataModule> modules;
    private final TransactionTemplate tenantReads;
    private final LifecycleFaults faults;
    private final Traces traces;
    private final JsonMapper json;
    private final Clock clock;
    private final String platformDomain;
    private final String linkOrigin;

    SalonExportServiceImpl(
            PlatformDatabase database,
            SalonExportStore store,
            PlatformOutbox outbox,
            PlatformAudit audit,
            ExportKey key,
            ExportArchives archives,
            List<TenantDataModule> modules,
            PlatformTransactionManager tenantTransactions,
            LifecycleFaults faults,
            Traces traces,
            JsonMapper json,
            Clock clock,
            @Value("${salon.platform-domain}") String platformDomain,
            @Value("${salon.export.link-origin:https://%s}") String linkOrigin) {
        this.database = database;
        this.store = store;
        this.outbox = outbox;
        this.audit = audit;
        this.key = key;
        this.archives = archives;
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

    @Override
    public List<Map.Entry<TenantDataModule, TenantDataModule.Table>> tables() {
        List<Map.Entry<TenantDataModule, TenantDataModule.Table>> all = new ArrayList<>();
        for (TenantDataModule module : modules) {
            for (TenantDataModule.Table table : module.tables()) {
                all.add(Map.entry(module, table));
            }
        }
        return all;
    }
    // --- the job -------------------------------------------------------------------------------

    @Override
    public ExportJobPass run(Instant now) {
        try {
            return traces.inPlatformSpan("job.salon-export", () -> pass(now));
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private ExportJobPass pass(Instant now) {
        List<Pending> pending = database.inTransaction(status -> store.pending());
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
        return new ExportJobPass(now, exports, failed);
    }

    /** Stores the part of every table not stored yet; returns the run's running count of stored tables. */
    private int storeParts(Pending export, int storedSoFar) {
        int stored = storedSoFar;
        CurrentTenant salon = new CurrentTenant(export.salonId(), export.slug(), export.zone());
        for (Map.Entry<TenantDataModule, TenantDataModule.Table> entry : tables()) {
            TenantDataModule module = entry.getKey();
            String table = entry.getValue().name();
            boolean needed = database.inTransaction(status -> store.partNeeded(export.exportId(), table));
            if (!needed) {
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
                if (!"pending".equals(store.statusForShare(export.exportId()).orElse(null))) {
                    return null;
                }
                store.addPart(export.exportId(), new Part(table, module.module(), count, sealed), at);
                return null;
            });
            stored++;
            faults.exportTableStored(stored);
        }
        return stored;
    }

    /** Writes the archive once every table has its part; false when the export is not complete yet. */
    private boolean assemble(Pending export, Instant now) {
        return database.inTransaction(status -> {
            String state = store.statusForUpdate(export.exportId()).orElse(null);
            if (!"pending".equals(state)) {
                return "ready".equals(state);
            }
            Map<String, Part> parts = new HashMap<>();
            store.parts(export.exportId()).forEach(part -> parts.put(part.tableName(), part));
            List<Map.Entry<TenantDataModule, TenantDataModule.Table>> tables = tables();
            if (tables.stream()
                    .anyMatch(entry -> !parts.containsKey(entry.getValue().name()))) {
                return false;
            }
            ClosedSalon salon = store.closedSalon(export.salonId());
            byte[] zip = archives.zip(export, tables, parts, salon, now);
            String sha = ExportKey.sha256(zip);
            String token = key.newToken();
            store.markReady(
                    export.exportId(),
                    new ReadyArchive(
                            zip.length,
                            sha,
                            key.seal(zip, "export-archive:" + export.exportId()),
                            ExportKey.tokenHash(token)),
                    now);
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
            String owner = salon.ownerEmail();
            if (owner != null) {
                enqueueEmail(export, owner, salon.name(), token, sha, salon.deleteAt(), now);
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
        outbox.enqueue(export.salonId(), OutboxLifecycle.PLATFORM_EMAIL, payload, now);
    }

    @Override
    public List<String> openLinks(String exportId, String sealed) {
        byte[] plain = key.open(Base64.getDecoder().decode(sealed), "export-link:" + exportId);
        return List.of(json.readValue(plain, String[].class));
    }
}
