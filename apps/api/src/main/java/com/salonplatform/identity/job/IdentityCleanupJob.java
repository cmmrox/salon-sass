package com.salonplatform.identity.job;

import com.salonplatform.identity.repository.ExpiredSecurityState;
import com.salonplatform.identity.service.CustomerAccess;
import com.salonplatform.shared.jobs.TenantJob;
import com.salonplatform.shared.tenancy.CurrentTenant;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Removes expired short-term security state every fifteen minutes, through the scheduler's isolated tenant
 * transaction (docs/architecture/identity-and-access.md): expired customer codes and code rate buckets, expired
 * second-step challenges, authenticator steps outside the accepted tolerance and staff rate buckets.
 */
@Component
@RequiredArgsConstructor
class IdentityCleanupJob implements TenantJob {

    /** A used step stays while a code of it, or of the step before, could still be accepted. */
    private static final long TOTP_STEP_SECONDS = 30;

    private static final Duration STAFF_RATE_WINDOW = Duration.ofMinutes(15);

    private final CustomerAccess customers;

    private final ExpiredSecurityState expired;

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
        expired.removeMfaChallengesExpiredBy(now);
        expired.removeMfaStepsBefore(now.getEpochSecond() / TOTP_STEP_SECONDS - 1);
        expired.removeStaffRateBucketsStartedBy(now.minus(STAFF_RATE_WINDOW));
    }
}
