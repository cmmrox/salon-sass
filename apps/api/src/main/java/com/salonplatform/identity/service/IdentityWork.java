package com.salonplatform.identity.service;

import com.salonplatform.identity.repository.IdentityLock;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.shared.web.ApplicationProblemException;
import java.time.Clock;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One tenant transaction per identity use case, holding the salon's identity lock. A refused sign-in, code or
 * rate limit (401 or 429) commits its counters, consumption and audit before the error is answered, so that a
 * failed HTTP outcome can never undo those controls; every other failure rolls the whole transaction back.
 */
@Service
public class IdentityWork {

    private final TransactionTemplate transactions;

    private final IdentityLock lock;

    private final Clock clock;

    public IdentityWork(PlatformTransactionManager manager, IdentityLock lock, Clock clock) {
        this.transactions = new TransactionTemplate(manager);
        this.lock = lock;
        this.clock = clock;
    }

    private record Outcome<T>(T value, ApplicationProblemException failure) {}

    public <T> T run(Supplier<T> useCase) {
        TenantContext.require();
        Outcome<T> result = transactions.execute(status -> {
            lock.lockCurrentSalon(clock.instant());
            try {
                return new Outcome<>(useCase.get(), null);
            } catch (ApplicationProblemException ex) {
                if (ex.status() != 401 && ex.status() != 429) {
                    throw ex;
                }
                return new Outcome<>(null, ex);
            }
        });
        if (result.failure() != null) {
            throw result.failure();
        }
        return result.value();
    }
}
