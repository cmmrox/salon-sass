package com.salonplatform.shared.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.salonplatform.shared.observability.Traces;
import com.salonplatform.shared.outbox.Outbox;
import com.salonplatform.shared.tenancy.ActiveTenants;
import com.salonplatform.shared.tenancy.CurrentTenant;
import com.salonplatform.testing.CapturedLogs;
import com.salonplatform.testing.MovableClock;
import com.salonplatform.testing.StartedApplication;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import com.salonplatform.testing.TestSalons;
import com.salonplatform.testing.TestSalons.TestSalon;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The scheduler of recurring jobs against PostgreSQL, on a clock the test moves: a job runs salon
 * by salon with that salon set (T-7) and one salon's failure does not stop the others; an
 * occurrence runs once across two schedulers; a job held by an instance that stopped runs again
 * only once its hold has run out; and the same work with no salon set reads and writes nothing.
 *
 * <p>The application under test runs no scheduler of its own (the test profile); each test starts
 * the schedulers it needs, on the application's own beans.
 */
class JobSchedulerIntegrationTest {

    private static final Duration HOURLY = Duration.ofHours(1);

    private static TestDatabase database;

    private static StartedApplication application;

    private static JdbcTemplate owner;

    private static TestSalon alpha;

    private static TestSalon beta;

    private static TestSalon gamma;

    private static TestSalon suspended;

    private final MovableClock clock = new MovableClock(Instant.parse("2026-10-12T08:00:00Z"));

    private final List<JobScheduler> schedulers = new ArrayList<>();

    @BeforeAll
    static void anApplicationWithThreeActiveSalonsAndASuspendedOne() {
        database = TestDatabase.createEmpty(TestPostgres.shared());
        application = database.start();
        owner = database.asOwner();
        TestSalons salons = new TestSalons(owner);
        alpha = salons.create("Jobs Alpha", "#111111");
        beta = salons.create("Jobs Beta", "#222222");
        gamma = salons.create("Jobs Gamma", "#333333");
        suspended = salons.create("Jobs Suspended", "#444444");
        salons.suspend(suspended);
    }

    @AfterAll
    static void stop() {
        application.close();
    }

    @AfterEach
    void stopSchedulers() {
        schedulers.forEach(JobScheduler::stop);
    }

    @Test
    void aJobRunsForEachActiveSalonWithThatSalonSetAndOneSalonsFailureDoesNotStopTheOthers() {
        RecordingJob job = new RecordingJob(beta.id());

        try (CapturedLogs logs = CapturedLogs.of("com.salonplatform.shared.jobs")) {
            start(job);
            await(() -> job.runs.size() == 3);
            pause(400);

            assertThat(job.runs)
                    .as("once for each active salon, and not for the suspended one")
                    .extracting(Run::salon)
                    .containsExactlyInAnyOrder(alpha.id(), beta.id(), gamma.id());
            assertThat(job.runs).allSatisfy(run -> {
                assertThat(run.settingInTheDatabase())
                        .as("app.tenant_id of the transaction")
                        .isEqualTo(run.salon().toString());
                assertThat(run.brandsSeen()).as("only its own salon's rows").containsExactly(run.salon());
                assertThat(run.salonOnLogLines())
                        .as("tenant.id of the log lines")
                        .isEqualTo(run.salon().toString());
                assertThat(run.now()).isEqualTo(clock.instant());
            });

            assertThat(messagesOf(job, alpha)).as("alpha's work was committed").isEqualTo(1);
            assertThat(messagesOf(job, gamma)).as("gamma's work was committed").isEqualTo(1);
            assertThat(messagesOf(job, beta))
                    .as("beta's failed and left nothing behind")
                    .isZero();

            List<ILoggingEvent> failures = logs.events(line -> line.getLevel() == Level.ERROR);
            assertThat(failures).singleElement().satisfies(line -> {
                assertThat(line.getFormattedMessage()).contains(job.name()).contains("failed for the salon");
                assertThat(CapturedLogs.context(line))
                        .containsEntry("tenant.id", beta.id().toString())
                        .containsKey("trace.id");
            });
            assertThat(logs.events(line -> line.getFormattedMessage().contains("ran for the salon")))
                    .extracting(line -> CapturedLogs.context(line).get("tenant.id"))
                    .containsExactlyInAnyOrder(alpha.id().toString(), gamma.id().toString());
        }

        Timer delay = application
                .bean(MeterRegistry.class)
                .find(JobScheduler.START_DELAY)
                .tag("job", job.name())
                .timer();
        assertThat(delay).as("how late the occurrence started is metered").isNotNull();
        assertThat(delay.count()).isEqualTo(1);
    }

    @Test
    void anOccurrenceRunsOnceAcrossTwoSchedulersAndMissedOccurrencesRunOnce() {
        String name = jobName();
        RecordingJob onOne = new RecordingJob(name, null);
        RecordingJob onTwo = new RecordingJob(name, null);

        start(onOne);
        start(onTwo);
        await(() -> runsOf(onOne, onTwo) == 3);
        pause(500);
        assertThat(runsOf(onOne, onTwo))
                .as("the first occurrence, once for each of three salons")
                .isEqualTo(3);

        clock.advance(HOURLY);
        await(() -> runsOf(onOne, onTwo) == 6);
        pause(500);
        assertThat(runsOf(onOne, onTwo)).as("the next occurrence, once").isEqualTo(6);

        clock.advance(HOURLY.multipliedBy(5));
        await(() -> runsOf(onOne, onTwo) == 9);
        pause(500);
        assertThat(runsOf(onOne, onTwo))
                .as("five occurrences were missed: the job runs once, not five times")
                .isEqualTo(9);

        clock.advance(Duration.ofMinutes(59));
        pause(500);
        assertThat(runsOf(onOne, onTwo)).as("not before the next occurrence").isEqualTo(9);
    }

    @Test
    void aJobHeldByAnInstanceThatStoppedRunsAgainOnlyOnceItsHoldHasRunOut() {
        RecordingJob job = new RecordingJob(null);
        Instant claimedAt = clock.instant();
        assertThat(application
                        .bean(JobStore.class)
                        .claim(
                                job.name(),
                                claimedAt,
                                HOURLY,
                                "an-instance-that-stopped",
                                claimedAt.plus(Duration.ofHours(3))))
                .as("claimed by the instance that then stopped")
                .contains(claimedAt);

        start(job);
        clock.advance(Duration.ofHours(2));
        pause(500);
        assertThat(job.runs)
                .as("the next occurrence is due, but the job is still held")
                .isEmpty();

        clock.advance(Duration.ofMinutes(61));
        await(() -> job.runs.size() == 3);
    }

    /** T-7: a job with no tenant context can read nothing, and write nothing. */
    @Test
    void theSameWorkWithNoSalonSetReadsNothingAndWritesNothing() {
        TransactionTemplate transactions = new TransactionTemplate(application.bean(PlatformTransactionManager.class));
        JdbcTemplate jdbc = application.jdbc();
        Outbox outbox = application.bean(Outbox.class);

        transactions.executeWithoutResult(status -> {
            for (String table : new String[] {"salon_brand", "outbox_message", "audit_entry"}) {
                assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class))
                        .as(table)
                        .isZero();
            }
        });
        assertThatIllegalStateException()
                .isThrownBy(() -> transactions.executeWithoutResult(status -> outbox.enqueue("job-test", Map.of())));
        assertThatExceptionOfType(DataAccessException.class)
                .isThrownBy(() -> transactions.executeWithoutResult(status -> jdbc.update(
                        "INSERT INTO outbox_message (tenant_id, id, channel, payload, status, next_attempt_at, "
                                + "created_at, updated_at) VALUES (?, gen_random_uuid(), 'job-test', '{}', 'pending', now(), now(), "
                                + "now())",
                        alpha.id())))
                .satisfies(refused -> assertThat(refused.getCause())
                        .isInstanceOfSatisfying(
                                SQLException.class,
                                sql -> assertThat(sql.getSQLState()).isEqualTo("42501")));
    }

    private void start(TenantJob job) {
        JobScheduler scheduler = new JobScheduler(
                new JobSettings(true, Duration.ofMillis(50), Duration.ofMinutes(10), HOURLY),
                application.bean(JobStore.class),
                List.of(job),
                application.bean(ActiveTenants.class),
                application.bean(PlatformTransactionManager.class),
                clock,
                application.bean(Traces.class),
                application.bean(MeterRegistry.class));
        schedulers.add(scheduler);
        scheduler.start();
    }

    private static int runsOf(RecordingJob... jobs) {
        int runs = 0;
        for (RecordingJob job : jobs) {
            runs += job.runs.size();
        }
        return runs;
    }

    private static int messagesOf(RecordingJob job, TestSalon salon) {
        return owner.queryForObject(
                "SELECT count(*) FROM outbox_message WHERE tenant_id = ? AND channel = ?",
                Integer.class,
                salon.id(),
                job.name());
    }

    private static String jobName() {
        return "test-" + UUID.randomUUID().toString().substring(0, 13);
    }

    private static void await(BooleanSupplier condition) {
        Instant until = Instant.now().plusSeconds(10);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(until)) {
                throw new AssertionError("it did not happen within 10 seconds");
            }
            pause(25);
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    /** What one run for one salon saw. */
    private record Run(
            UUID salon, Instant now, String settingInTheDatabase, List<UUID> brandsSeen, String salonOnLogLines) {}

    /**
     * A job that records what each run saw and writes one outbox message on a channel named after
     * itself, then fails for one salon if it was told to.
     */
    private static final class RecordingJob implements TenantJob {

        private final String name;

        private final UUID failsFor;

        private final List<Run> runs = new CopyOnWriteArrayList<>();

        RecordingJob(UUID failsFor) {
            this(jobName(), failsFor);
        }

        RecordingJob(String name, UUID failsFor) {
            this.name = name;
            this.failsFor = failsFor;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Duration every() {
            return HOURLY;
        }

        @Override
        public void runFor(CurrentTenant salon, Instant now) {
            JdbcTemplate jdbc = application.jdbc();
            runs.add(new Run(
                    salon.id(),
                    now,
                    jdbc.queryForObject("SELECT current_setting('app.tenant_id', true)", String.class),
                    jdbc.queryForList("SELECT tenant_id FROM salon_brand", UUID.class),
                    MDC.get("tenant.id")));
            application.bean(Outbox.class).enqueue(name, Map.of("for", salon.slug()));
            if (salon.id().equals(failsFor)) {
                throw new IllegalStateException("the job fails for this salon");
            }
        }
    }
}
