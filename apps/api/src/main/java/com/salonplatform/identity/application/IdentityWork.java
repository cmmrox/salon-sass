package com.salonplatform.identity.application;

import com.salonplatform.identity.domain.IdentityGuard;
import com.salonplatform.shared.api.ApplicationProblemException;
import com.salonplatform.shared.tenancy.TenantContext;
import jakarta.persistence.*;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Shared transaction and cross-instance security lock. Failed auth state commits before the HTTP
 * error.
 */
@Service
public class IdentityWork {
    @PersistenceContext
    private EntityManager em;

    private final TransactionTemplate transactions;
    private final Clock clock;

    public IdentityWork(PlatformTransactionManager manager, Clock clock) {
        this.transactions = new TransactionTemplate(manager);
        this.clock = clock;
    }

    private record Outcome<T>(T value, ApplicationProblemException failure) {}

    public <T> T run(Supplier<T> useCase) {
        TenantContext.require();
        Outcome<T> result = transactions.execute(status -> {
            lockSalon();
            try {
                return new Outcome<>(useCase.get(), null);
            } catch (ApplicationProblemException ex) {
                if (ex.status() != 401 && ex.status() != 429) throw ex;
                return new Outcome<>(null, ex);
            }
        });
        if (result.failure() != null) throw result.failure();
        return result.value();
    }

    private void lockSalon() {
        UUID tenant = TenantContext.require().id();
        Instant at = clock.instant();
        em.createNativeQuery("INSERT INTO identity_guard(id,tenant_id,created_at,updated_at)"
                        + " VALUES(:id,:tenant,:at,:at) ON CONFLICT(tenant_id) DO NOTHING")
                .setParameter("id", tenant)
                .setParameter("tenant", tenant)
                .setParameter("at", at)
                .executeUpdate();
        em.createQuery("select g from IdentityGuard g", IdentityGuard.class)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getSingleResult();
    }
}
