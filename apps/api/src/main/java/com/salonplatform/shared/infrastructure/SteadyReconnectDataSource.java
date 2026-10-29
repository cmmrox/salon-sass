package com.salonplatform.shared.infrastructure;

import com.salonplatform.shared.api.DatabaseUnavailable;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Where the pool opens its connections: the driver, asked again at a short, steady interval for as
 * long as the database is unreachable or silent, instead of reporting each failure to the pool.
 *
 * <p>The pool waits between failed attempts to open a connection, twice as long after each failure
 * up to five seconds, and that wait is not a setting (HikariCP 7.0.2, {@code PoolEntryCreator}). By
 * the time a database has been silent for half a minute the pool is trying once every seven seconds,
 * and when the database answers again it is not asked until the wait in progress ends: up to five
 * more seconds of 503 for every salon. Asked through this class the pool never sees those failures,
 * so its wait never grows, and a database that answers again is found within one
 * {@code salon.database.reconnect-interval}.
 *
 * <p>What does not change:
 * <ul>
 * <li>Every attempt keeps the driver's own limits on connecting and signing in. Only the pool's
 * thread that adds connections ever waits here; a request waits for a connection from the pool, with
 * the pool's limit, exactly as before.</li>
 * <li>Until the database has been reached once, a failure is reported at once, so an application
 * started against a database that is not there still fails to start.</li>
 * <li>A failure that is not the database being unreachable or silent (a refused password, a missing
 * database) is reported at once and left to the pool's own, slower, schedule.</li>
 * <li>When the pool is closed the attempts stop.</li>
 * </ul>
 */
final class SteadyReconnectDataSource extends DelegatingDataSource {

    private static final Logger log = LoggerFactory.getLogger(SteadyReconnectDataSource.class);

    private final long intervalMillis;

    private final BooleanSupplier poolIsClosed;

    private volatile boolean reachedBefore;

    SteadyReconnectDataSource(DataSource driver, Duration interval, BooleanSupplier poolIsClosed) {
        super(driver);
        if (interval.toMillis() < 1) {
            throw new IllegalArgumentException("salon.database.reconnect-interval must be at least one millisecond");
        }
        this.intervalMillis = interval.toMillis();
        this.poolIsClosed = poolIsClosed;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return open(() -> obtainTargetDataSource().getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return open(() -> obtainTargetDataSource().getConnection(username, password));
    }

    private Connection open(Attempt attempt) throws SQLException {
        long silentSince = 0;
        boolean silent = false;
        while (true) {
            try {
                Connection connection = attempt.open();
                reachedBefore = true;
                if (silent) {
                    log.info(
                            "A connection to the database was opened again, {} ms after the first attempt that failed",
                            Duration.ofNanos(System.nanoTime() - silentSince).toMillis());
                }
                return connection;
            } catch (SQLException ex) {
                if (!reachedBefore || !DatabaseUnavailable.isCauseOf(ex) || poolIsClosed.getAsBoolean()) {
                    throw ex;
                }
                if (!silent) {
                    silent = true;
                    silentSince = System.nanoTime();
                    log.warn(
                            "No connection to the database could be opened ({}); trying again every {} ms until one is",
                            DatabaseUnavailable.describe(ex),
                            intervalMillis);
                }
                if (!waitForTheNextAttempt()) {
                    throw ex;
                }
            }
        }
    }

    /** False when the thread was interrupted, which ends the attempts. */
    private boolean waitForTheNextAttempt() {
        try {
            Thread.sleep(intervalMillis);
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @FunctionalInterface
    private interface Attempt {

        Connection open() throws SQLException;
    }
}
