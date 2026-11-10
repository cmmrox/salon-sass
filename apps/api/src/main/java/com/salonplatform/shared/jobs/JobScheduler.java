package com.salonplatform.shared.jobs;

import com.salonplatform.shared.observability.Traces;
import com.salonplatform.shared.persistence.DatabaseLimits;
import com.salonplatform.shared.tenancy.ActiveTenants;
import com.salonplatform.shared.tenancy.CurrentTenant;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.shared.web.DatabaseUnavailable;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs the recurring jobs. Each poll it asks, for every job not already running here, whether an occurrence is
 * due; claiming it is atomic in the database, so a job runs at most once per occurrence across every running
 * instance. A claimed job runs on a thread of its own, at most {@code salon.jobs.concurrent} at once, so a slow
 * job (an export, billing) holds up no other (platform review M3). A {@link TenantJob} then runs salon by salon:
 * the active salons' ids from the registry, each salon in a span and a transaction of its own with that salon set
 * (T-7), one salon's failure logged without stopping the others. How late each occurrence started is the meter
 * {@value #START_DELAY}. Every time is the application's clock.
 *
 * <p>While a job runs, its claim is renewed every third of {@code salon.jobs.hold}, so another instance can claim
 * it only once this one has stopped renewing for a whole hold: a job that runs longer than its hold does not run
 * twice at once. A run that finds its claim taken over stops before its next salon.
 *
 * <p>On stop it starts nothing new and lets each salon whose run is in progress finish, within {@value
 * #STOP_LIMIT_SECONDS} seconds; runs then stop between salons. A claim left by an instance that stopped mid-run
 * is released when its hold runs out.
 */
final class JobScheduler implements SmartLifecycle {

    static final String START_DELAY = "salon.jobs.start.delay";

    /** How long a stop waits for the salons in progress before it interrupts the runs. */
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

    private final TenantJobActivity activity;

    private final String instance = "api-" + UUID.randomUUID();

    /** The jobs running on this instance now, by name: a running job is not claimed again here. */
    private final Set<String> runningHere = ConcurrentHashMap.newKeySet();

    private final Semaphore lanes;

    private volatile boolean running;

    private volatile boolean pollFailing;

    /** Released on stop, so that the wait between polls ends at once and nothing in progress is interrupted. */
    private volatile CountDownLatch stopping = new CountDownLatch(1);

    private Thread poller;

    private ExecutorService runs;

    JobScheduler(
            JobSettings settings,
            JobStore store,
            List<TenantJob> jobs,
            ActiveTenants tenants,
            PlatformTransactionManager transactionManager,
            Clock clock,
            Traces traces,
            MeterRegistry meters) {
        this(settings, store, jobs, List.of(), tenants, transactionManager, clock, traces, meters);
    }

    JobScheduler(
            JobSettings settings,
            JobStore store,
            List<TenantJob> jobs,
            List<PlatformJob> platformJobs,
            ActiveTenants tenants,
            PlatformTransactionManager transactionManager,
            Clock clock,
            Traces traces,
            MeterRegistry meters) {
        this(
                settings,
                store,
                jobs,
                platformJobs,
                tenants,
                transactionManager,
                clock,
                traces,
                meters,
                new TenantJobActivity());
    }

    JobScheduler(
            JobSettings settings,
            JobStore store,
            List<TenantJob> jobs,
            List<PlatformJob> platformJobs,
            ActiveTenants tenants,
            PlatformTransactionManager transactionManager,
            Clock clock,
            Traces traces,
            MeterRegistry meters,
            TenantJobActivity activity) {
        this.activity = activity;
        this.settings = settings;
        this.store = store;
        this.jobs = List.copyOf(jobs);
        this.platformJobs = List.copyOf(platformJobs);
        this.tenants = tenants;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.traces = traces;
        this.meters = meters;
        this.lanes = new Semaphore(settings.concurrent());
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        stopping = new CountDownLatch(1);
        runs = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("job-run-", 0).factory());
        poller = Thread.ofVirtual().name("job-scheduler").start(this::pollUntilStopped);
        log.info(
                "The job scheduler started with the jobs {} and the platform jobs {}",
                jobs.stream().map(TenantJob::name).toList(),
                platformJobs.stream().map(PlatformJob::name).toList());
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        stopping.countDown();
        try {
            poller.join(Duration.ofSeconds(STOP_LIMIT_SECONDS));
            runs.shutdown();
            if (!runs.awaitTermination(STOP_LIMIT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("A job's run did not finish within {} s of the stop and is interrupted", STOP_LIMIT_SECONDS);
                runs.shutdownNow();
                runs.awaitTermination(1, TimeUnit.SECONDS);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        log.info("The job scheduler stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** A job of either kind, as the poll sees it. */
    private record Scheduled(String name, Duration every, Run run) {}

    /** One occurrence's run, told how to know that it should stop between salons. */
    @FunctionalInterface
    private interface Run {
        void run(Instant now, BooleanSupplier keepGoing);
    }

    private List<Scheduled> everyJob() {
        List<Scheduled> all = new ArrayList<>();
        for (TenantJob job : jobs) {
            all.add(new Scheduled(job.name(), job.every(), (now, keepGoing) -> runForEverySalon(job, now, keepGoing)));
        }
        for (PlatformJob job : platformJobs) {
            all.add(new Scheduled(job.name(), job.every(), (now, keepGoing) -> runPlatformJob(job, now)));
        }
        return all;
    }

    /** One poll: claims every job whose occurrence is due and that is not running here, while a lane is free. */
    private void pollOnce() {
        for (Scheduled job : everyJob()) {
            if (!running) {
                return;
            }
            if (runningHere.contains(job.name())) {
                continue;
            }
            if (!lanes.tryAcquire()) {
                return;
            }
            boolean started = false;
            try {
                Instant now = clock.instant();
                Optional<Instant> occurrence =
                        store.claim(job.name(), now, job.every(), instance, now.plus(settings.hold()));
                if (occurrence.isEmpty()) {
                    continue;
                }
                Timer.builder(START_DELAY)
                        .description("How late a job's occurrence started")
                        .tag("job", job.name())
                        .register(meters)
                        .record(Duration.between(occurrence.get(), now));
                runningHere.add(job.name());
                runs.execute(() -> {
                    try {
                        runHeld(job, now);
                    } finally {
                        runningHere.remove(job.name());
                        lanes.release();
                    }
                });
                started = true;
            } finally {
                if (!started) {
                    lanes.release();
                }
            }
        }
    }

    /** Runs a claimed occurrence, renewing its claim while it runs, and lets go of it at the end. */
    private void runHeld(Scheduled job, Instant now) {
        Hold hold = new Hold(job.name());
        Thread renewing = Thread.ofVirtual().name("job-hold-" + job.name()).start(hold::renewUntilDone);
        try {
            job.run().run(now, () -> running && !hold.lost());
        } finally {
            hold.done();
            try {
                renewing.join(Duration.ofSeconds(1));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            try {
                store.finish(job.name(), instance, clock.instant());
            } catch (RuntimeException ex) {
                log.warn("Job {} could not let go of its claim; it is released when its hold runs out", job.name(), ex);
            }
        }
    }

    /** A running job's claim, renewed every third of the hold until the run is done. */
    private final class Hold {

        private final String job;

        private final CountDownLatch done = new CountDownLatch(1);

        private volatile boolean lost;

        Hold(String job) {
            this.job = job;
        }

        void renewUntilDone() {
            Duration every = settings.hold().dividedBy(3);
            try {
                while (!done.await(every.toMillis(), TimeUnit.MILLISECONDS)) {
                    renew();
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }

        private void renew() {
            try {
                Instant now = clock.instant();
                if (!store.renew(job, instance, now.plus(settings.hold()))) {
                    lost = true;
                    log.error("Job {} lost its claim to another instance; its run stops before the next salon", job);
                }
            } catch (RuntimeException ex) {
                log.warn("Job {} could not renew its claim; it tries again", job, ex);
            }
        }

        boolean lost() {
            return lost;
        }

        void done() {
            done.countDown();
        }
    }

    private void runPlatformJob(PlatformJob job, Instant now) {
        try {
            // Long work: its transactions set their own limits, not a request's (decision 120).
            DatabaseLimits.longWork(() -> job.run(now));
            log.info("Platform job {} ran", job.name());
        } catch (RuntimeException ex) {
            log.error("Platform job {} failed; its next occurrence tries again", job.name(), ex);
        }
    }

    private void runForEverySalon(TenantJob job, Instant now, BooleanSupplier keepGoing) {
        int failed = 0;
        List<UUID> ids = tenants.activeIds();
        for (UUID id : ids) {
            if (!keepGoing.getAsBoolean()) {
                log.info("Job {} stopped before every salon had run", job.name());
                return;
            }
            Optional<CurrentTenant> salon = tenants.findActive(id);
            if (salon.isEmpty()) {
                continue;
            }
            boolean succeeded = runFor(job, salon.get(), now);
            activity.ran(id, succeeded, now);
            if (!succeeded) {
                failed++;
            }
        }
        // A suspended salon's jobs are skipped until it is active again (decision 032); counted so the
        // local background-state hook can show it.
        List<UUID> skipped = List.of();
        try {
            skipped = tenants.suspendedIds();
        } catch (RuntimeException ex) {
            log.debug("The suspended salons could not be read to count skipped runs", ex);
        }
        skipped.forEach(id -> activity.skipped(id, now));
        log.info(
                "Job {} ran for {} salons, {} failed, {} suspended salons skipped",
                job.name(),
                ids.size(),
                failed,
                skipped.size());
    }

    /**
     * One salon's run: its salon set, a span of its own, and one transaction. What it logs, the
     * failure included, is written inside the span, so that every line carries the salon and the
     * trace.
     */
    private boolean runFor(TenantJob job, CurrentTenant salon, Instant now) {
        try {
            return TenantContext.callAs(
                    salon,
                    () -> traces.inSpan("job." + job.name(), salon, null, () -> {
                        try {
                            // Long work: its transaction sets its own limits, not a request's (decision 120).
                            DatabaseLimits.longWork(() -> transactions.executeWithoutResult(status -> {
                                try {
                                    job.runFor(salon, now);
                                } catch (RuntimeException ex) {
                                    throw ex;
                                } catch (Exception ex) {
                                    throw new IllegalStateException(ex);
                                }
                            }));
                            log.info("Job {} ran for the salon", job.name());
                            return true;
                        } catch (RuntimeException ex) {
                            traces.failed(ex);
                            log.error("Job {} failed for the salon; the other salons still run", job.name(), ex);
                            return false;
                        }
                    }));
        } catch (Exception ex) {
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
            } catch (RuntimeException ex) {
                if (!pollFailing) {
                    pollFailing = true;
                    log.warn(
                            "The job scheduler cannot reach its bookkeeping ({}); it keeps trying",
                            DatabaseUnavailable.isCauseOf(ex) ? DatabaseUnavailable.describe(ex) : ex.toString());
                }
            }
            try {
                if (stopping.await(settings.pollInterval().toMillis(), TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
