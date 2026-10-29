package com.salonplatform.platform.local;

import com.salonplatform.platform.application.SalonDeletion;
import com.salonplatform.platform.application.SalonExports;
import com.salonplatform.platform.infrastructure.SalonLifecycleStore;
import com.salonplatform.shared.infrastructure.PlatformDatabase;
import com.salonplatform.shared.jobs.TenantJobActivity;
import com.salonplatform.shared.outbox.SalonMessageCounts;
import com.salonplatform.shared.tenancy.CurrentTenant;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import com.salonplatform.shared.web.NotFoundException;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F03-S03's local test hooks (docs/architecture/operator-console.md, "Local test hooks"): loopback
 * only, {@code local} profile only, never in the OpenAPI document. They run one pass of the export or
 * deletion job now at the test clock's instant, arm a one-shot interruption of either part-way, show
 * one salon's background state (counts only, no message content), whatever its state, and reset the
 * local environment to the synthetic baseline ({@link LocalReset}).
 */
@RestController
@Profile("local")
@Hidden
@RequestMapping("/local/operator")
class LocalLifecycleController {

    private final SalonExports exports;
    private final SalonDeletion deletion;
    private final LocalLifecycleFaults faults;
    private final LocalReset reset;
    private final PlatformDatabase database;
    private final SalonLifecycleStore lifecycles;
    private final SalonMessageCounts messages;
    private final TransactionTemplate tenantReads;
    private final TenantJobActivity activity;
    private final Clock clock;

    LocalLifecycleController(
            SalonExports exports,
            SalonDeletion deletion,
            LocalLifecycleFaults faults,
            LocalReset reset,
            PlatformDatabase database,
            SalonLifecycleStore lifecycles,
            SalonMessageCounts messages,
            PlatformTransactionManager tenantTransactions,
            TenantJobActivity activity,
            Clock clock) {
        this.exports = exports;
        this.deletion = deletion;
        this.faults = faults;
        this.reset = reset;
        this.database = database;
        this.lifecycles = lifecycles;
        this.messages = messages;
        this.tenantReads = new TransactionTemplate(tenantTransactions);
        this.tenantReads.setReadOnly(true);
        this.activity = activity;
        this.clock = clock;
    }

    record InterruptRequest(Integer afterTables, Integer afterBatches) {}

    record ExportInterrupt(Integer afterTables) {}

    record DeletionInterrupt(Integer afterBatches) {}

    record Outbox(long waiting, long delivered, long cancelled, long dead) {}

    record Jobs(
            long due,
            long run,
            long skipped,
            long failed,
            java.time.Instant lastRunAt,
            java.time.Instant lastSkippedAt) {}

    record Background(UUID salonId, String status, String accessState, Outbox outbox, Jobs jobs) {}

    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    /** One export pass, now, at the application clock's instant (the test clock when it is set). */
    @PostMapping("/exports/run")
    ResponseEntity<SalonExports.Pass> runExport(HttpServletRequest http) {
        loopback(http);
        return ok(exports.run(clock.instant()));
    }

    /** One deletion pass, now, at the application clock's instant. */
    @PostMapping("/deletions/run")
    ResponseEntity<SalonDeletion.Pass> runDeletion(HttpServletRequest http) {
        loopback(http);
        return ok(deletion.run(clock.instant()));
    }

    @PutMapping("/exports/interrupt")
    ResponseEntity<ExportInterrupt> interruptExport(
            @RequestBody(required = false) InterruptRequest request, HttpServletRequest http) {
        loopback(http);
        Integer after = request == null ? null : request.afterTables();
        if (after != null && (after < 1 || after > 1000)) {
            throw new ApplicationProblemException(
                    400,
                    ErrorCode.BAD_REQUEST,
                    List.of(new FieldProblem("afterTables", "must be from 1 to 1000, or null")));
        }
        faults.armExport(after);
        return ok(new ExportInterrupt(faults.export()));
    }

    @GetMapping("/exports/interrupt")
    ResponseEntity<ExportInterrupt> exportInterrupt(HttpServletRequest http) {
        loopback(http);
        return ok(new ExportInterrupt(faults.export()));
    }

    @PutMapping("/deletions/interrupt")
    ResponseEntity<DeletionInterrupt> interruptDeletion(
            @RequestBody(required = false) InterruptRequest request, HttpServletRequest http) {
        loopback(http);
        Integer after = request == null ? null : request.afterBatches();
        if (after != null && (after < 1 || after > 100000)) {
            throw new ApplicationProblemException(
                    400,
                    ErrorCode.BAD_REQUEST,
                    List.of(new FieldProblem("afterBatches", "must be from 1 to 100000, or null")));
        }
        faults.armDeletion(after);
        return ok(new DeletionInterrupt(faults.deletion()));
    }

    @GetMapping("/deletions/interrupt")
    ResponseEntity<DeletionInterrupt> deletionInterrupt(HttpServletRequest http) {
        loopback(http);
        return ok(new DeletionInterrupt(faults.deletion()));
    }

    /**
     * One salon's background state, whatever its lifecycle state: its outbox messages waiting (pending or
     * being delivered), delivered, cancelled and dead, and its recurring-job occurrences due, run,
     * skipped (while suspended) and failed since the API started. Counts only.
     */
    @GetMapping("/salons/{salonId}/background")
    ResponseEntity<Background> background(@PathVariable UUID salonId, HttpServletRequest http) throws Exception {
        loopback(http);
        SalonLifecycleStore.State salon =
                database.inTransaction(status -> lifecycles.state(salonId).orElseThrow(NotFoundException::new));
        CurrentTenant tenant = new CurrentTenant(salonId, salon.slug(), ZoneId.of(salon.timeZone()));
        Map<String, Long> counts =
                TenantContext.callAs(tenant, () -> tenantReads.execute(status -> messages.byStatus(salonId)));
        TenantJobActivity.Counts jobs = activity.of(salonId);
        return ok(new Background(
                salonId,
                salon.status(),
                salon.accessState(),
                new Outbox(
                        counts.getOrDefault("pending", 0L) + counts.getOrDefault("sending", 0L),
                        counts.getOrDefault("sent", 0L),
                        counts.getOrDefault("cancelled", 0L),
                        counts.getOrDefault("dead", 0L)),
                new Jobs(
                        jobs.due(),
                        jobs.run(),
                        jobs.skipped(),
                        jobs.failed(),
                        jobs.lastRunAt(),
                        jobs.lastSkippedAt())));
    }

    /** Back to the synthetic baseline (see {@link LocalReset}). */
    @PostMapping("/reset")
    ResponseEntity<LocalReset.Result> reset(HttpServletRequest http) {
        loopback(http);
        return ok(reset.reset());
    }

    private static void loopback(HttpServletRequest request) {
        try {
            if (!InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress()) {
                throw new NotFoundException();
            }
        } catch (UnknownHostException ex) {
            throw new NotFoundException();
        }
    }
}
