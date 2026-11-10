package com.salonplatform.shared.attempts.impl;

import com.salonplatform.shared.attempts.AddressAttempts;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The counters, through {@code shared_count_address_attempt()} and {@code shared_return_address_attempt()}: calls
 * to {@code SECURITY DEFINER} functions, a named direct-SQL exception ({@code docs/engineering/backend.md}). The
 * application's role has no grant on the table itself.
 */
@Service
class AddressAttemptsImpl implements AddressAttempts {

    private final JdbcClient jdbc;

    private final Clock clock;

    AddressAttemptsImpl(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public Counted count(String purpose, String addressHash, Duration window) {
        requireWritableTransaction();
        Instant at = clock.instant();
        int attempts = jdbc.sql("SELECT shared_count_address_attempt(?, ?, ?, make_interval(secs => ?))")
                .params(purpose, addressHash, at.atOffset(ZoneOffset.UTC), window.toMillis() / 1000.0)
                .query(Integer.class)
                .single();
        return new Counted(attempts, at);
    }

    @Override
    public void giveBack(String purpose, String addressHash, Counted counted) {
        requireWritableTransaction();
        jdbc.sql("SELECT shared_return_address_attempt(?, ?, ?)")
                .params(purpose, addressHash, counted.at().atOffset(ZoneOffset.UTC))
                .query()
                .listOfRows();
    }

    private static void requireWritableTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("An attempt is counted in the caller's writable transaction");
        }
    }
}
