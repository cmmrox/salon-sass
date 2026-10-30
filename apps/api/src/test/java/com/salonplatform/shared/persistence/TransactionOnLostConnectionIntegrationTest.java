package com.salonplatform.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;

import com.salonplatform.identity.repository.StaffAccountRepository;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.shared.web.DatabaseUnavailable;
import com.salonplatform.shared.web.NotFoundException;
import com.salonplatform.testing.StartedApplication;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import com.salonplatform.testing.TestSalons;
import com.salonplatform.testing.TestSalons.TestSalon;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * BUG-F02-015, for every way work runs in a transaction: when the database goes silent while the work
 * waits on it, the transaction's end reports that the database is unavailable (so the request is
 * answered 503), whatever the work concluded from the failure and however the end then fails on the
 * connection the driver gave up. Hibernate's {@code find} turns a lost connection in a transaction
 * already marked rollback-only into "no such row" (HHH-12472), so the work can even return normally.
 */
class TransactionOnLostConnectionIntegrationTest {

    private static final Duration LIMIT = Duration.ofSeconds(5);

    private static PostgreSQLContainer postgres;

    private static StartedApplication application;

    private static JdbcTemplate owner;

    private static TestSalon alpha;

    @BeforeAll
    static void start() {
        postgres = TestPostgres.newContainer();
        postgres.start();
        application = StartedApplication.against(postgres);
        owner = TestDatabase.of(postgres).asOwner();
        alpha = new TestSalons(owner).create("Alpha Lost", "#D6086A");
    }

    @AfterAll
    static void stop() {
        application.close();
        postgres.close();
    }

    @Test
    void aTenantTransactionWhoseLookupLostTheConnectionAndThatReturnsNormallyReportsTheDatabaseUnavailable() {
        StaffAccountRepository staff = application.bean(StaffAccountRepository.class);
        TransactionTemplate transactions = new TransactionTemplate(application.bean(PlatformTransactionManager.class));
        Throwable failure = whileSilent(
                "identity_staff",
                () -> TenantContext.callAs(
                        new TestSalons(owner).asCurrent(alpha),
                        () -> transactions.execute(status -> {
                            // The work treats "not found" as an answer, as a use case that turns it into 401 does.
                            Optional<?> found = staff.findById(UUID.randomUUID());
                            return found.isPresent();
                        })));

        assertThat(DatabaseUnavailable.isCauseOf(failure))
                .as("reported as the database being unavailable: %s", DatabaseUnavailable.describe(failure))
                .isTrue();
    }

    /**
     * The use case turns the lookup Hibernate answered with "no such row" into 404; on a lost connection
     * that answer is not true, so the transaction reports the database unavailable instead (503), and
     * keeps the use case's exception for the log.
     */
    @Test
    void aTenantTransactionWhoseWorkTurnedTheLostLookupInto404ReportsTheDatabaseUnavailable() {
        StaffAccountRepository staff = application.bean(StaffAccountRepository.class);
        TransactionTemplate transactions = new TransactionTemplate(application.bean(PlatformTransactionManager.class));
        Throwable failure = whileSilent(
                "identity_staff",
                () -> TenantContext.callAs(
                        new TestSalons(owner).asCurrent(alpha),
                        () -> transactions.execute(
                                status -> staff.findById(UUID.randomUUID()).orElseThrow(NotFoundException::new))));

        assertThat(DatabaseUnavailable.isCauseOf(failure))
                .as("reported as the database being unavailable: %s", DatabaseUnavailable.describe(failure))
                .isTrue();
        assertThat(failure)
                .isInstanceOfSatisfying(
                        TransactionSystemException.class,
                        ended -> assertThat(ended.getApplicationException()).isInstanceOf(NotFoundException.class));
    }

    /** On a database that answers, a row that is not there is still 404, and nothing else changes. */
    @Test
    void onALiveConnectionAGenuineNotFoundStaysNotFoundInBothKindsOfTransaction() {
        StaffAccountRepository staff = application.bean(StaffAccountRepository.class);
        TransactionTemplate transactions = new TransactionTemplate(application.bean(PlatformTransactionManager.class));
        Throwable tenant = catchThrowable(() -> TenantContext.callAs(
                new TestSalons(owner).asCurrent(alpha),
                () -> transactions.execute(
                        status -> staff.findById(UUID.randomUUID()).orElseThrow(NotFoundException::new))));
        assertThat(tenant).isExactlyInstanceOf(NotFoundException.class);

        PlatformDatabase platform = application.bean(PlatformDatabase.class);
        Throwable platformFailure = catchThrowable(() -> platform.inTransaction(status -> {
            long operators = platform.jdbc()
                    .sql("SELECT count(*) FROM operator_user WHERE email = 'nobody@example.invalid'")
                    .query(Long.class)
                    .single();
            if (operators == 0) {
                throw new NotFoundException();
            }
            return operators;
        }));
        assertThat(platformFailure).isExactlyInstanceOf(NotFoundException.class);
    }

    @Test
    void aTenantTransactionWhoseWorkFailedOnTheLostConnectionStillReportsThatFailure() {
        StaffAccountRepository staff = application.bean(StaffAccountRepository.class);
        TransactionTemplate transactions = new TransactionTemplate(application.bean(PlatformTransactionManager.class));
        Throwable failure = whileSilent(
                "identity_staff",
                () -> TenantContext.callAs(
                        new TestSalons(owner).asCurrent(alpha),
                        () -> transactions.execute(status -> staff.countByActiveTrue())));

        assertThat(DatabaseUnavailable.isCauseOf(failure))
                .as("reported as the database being unavailable: %s", DatabaseUnavailable.describe(failure))
                .isTrue();
    }

    @Test
    void aPlatformTransactionWhoseWorkFailedOnTheLostConnectionReportsTheDatabaseUnavailable() {
        PlatformDatabase platform = application.bean(PlatformDatabase.class);
        Throwable failure = whileSilent(
                "operator_user",
                () -> platform.inTransaction(status -> platform.jdbc()
                        .sql("SELECT count(*) FROM operator_user")
                        .query(Long.class)
                        .single()));

        assertThat(DatabaseUnavailable.isCauseOf(failure))
                .as("reported as the database being unavailable: %s", DatabaseUnavailable.describe(failure))
                .isTrue();
    }

    /**
     * Runs the work while the owning role holds a lock it waits on, makes the database silent once it
     * waits, and returns what the work threw (it must throw, within the limit).
     */
    private static Throwable whileSilent(String table, Callable<?> work) {
        try (Connection locker = TestDatabase.of(postgres).connectAsOwner();
                ExecutorService runner = Executors.newVirtualThreadPerTaskExecutor()) {
            locker.setAutoCommit(false);
            try (Statement lock = locker.createStatement()) {
                lock.execute("LOCK TABLE " + table + " IN ACCESS EXCLUSIVE MODE");
            }
            Future<?> running = runner.submit(work);
            await().atMost(Duration.ofSeconds(2))
                    .pollInterval(Duration.ofMillis(20))
                    .until(() -> owner.queryForObject(
                                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                                            + " AND datname = current_database()",
                                    Long.class)
                            >= 1);
            freeze();
            Throwable failure;
            try {
                failure = catchThrowable(() -> running.get(LIMIT.toMillis(), TimeUnit.MILLISECONDS));
            } finally {
                thaw();
            }
            locker.rollback();
            assertThat(failure).as("the work must fail, not finish").isNotNull();
            return failure.getCause() != null ? failure.getCause() : failure;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        } finally {
            // The pool replaces the connections it gave up; the next case starts on a database that answers.
            await().atMost(LIMIT).ignoreExceptions().until(() -> owner.queryForObject("SELECT 1", Integer.class) == 1);
        }
    }

    private static void freeze() {
        postgres.getDockerClient().pauseContainerCmd(postgres.getContainerId()).exec();
    }

    private static void thaw() {
        Boolean paused = postgres.getDockerClient()
                .inspectContainerCmd(postgres.getContainerId())
                .exec()
                .getState()
                .getPaused();
        if (Boolean.TRUE.equals(paused)) {
            postgres.getDockerClient()
                    .unpauseContainerCmd(postgres.getContainerId())
                    .exec();
        }
    }
}
