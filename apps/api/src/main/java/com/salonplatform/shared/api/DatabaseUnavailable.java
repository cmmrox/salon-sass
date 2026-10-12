package com.salonplatform.shared.api;

import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;

/**
 * Whether a failure means the database could not be reached or did not answer in time, rather than
 * that something was wrong with the request or the code: no connection within the pool's limit, a
 * connection refused, or a connection that went silent past the request's limit. Such a request is
 * answered 503 {@code service_unavailable}, and such a failure to open a connection is one the pool's
 * source of connections tries again steadily.
 */
public final class DatabaseUnavailable {

    /** SQL states of the connection-failure class, and of the server shutting down. */
    private static final String[] UNAVAILABLE_STATES = { "08", "57P01", "57P02", "57P03" };

    private DatabaseUnavailable() {
    }

    public static boolean isCauseOf(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof DataAccessResourceFailureException
                    || cause instanceof TransientDataAccessResourceException || cause instanceof QueryTimeoutException
                    || cause instanceof SQLTransientConnectionException || cause instanceof SQLTimeoutException
                    || cause instanceof SQLNonTransientConnectionException) {
                return true;
            }
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                for (String state : UNAVAILABLE_STATES) {
                    if (sql.getSQLState().startsWith(state)) {
                        return true;
                    }
                }
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    /** The kind of failure, for the log: the exception's class and the database's state, no message. */
    public static String describe(Throwable failure) {
        StringBuilder chain = new StringBuilder();
        for (Throwable cause = failure; cause != null && cause.getCause() != cause; cause = cause.getCause()) {
            if (!chain.isEmpty()) {
                chain.append(" <- ");
            }
            chain.append(cause.getClass().getSimpleName());
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                chain.append(" (SQL state ").append(sql.getSQLState()).append(')');
            }
        }
        return chain.toString();
    }
}
