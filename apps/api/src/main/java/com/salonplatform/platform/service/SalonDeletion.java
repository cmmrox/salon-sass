package com.salonplatform.platform.service;

import com.salonplatform.platform.dto.DeletionJobPass;
import com.salonplatform.platform.dto.SalonPass;
import com.salonplatform.platform.repository.PlatformAudit;
import com.salonplatform.platform.repository.SalonExportStore;
import com.salonplatform.platform.repository.SalonLifecycleStore;
import com.salonplatform.shared.lifecycle.TenantDataModule;
import com.salonplatform.shared.observability.Traces;
import com.salonplatform.shared.persistence.PlatformDatabase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The retention job (PLT-06; docs/architecture/operator-console.md, "Retention"): once a closed salon's
 * deletion instant (30 days after closure) has come by the application clock, every tenant row of it
 * is removed through each owning module's registered eraser, table by table in bounded batches, each
 * batch a platform transaction of its own, so a deletion that stops part-way resumes where it was on
 * the next run. Then, in one transaction holding the registry row lock, the archive and its token hash
 * are removed, the registry row becomes the {@code deleted} tombstone with its retired slug, and the
 * lifecycle event and platform audit are written; a repeated or concurrent run finds the salon deleted
 * and does nothing more. Invoices, payments, the lifecycle history and the platform audit are platform
 * records and remain. The erasers' database functions refuse any salon whose retention has not run
 * out, so nothing of another salon, or of this one a second early, can be removed.
 *
 * <p>A salon whose export is still being built waits for it: the Owner's archive is produced before
 * the data it is made from is removed.
 */
@Service
public class SalonDeletion {

    private static final Logger log = LoggerFactory.getLogger(SalonDeletion.class);

    private final PlatformDatabase database;
    private final PlatformAudit audit;
    private final SalonLifecycle lifecycle;
    private final SalonExports exports;
    private final SalonLifecycleStore lifecycles;
    private final SalonExportStore exportStore;
    private final LifecycleFaults faults;
    private final Traces traces;
    private final int batchSize;

    public SalonDeletion(
            PlatformDatabase database,
            PlatformAudit audit,
            SalonLifecycle lifecycle,
            SalonExports exports,
            SalonLifecycleStore lifecycles,
            SalonExportStore exportStore,
            LifecycleFaults faults,
            Traces traces,
            @Value("${salon.retention.batch-size:500}") int batchSize) {
        this.database = database;
        this.audit = audit;
        this.lifecycle = lifecycle;
        this.exports = exports;
        this.lifecycles = lifecycles;
        this.exportStore = exportStore;
        this.faults = faults;
        this.traces = traces;
        if (batchSize < 1 || batchSize > 10_000) {
            throw new IllegalStateException("salon.retention.batch-size must be from 1 to 10000");
        }
        this.batchSize = batchSize;
    }

    /** One pass at {@code now}: every closed salon whose deletion instant is at or before it. */
    public DeletionJobPass run(Instant now) {
        try {
            return traces.inPlatformSpan("job.salon-deletion", () -> pass(now));
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private DeletionJobPass pass(Instant now) {
        List<UUID> due = database.inTransaction(status -> lifecycles.dueForDeletion(now));
        List<SalonPass> salons = new ArrayList<>();
        List<UUID> waiting = new ArrayList<>();
        List<UUID> failed = new ArrayList<>();
        int batches = 0;
        for (UUID salon : due) {
            String export = database.inTransaction(
                    status -> exportStore.statusOfSalon(salon).orElse(null));
            if ("pending".equals(export)) {
                waiting.add(salon);
                log.warn("A closed salon's deletion waits for its export to be ready");
                continue;
            }
            int before = batches;
            long rows = 0;
            try {
                for (Map.Entry<TenantDataModule, TenantDataModule.Table> entry : exports.tables()) {
                    TenantDataModule module = entry.getKey();
                    String table = entry.getValue().name();
                    while (true) {
                        int erased = database.inTransaction(status -> module.erase(table, salon, now, batchSize));
                        if (erased == 0) {
                            break;
                        }
                        rows += erased;
                        batches++;
                        faults.deletionBatchCommitted(batches);
                        if (erased < batchSize) {
                            break;
                        }
                    }
                }
                long erasedRows = rows;
                boolean deleted = database.inTransaction(status -> finish(salon, now, erasedRows));
                salons.add(new SalonPass(salon, batches - before, rows, deleted));
            } catch (RuntimeException ex) {
                failed.add(salon);
                log.error("The deletion job failed for one salon; the next run resumes it", ex);
            }
        }
        log.info("Deletion pass: {} due, {} waiting, {} failed", due.size(), waiting.size(), failed.size());
        return new DeletionJobPass(now, salons, waiting, failed);
    }

    /** Makes the salon a tombstone, once; false when another run already did. */
    private boolean finish(UUID salon, Instant now, long rows) {
        String state = lifecycles.lockStatus(salon).orElse(null);
        if (!"closed".equals(state)) {
            return false;
        }
        exportStore.markDeleted(salon, now);
        lifecycles.markDeleted(salon, now);
        lifecycle.event(salon, "deleted", null, PlatformAudit.SYSTEM, "retention", now);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("from", "closed");
        details.put("to", "deleted");
        details.put("rowsErasedThisRun", rows);
        audit.record(PlatformAudit.SYSTEM, "retention", "salon.deleted", "salon", salon.toString(), details, now);
        return true;
    }
}
