package com.salonplatform.shared.attempts.impl;

import com.salonplatform.shared.attempts.AddressAttempts;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The counters, through {@code shared_count_address_attempt()}: a call to a {@code SECURITY DEFINER} function, a
 * named direct-SQL exception ({@code docs/engineering/backend.md}). The application's role has no grant on the
 * table itself.
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
    public int count(String purpose, String addressHash, Duration window) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("An attempt is counted in the caller's writable transaction");
        }
        return jdbc.sql("SELECT shared_count_address_attempt(?, ?, ?, make_interval(secs => ?))")
                .params(purpose, addressHash, clock.instant().atOffset(ZoneOffset.UTC), window.toMillis() / 1000.0)
                .query(Integer.class)
                .single();
    }
}
