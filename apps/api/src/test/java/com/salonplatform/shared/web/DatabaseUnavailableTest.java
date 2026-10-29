package com.salonplatform.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.sql.SQLTransactionRollbackException;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;

/** Which failures mean "the database is down or silent" (503), and which do not (500). */
class DatabaseUnavailableTest {

    @Test
    void noConnectionFromThePoolIsUnavailable() {
        Exception poolTimeout = new SQLTransientConnectionException("Connection is not available, request timed out");

        assertThat(DatabaseUnavailable.isCauseOf(
                        new CannotGetJdbcConnectionException("no connection", new SQLException(poolTimeout))))
                .isTrue();
        assertThat(DatabaseUnavailable.isCauseOf(
                        new CannotCreateTransactionException("could not begin", new RuntimeException(poolTimeout))))
                .isTrue();
    }

    @Test
    void aConnectionThatWentSilentOrWasRefusedIsUnavailable() {
        assertThat(DatabaseUnavailable.isCauseOf(new RuntimeException(new SQLException("I/O error", "08006"))))
                .isTrue();
        assertThat(DatabaseUnavailable.isCauseOf(new SQLException("refused", "08001")))
                .isTrue();
        assertThat(DatabaseUnavailable.isCauseOf(new SQLException("shutting down", "57P01")))
                .isTrue();
        assertThat(DatabaseUnavailable.isCauseOf(new DataAccessResourceFailureException("gone")))
                .isTrue();
    }

    @Test
    void aFailureOfTheRequestOrTheCodeIsNot() {
        assertThat(DatabaseUnavailable.isCauseOf(new IllegalStateException("bug")))
                .isFalse();
        assertThat(DatabaseUnavailable.isCauseOf(
                        new DataIntegrityViolationException("duplicate", new SQLException("duplicate key", "23505"))))
                .isFalse();
        assertThat(DatabaseUnavailable.isCauseOf(new CannotAcquireLockException(
                        "lock", new SQLTransactionRollbackException("deadlock", "40P01"))))
                .isFalse();
        assertThat(DatabaseUnavailable.isCauseOf(
                        new CannotCreateTransactionException("could not begin", new IllegalStateException("bug"))))
                .isFalse();
        assertThat(DatabaseUnavailable.isCauseOf(new SQLException("permission denied", "42501")))
                .isFalse();
    }

    @Test
    void theLogLineNamesTheKindOfFailureAndNothingElse() {
        String line = DatabaseUnavailable.describe(new CannotGetJdbcConnectionException(
                "password=secret", new SQLException("host 10.0.0.1 refused", "08001")));

        assertThat(line).isEqualTo("CannotGetJdbcConnectionException <- SQLException (SQL state 08001)");
    }
}
