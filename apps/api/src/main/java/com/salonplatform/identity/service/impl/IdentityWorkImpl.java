package com.salonplatform.identity.service.impl;

import com.salonplatform.identity.repository.IdentityLock;
import com.salonplatform.identity.service.IdentityWork;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.shared.web.ApplicationProblemException;
import java.time.Clock;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class IdentityWorkImpl implements IdentityWork {

    private final TransactionTemplate transactions;

    private final IdentityLock lock;

    private final Clock clock;

    IdentityWorkImpl(PlatformTransactionManager manager, IdentityLock lock, Clock clock) {
        this.transactions = new TransactionTemplate(manager);
        this.lock = lock;
        this.clock = clock;
    }

    private record Outcome<T>(T value, ApplicationProblemException failure) {}

    @Override
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
