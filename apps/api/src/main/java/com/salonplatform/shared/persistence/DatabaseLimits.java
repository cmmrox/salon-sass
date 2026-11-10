package com.salonplatform.shared.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.core.env.Environment;

/**
 * The limits on the application's database work (decision 120), kept by the database as well as by the driver.
 *
 * <ul>
 *   <li><b>Every connection</b> of the tenant and platform pools is opened with the server's own limits
 *       ({@code salon.database.statement-timeout}, {@code lock-timeout}, {@code idle-in-transaction-timeout} and
 *       {@code client-check-interval}), passed as the driver's {@code options}, so that the database itself stops
 *       a statement that runs too long, a wait for a lock, and a transaction left open, and frees what they hold.
 *       The roles are created outside the migrations (infra/postgres) and the owning role may not change another
 *       role's settings, so the limits are the connection's, not the role's.
 *   <li><b>A request's transaction</b> also waits at most {@code salon.database.request-timeout} for an answer on
 *       its connection (the driver's network timeout): what bounds it when the database is silent, as F01-S02
 *       requires. The server's statement limit is shorter, so a slow statement is cancelled by the database,
 *       which answers, before the driver gives the connection up.
 *   <li><b>Long work</b> (recurring jobs, exports and their download, the erasers), run through {@link
 *       #longWork}, sets its own, longer limits on each transaction it opens ({@code salon.database.long-work.*}),
 *       so neither the request's network timeout nor the connection's statement limit caps it.
 *   <li><b>Migrations</b> run on their own connection with their own explicit limits
 *       ({@code salon.database.migration.*}), under the driver's socket timeout.
 * </ul>
 */
public final class DatabaseLimits {

    /** Bound while long work runs, for the transactions it opens. */
    private static final ScopedValue<Boolean> LONG_WORK = ScopedValue.newInstance();

    /** How much longer the driver waits than the server's statement limit, so that the server answers first. */
    private static final Duration DRIVER_MARGIN = Duration.ofSeconds(10);

    /** The limits the server keeps on one session or transaction. */
    private record ServerLimits(Duration statement, Duration lock, Duration idleInTransaction) {

        ServerLimits {
            if (lock.compareTo(statement) > 0) {
                throw new IllegalStateException("A lock timeout (" + lock
                        + ") must not be longer than its statement timeout (" + statement + ")");
            }
        }
    }

    private final Duration requestTimeout;

    private final Duration clientCheckInterval;

    private final ServerLimits connection;

    private final ServerLimits longWork;

    private final ServerLimits migration;

    private DatabaseLimits(
            Duration requestTimeout,
            Duration clientCheckInterval,
            ServerLimits connection,
            ServerLimits longWork,
            ServerLimits migration) {
        if (connection.statement().compareTo(requestTimeout) >= 0) {
            throw new IllegalStateException("salon.database.statement-timeout (" + connection.statement()
                    + ") must be shorter than salon.database.request-timeout (" + requestTimeout
                    + "), so that the database stops a slow statement before the driver gives the connection up");
        }
        this.requestTimeout = requestTimeout;
        this.clientCheckInterval = clientCheckInterval;
        this.connection = connection;
        this.longWork = longWork;
        this.migration = migration;
    }

    /** The limits in the environment's settings, each required and positive. */
    static DatabaseLimits from(Environment environment) {
        return new DatabaseLimits(
                positive(environment, "salon.database.request-timeout"),
                positive(environment, "salon.database.client-check-interval"),
                server(environment, "salon.database."),
                server(environment, "salon.database.long-work."),
                server(environment, "salon.database.migration."));
    }

    /**
     * Runs work that legitimately takes longer than a request (a job, an export, an eraser): each transaction it
     * opens, on either pool, has the long-work limits instead of the request's.
     */
    public static <T> T longWork(Supplier<T> work) {
        return ScopedValue.where(LONG_WORK, Boolean.TRUE).call(work::get);
    }

    /** As {@link #longWork(Supplier)}, for work that returns nothing. */
    public static void longWork(Runnable work) {
        ScopedValue.where(LONG_WORK, Boolean.TRUE).run(work);
    }

    /** Whether the current work is long work. */
    static boolean isLongWork() {
        return LONG_WORK.isBound();
    }

    /**
     * The driver's {@code options} for every connection of the application's pools: the server's limits on its
     * statements, its lock waits and an idle open transaction, and how often it checks that the client is still
     * there while a statement runs, so that a statement whose caller gave up is stopped.
     */
    String connectionOptions() {
        return options(connection) + " -c client_connection_check_interval=" + millis(clientCheckInterval);
    }

    /** The driver's {@code options} for the migrations' connection. */
    String migrationOptions() {
        return options(migration);
    }

    /**
     * Sets the limits of a transaction that is starting on this connection: for a request, the driver's wait
     * for an answer; for long work, a longer one, and the long-work limits on the server for this transaction
     * only. The pool puts its own network timeout back when the connection is returned, and the server's end
     * with the transaction.
     */
    void applyToTransaction(Connection connection) throws SQLException {
        if (!isLongWork()) {
            applyTo(connection);
            return;
        }
        connection.setNetworkTimeout(
                Runnable::run,
                Math.toIntExact(longWork.statement().plus(DRIVER_MARGIN).toMillis()));
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET LOCAL statement_timeout = " + quoted(longWork.statement())
                    + "; SET LOCAL lock_timeout = " + quoted(longWork.lock())
                    + "; SET LOCAL idle_in_transaction_session_timeout = " + quoted(longWork.idleInTransaction()));
        }
    }

    /**
     * Limits how long a request's work waits for an answer on this connection, until it goes back to the pool: for
     * a statement outside a transaction (the registry's lookups) as for a transaction's.
     */
    public void applyTo(Connection connection) throws SQLException {
        connection.setNetworkTimeout(Runnable::run, Math.toIntExact(requestTimeout.toMillis()));
    }

    private static String options(ServerLimits limits) {
        return "-c statement_timeout=" + millis(limits.statement())
                + " -c lock_timeout=" + millis(limits.lock())
                + " -c idle_in_transaction_session_timeout=" + millis(limits.idleInTransaction());
    }

    private static ServerLimits server(Environment environment, String prefix) {
        return new ServerLimits(
                positive(environment, prefix + "statement-timeout"),
                positive(environment, prefix + "lock-timeout"),
                positive(environment, prefix + "idle-in-transaction-timeout"));
    }

    private static Duration positive(Environment environment, String name) {
        Duration value = environment.getRequiredProperty(name, Duration.class);
        if (value.isNegative() || value.isZero()) {
            throw new IllegalStateException(name + " must be positive");
        }
        return value;
    }

    private static String millis(Duration duration) {
        return duration.toMillis() + "ms";
    }

    private static String quoted(Duration duration) {
        return "'" + millis(duration) + "'";
    }
}
