package com.salonplatform.shared.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.TransactionSystemException;

/**
 * A transaction that ends on a connection the driver has already given up: the database went silent or
 * away while the work held it (BUG-F02-015). Nothing the work read can be trusted, so whatever it
 * concluded, the transaction managers report the database as unavailable
 * ({@link com.salonplatform.shared.web.DatabaseUnavailable}) and the request is answered 503.
 */
final class LostConnection {

    private LostConnection() {}

    /** Whether the connection is closed; a connection that cannot even say so is not taken as closed. */
    static boolean isClosed(Connection connection) {
        try {
            return connection != null && connection.isClosed();
        } catch (SQLException ex) {
            return false;
        }
    }

    /**
     * What a commit or rollback that failed on a lost connection throws. It replaces the work's own
     * exception, if any: Spring attaches that one to it ({@link TransactionSystemException#getApplicationException})
     * and logs it, so it is kept for the log, not for the answer.
     */
    static TransactionSystemException atTransactionEnd(RuntimeException failure) {
        return new TransactionSystemException(
                "The database connection was lost before the transaction ended",
                new DataAccessResourceFailureException("The database connection is closed", failure));
    }
}
