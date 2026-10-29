package com.salonplatform.shared.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The longest a request's database work waits for an answer on a connection it already holds
 * ({@code salon.database.request-timeout}). A database that accepts connections and then says
 * nothing would otherwise hold the request for the driver's socket timeout; with this it is answered
 * 503 within seconds, and the connection is given up so that the pool replaces it.
 *
 * <p>The limit is put on the connection's socket for as long as the work has it. The driver does
 * the waiting itself, and the pool puts its own limit back when the connection is returned.
 */
@Component
public class RequestDatabaseLimit {

    private final int timeoutMillis;

    RequestDatabaseLimit(@Value("${salon.database.request-timeout}") Duration timeout) {
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("salon.database.request-timeout must be positive");
        }
        this.timeoutMillis = Math.toIntExact(timeout.toMillis());
    }

    /** Limits how long this connection waits for an answer, until it goes back to the pool. */
    public void applyTo(Connection connection) throws SQLException {
        connection.setNetworkTimeout(Runnable::run, timeoutMillis);
    }
}
