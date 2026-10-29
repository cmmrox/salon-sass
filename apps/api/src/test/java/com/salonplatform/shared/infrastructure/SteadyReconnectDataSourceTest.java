package com.salonplatform.shared.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.AbstractDataSource;

/**
 * The pool's source of connections asks a database that has stopped answering again and again, at an
 * interval that does not grow, and reports every other failure at once.
 */
class SteadyReconnectDataSourceTest {

    private static final Duration INTERVAL = Duration.ofMillis(20);

    private final Driver driver = new Driver();

    private final AtomicBoolean poolIsClosed = new AtomicBoolean();

    private final SteadyReconnectDataSource source = new SteadyReconnectDataSource(driver, INTERVAL, poolIsClosed::get);

    @Test
    void aDatabaseThatStopsAnsweringIsAskedAgainUntilItAnswersAndThePoolSeesNoFailure() throws Exception {
        Connection first = driver.answers();
        assertThat(source.getConnection()).isSameAs(first);

        // More failures in a row than it takes the pool's own wait to grow to its longest.
        for (int attempt = 0; attempt < 12; attempt++) {
            driver.fails(attempt % 2 == 0 ? unreachable() : silent());
        }
        Connection afterTheSilence = driver.answers();

        assertThat(source.getConnection()).isSameAs(afterTheSilence);
        assertThat(driver.attempts()).hasSize(1 + 12 + 1);
    }

    @Test
    void theWaitBetweenAttemptsIsTheIntervalHoweverManyHaveFailed() throws Exception {
        driver.answers();
        source.getConnection();
        for (int attempt = 0; attempt < 12; attempt++) {
            driver.fails(unreachable());
        }
        driver.answers();

        source.getConnection();

        List<Long> attempts = driver.attempts().subList(1, driver.attempts().size());
        List<Duration> waits = new ArrayList<>();
        for (int attempt = 1; attempt < attempts.size(); attempt++) {
            waits.add(Duration.ofNanos(attempts.get(attempt) - attempts.get(attempt - 1)));
        }
        assertThat(waits).hasSize(12).allSatisfy(wait -> assertThat(wait).isGreaterThanOrEqualTo(INTERVAL));
        // Left to the pool, the last of these waits alone would be five seconds.
        assertThat(waits.get(waits.size() - 1))
                .as("the wait before the last attempt")
                .isLessThan(Duration.ofSeconds(1));
        assertThat(Duration.ofNanos(attempts.get(attempts.size() - 1) - attempts.get(0)))
                .as("all twelve waits together")
                .isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void aConnectionAskedForByRoleIsAskedForAgainInTheSameWay() throws Exception {
        driver.answers();
        source.getConnection("salon_app", "not-a-real-password");
        driver.fails(silent());
        driver.fails(unreachable());
        Connection afterTheSilence = driver.answers();

        assertThat(source.getConnection("salon_app", "not-a-real-password")).isSameAs(afterTheSilence);
        assertThat(driver.attempts()).hasSize(4);
        assertThat(driver.roles()).containsOnly("salon_app");
    }

    @Test
    void untilTheDatabaseHasBeenReachedOnceAFailureIsReportedAtOnce() {
        SQLException down = unreachable();
        driver.fails(down);
        driver.answers();

        assertThatThrownBy(source::getConnection).isSameAs(down);
        assertThat(driver.attempts())
                .as("an application started without its database fails to start")
                .hasSize(1);
    }

    @Test
    void aFailureThatIsNotTheDatabaseBeingUnreachableOrSilentIsReportedAtOnce() throws Exception {
        driver.answers();
        source.getConnection();
        SQLException refusedPassword = new SQLException("password authentication failed", "28P01");
        driver.fails(refusedPassword);
        driver.answers();

        assertThatThrownBy(source::getConnection).isSameAs(refusedPassword);
        assertThat(driver.attempts()).hasSize(2);
    }

    @Test
    void theAttemptsStopWhenThePoolIsClosed() throws Exception {
        driver.answers();
        source.getConnection();
        driver.fails(unreachable());
        driver.fails(unreachable());
        SQLException last = silent();
        driver.fails(last, () -> poolIsClosed.set(true));
        driver.answers();

        assertThatThrownBy(source::getConnection).isSameAs(last);
        assertThat(driver.attempts()).hasSize(1 + 3);
    }

    @Test
    void theAttemptsStopWhenTheThreadIsInterrupted() throws Exception {
        driver.answers();
        source.getConnection();
        SQLException down = unreachable();
        driver.fails(down, () -> Thread.currentThread().interrupt());
        driver.answers();

        try {
            assertThatThrownBy(source::getConnection).isSameAs(down);
            assertThat(Thread.currentThread().isInterrupted())
                    .as("the interruption is left for the caller to see")
                    .isTrue();
            assertThat(driver.attempts()).hasSize(2);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void anIntervalOfNothingIsRefused() {
        assertThatThrownBy(() -> new SteadyReconnectDataSource(driver, Duration.ZERO, poolIsClosed::get))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("salon.database.reconnect-interval");
    }

    /** What the driver reports when nothing listens at the database's address, or signing in times out. */
    private static SQLException unreachable() {
        return new SQLException("Connection attempt timed out.", "08001");
    }

    /** What it reports when the connection is opened and then nothing comes back. */
    private static SQLException silent() {
        return new SQLException("An I/O error occurred while sending to the backend.", "08006");
    }

    /** A driver that answers each attempt with what the test lined up for it, and notes when it was asked. */
    private static final class Driver extends AbstractDataSource {

        private final Deque<Outcome> outcomes = new ArrayDeque<>();

        private final List<Long> attempts = new ArrayList<>();

        private final List<String> roles = new ArrayList<>();

        Connection answers() {
            Connection connection = (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (proxy, method, arguments) -> switch (method.getName()) {
                        case "equals" -> proxy == arguments[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "toString" -> "a connection";
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            outcomes.add(new Outcome(connection, null, () -> {}));
            return connection;
        }

        void fails(SQLException failure) {
            fails(failure, () -> {});
        }

        void fails(SQLException failure, Runnable meanwhile) {
            outcomes.add(new Outcome(null, failure, meanwhile));
        }

        List<Long> attempts() {
            return attempts;
        }

        List<String> roles() {
            return roles;
        }

        @Override
        public Connection getConnection() throws SQLException {
            attempts.add(System.nanoTime());
            Outcome outcome = outcomes.remove();
            outcome.meanwhile().run();
            if (outcome.failure() != null) {
                throw outcome.failure();
            }
            return outcome.connection();
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            roles.add(username);
            return getConnection();
        }

        private record Outcome(Connection connection, SQLException failure, Runnable meanwhile) {}
    }
}
