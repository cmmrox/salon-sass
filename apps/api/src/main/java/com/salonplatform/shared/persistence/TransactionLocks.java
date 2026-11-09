package com.salonplatform.shared.persistence;

import com.salonplatform.shared.tenancy.TenantContext;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * A lock on one subject of the current salon, held until the caller's transaction ends: PostgreSQL's
 * transaction-scoped advisory lock ({@code pg_advisory_xact_lock}), keyed by a purpose, the salon and the subject's
 * id. Two transactions that name the same purpose and subject take turns; anything else goes on. No row is read or
 * written, so it serves a question whose answer comes from another module's tables: the transaction that changes
 * the answer and the one that relies on it both hold the lock, so the second sees the first's committed change
 * (F07-S01, the guard window of decision 094(f)). Leasing and locking is named direct-SQL exception 3 of
 * docs/engineering/backend.md.
 *
 * <p>The 64-bit key is a hash of the three parts. Two different subjects that happen to share a key only wait for
 * each other; nothing is ever let through that should wait.
 */
@Component
public class TransactionLocks {

    private final JdbcClient jdbc;

    TransactionLocks(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Waits for, and then holds until the caller's transaction ends, the current salon's lock on one subject.
     *
     * @param purpose what is locked, a stable name owned by the module that declares it, such as
     *     {@code staff.member-bookings}
     * @param subject the id of the thing locked
     * @throws IllegalStateException outside a writable transaction, where the lock would end at once
     */
    public void hold(String purpose, UUID subject) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("A transaction lock is taken inside a writable transaction");
        }
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))::text")
                .param(key(purpose, TenantContext.require().id(), subject))
                .query(String.class)
                .single();
    }

    /** The text the lock's key is hashed from: purpose, salon and subject. */
    public static String key(String purpose, UUID salon, UUID subject) {
        if (purpose == null || !purpose.matches("[a-z][a-z.-]{2,62}")) {
            throw new IllegalArgumentException("A lock's purpose is a plain dotted name");
        }
        return purpose + "/" + salon + "/" + subject;
    }
}
