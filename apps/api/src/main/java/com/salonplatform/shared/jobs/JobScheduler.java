package com.salonplatform.shared.jobs;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
 * <p>On stop it starts nothing new and lets the salon whose run is in progress finish, within
 * {@value #STOP_LIMIT_SECONDS} seconds; the run then stops between salons. A claim left by an instance
 * that stopped mid-run is released when its hold runs out.
 */
final class JobScheduler implements SmartLifecycle {

    static final String START_DELAY = "salon.jobs.start.delay";

    /** How long a stop waits for the salon in progress before it interrupts the run. */
    private static final int STOP_LIMIT_SECONDS = 10;

    private static final Logger log = LoggerFactory.getLogger(JobScheduler.class);

    private final JobSettings settings;

    private final JobStore store;

    private final List<TenantJob> jobs;

    private final List<PlatformJob> platformJobs;

    private final ActiveTenants tenants;

    private final TransactionTemplate transactions;

    private final Clock clock;

    private final Traces traces;

    private final MeterRegistry meters;

    private final String instance = "api-" + UUID.randomUUID();

    private volatile boolean running;

    private volatile boolean pollFailing;

    /** Released on stop, so that the wait between polls ends at once and nothing in progress is interrupted. */
    private volatile CountDownLatch stopping = new CountDownLatch(1);

    private Thread poller;

    JobScheduler(JobSettings settings, JobStore store, List<TenantJob> jobs, ActiveTenants tenants,
            PlatformTransactionManager transactionManager, Clock clock, Traces traces, MeterRegistry meters) {
        this(settings, store, jobs, List.of(), tenants, transactionManager, clock, traces, meters);
    }

    JobScheduler(JobSettings settings, JobStore store, List<TenantJob> jobs, List<PlatformJob> platformJobs,
            ActiveTenants tenants, PlatformTransactionManager transactionManager, Clock clock, Traces traces,
            MeterRegistry meters) {
        this.settings = settings;
        this.store = store;
        this.jobs = List.copyOf(jobs);
        this.platformJobs = List.copyOf(platformJobs);
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
        stopping = new CountDownLatch(1);
        poller = Thread.ofVirtual().name("job-scheduler").start(this::pollUntilStopped);
        log.info("The job scheduler started with the jobs {} and the platform jobs {}",
                jobs.stream().map(TenantJob::name).toList(), platformJobs.stream().map(PlatformJob::name).toList());
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        stopping.countDown();
        try {
            if (!poller.join(Duration.ofSeconds(STOP_LIMIT_SECONDS))) {
                log.warn("A job's run did not finish within {} s of the stop and is interrupted", STOP_LIMIT_SECONDS);
                poller.interrupt();
                poller.join(Duration.ofSeconds(1));
            }
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

    /** One poll: claims and runs every job whose occurrence is due. */
    private void pollOnce() {
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
        for (PlatformJob job : platformJobs) {
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
                job.run(now);
                log.info("Platform job {} ran", job.name());
            }
            catch (RuntimeException ex) {
                log.error("Platform job {} failed; its next occurrence tries again", job.name(), ex);
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

    /**
     * One salon's run: its salon set, a span of its own, and one transaction. What it logs, the
     * failure included, is written inside the span, so that every line carries the salon and the
     * trace.
     */
    private boolean runFor(TenantJob job, CurrentTenant salon, Instant now) {
        try {
            return TenantContext.callAs(salon, () -> traces.inSpan("job." + job.name(), salon, null, () -> {
                try {
                    transactions.executeWithoutResult(status -> {
                        try {
                            job.runFor(salon, now);
                        }
                        catch (RuntimeException ex) {
                            throw ex;
                        }
                        catch (Exception ex) {
                            throw new IllegalStateException(ex);
                        }
                    });
                    log.info("Job {} ran for the salon", job.name());
                    return true;
                }
                catch (RuntimeException ex) {
                    traces.failed(ex);
                    log.error("Job {} failed for the salon; the other salons still run", job.name(), ex);
                    return false;
                }
            }));
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
                if (stopping.await(settings.pollInterval().toMillis(), TimeUnit.MILLISECONDS)) {
                    return;
                }
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
