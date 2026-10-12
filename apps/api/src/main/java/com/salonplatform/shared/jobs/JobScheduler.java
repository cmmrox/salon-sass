package com.salonplatform.shared.jobs;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.salonplatform.shared.api.DatabaseUnavailable;
import com.salonplatform.shared.observability.Traces;
import com.salonplatform.shared.tenancy.ActiveTenants;
import com.salonplatform.shared.tenancy.CurrentTenant;
import com.salonplatform.shared.tenancy.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.SmartLifecycle;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs the recurring jobs. Each poll it asks, for every job, whether an occurrence is due; claiming
 * it is atomic in the database, so a job runs at most once per occurrence across every running
 * instance. A {@link TenantJob} then runs salon by salon: the active salons' ids from the registry,
 * each salon in a span and a transaction of its own with that salon set (T-7), one salon's failure
 * logged without stopping the others. How late each occurrence started is the meter
 * {@value #START_DELAY}. Every time is the application's clock.
 *
 * <p>On stop it starts nothing new; a run in progress stops between salons. A claim left by an
 * instance that stopped mid-run is released when its hold runs out.
 */
final class JobScheduler implements SmartLifecycle {

    static final String START_DELAY = "salon.jobs.start.delay";

    private static final Logger log = LoggerFactory.getLogger(JobScheduler.class);

    private final JobSettings settings;

    private final JobStore store;

    private final List<TenantJob> jobs;

    private final ActiveTenants tenants;

    private final TransactionTemplate transactions;

    private final Clock clock;

    private final Traces traces;

    private final MeterRegistry meters;

    private final String instance = "api-" + UUID.randomUUID();

    private volatile boolean running;

    private volatile boolean pollFailing;

    private Thread poller;

    JobScheduler(JobSettings settings, JobStore store, List<TenantJob> jobs, ActiveTenants tenants,
            PlatformTransactionManager transactionManager, Clock clock, Traces traces, MeterRegistry meters) {
        this.settings = settings;
        this.store = store;
        this.jobs = List.copyOf(jobs);
        this.tenants = tenants;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.traces = traces;
        this.meters = meters;
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        poller = Thread.ofVirtual().name("job-scheduler").start(this::pollUntilStopped);
        log.info("The job scheduler started with the jobs {}", jobs.stream().map(TenantJob::name).toList());
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        poller.interrupt();
        try {
            poller.join(Duration.ofSeconds(10));
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        log.info("The job scheduler stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Runs every job whose occurrence is due. Exposed for tests that drive the scheduler by hand. */
    void pollOnce() {
        for (TenantJob job : jobs) {
            if (!running) {
                return;
            }
            Instant now = clock.instant();
            Optional<Instant> occurrence = store.claim(job.name(), now, job.every(), instance,
                    now.plus(settings.hold()));
            if (occurrence.isEmpty()) {
                continue;
            }
            Timer.builder(START_DELAY).description("How late a job's occurrence started").tag("job", job.name())
                    .register(meters).record(Duration.between(occurrence.get(), now));
            try {
                runForEverySalon(job, now);
            }
            finally {
                store.finish(job.name(), instance, clock.instant());
            }
        }
    }

    private void runForEverySalon(TenantJob job, Instant now) {
        int failed = 0;
        List<java.util.UUID> ids = tenants.activeIds();
        for (java.util.UUID id : ids) {
            if (!running) {
                log.info("Job {} stopped before every salon had run", job.name());
                return;
            }
            Optional<CurrentTenant> salon = tenants.findActive(id);
            if (salon.isEmpty()) {
                continue;
            }
            if (!runFor(job, salon.get(), now)) {
                failed++;
            }
        }
        log.info("Job {} ran for {} salons, {} failed", job.name(), ids.size(), failed);
    }

    private boolean runFor(TenantJob job, CurrentTenant salon, Instant now) {
        try {
            return TenantContext.callAs(salon, () -> {
                try {
                    traces.inSpan("job." + job.name(), salon, null, () -> transactions.execute(status -> {
                        try {
                            job.runFor(salon, now);
                            return null;
                        }
                        catch (RuntimeException ex) {
                            throw ex;
                        }
                        catch (Exception ex) {
                            throw new IllegalStateException(ex);
                        }
                    }));
                    log.info("Job {} ran for the salon", job.name());
                    return true;
                }
                catch (Exception ex) {
                    log.error("Job {} failed for the salon; the other salons still run", job.name(), ex);
                    return false;
                }
            });
        }
        catch (Exception ex) {
            log.error("Job {} could not run for salon {}", job.name(), salon.id(), ex);
            return false;
        }
    }

    private void pollUntilStopped() {
        while (running) {
            try {
                pollOnce();
                if (pollFailing) {
                    pollFailing = false;
                    log.info("The job scheduler can reach its bookkeeping again");
                }
            }
            catch (RuntimeException ex) {
                if (!pollFailing) {
                    pollFailing = true;
                    log.warn("The job scheduler cannot reach its bookkeeping ({}); it keeps trying",
                            DatabaseUnavailable.isCauseOf(ex) ? DatabaseUnavailable.describe(ex) : ex.toString());
                }
            }
            try {
                Thread.sleep(settings.pollInterval());
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
