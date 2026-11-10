package com.salonplatform.identity.service.impl;

import com.salonplatform.identity.adapter.IdentityCrypto;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Staff password hashing and checking (PBKDF2, deliberately slow), never inside a database transaction and at most
 * {@code salon.identity.password-checks.concurrent} at once on this instance (decision 120, BUG-F02-016). Inside a
 * transaction each check would hold a pooled connection and the salon's identity lock for its whole length, so a few
 * sign-ins in flight stalled every salon. A check beyond the limit waits up to the pool's own two seconds and is then
 * refused 503 {@code service_unavailable}, which a client may try again.
 */
@Component
class PasswordChecks {

    static final Duration WAIT = Duration.ofSeconds(2);

    private final IdentityCrypto crypto;
    private final Semaphore permits;

    PasswordChecks(
            IdentityCrypto crypto,
            @Value(
                            "${salon.identity.password-checks.concurrent:#{T(java.lang.Runtime).getRuntime().availableProcessors()}}")
                    int concurrent) {
        if (concurrent < 1) {
            throw new IllegalArgumentException("salon.identity.password-checks.concurrent must be positive");
        }
        this.crypto = crypto;
        this.permits = new Semaphore(concurrent, true);
    }

    /** Whether the password matches the stored hash; an absent hash is checked against a dummy one, as slowly. */
    boolean matches(String password, String hash) {
        return limited(() -> crypto.matchesPassword(password, hash));
    }

    /** The stored form of a new password. */
    String encode(String password) {
        return limited(() -> crypto.encodePassword(password));
    }

    private <T> T limited(Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "A password is never hashed or checked inside a transaction (decision 120)");
        }
        boolean permitted;
        try {
            permitted = permits.tryAcquire(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            permitted = false;
        }
        if (!permitted) {
            throw new ApplicationProblemException(503, ErrorCode.SERVICE_UNAVAILABLE);
        }
        try {
            return work.get();
        } finally {
            permits.release();
        }
    }
}
