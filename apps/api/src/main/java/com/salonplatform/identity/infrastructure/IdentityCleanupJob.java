package com.salonplatform.identity.infrastructure;

import com.salonplatform.shared.jobs.TenantJob;
import com.salonplatform.shared.tenancy.CurrentTenant;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Removes expired short-term security state, through the scheduler's isolated tenant transaction.
 */
@Component
class IdentityCleanupJob implements TenantJob {
    @PersistenceContext private EntityManager em;
    private final com.salonplatform.identity.application.CustomerAccess customers;

    IdentityCleanupJob(com.salonplatform.identity.application.CustomerAccess customers) {
        this.customers = customers;
    }

    @Override
    public String name() {
        return "identity-security-cleanup";
    }

    @Override
    public Duration every() {
        return Duration.ofMinutes(15);
    }

    @Override
    public void runFor(CurrentTenant salon, Instant now) {
        customers.cleanup(now);
        em.createQuery("delete from MfaChallenge c where c.expiresAt<=:now")
                .setParameter("now", now)
                .executeUpdate();
        em.createQuery("delete from MfaStep s where s.step<:step")
                .setParameter("step", now.getEpochSecond() / 30 - 1)
                .executeUpdate();
        em.createQuery(
                        "delete from RateBucket b where b.windowStart<=:before and b.dimension like"
                                + " 'staff-%'")
                .setParameter("before", now.minusSeconds(900))
                .executeUpdate();
    }
}
