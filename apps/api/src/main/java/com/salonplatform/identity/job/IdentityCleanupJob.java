package com.salonplatform.identity.job;

import com.salonplatform.identity.repository.ExpiredSecurityState;
import com.salonplatform.identity.service.CustomerAccessService;
import com.salonplatform.shared.jobs.TenantJob;
import com.salonplatform.shared.tenancy.CurrentTenant;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Removes expired short-term security state every fifteen minutes, through the scheduler's isolated tenant
 * transaction (docs/architecture/identity-and-access.md): expired customer codes and code rate buckets, expired
 * second-step challenges, authenticator steps outside the accepted tolerance and staff rate buckets; and, a week
 * after they ended, sessions and their refresh history, at most {@value #SESSIONS_PER_RUN} a run (platform review
 * M6). An ended session's spent credentials are kept that long so that a replay is still recognised and recorded
 * for a while; the session itself can no longer be renewed.
 */
@Component
@RequiredArgsConstructor
class IdentityCleanupJob implements TenantJob {

    /** A used step stays while a code of it, or of the step before, could still be accepted. */
    private static final long TOTP_STEP_SECONDS = 30;

    private static final Duration STAFF_RATE_WINDOW = Duration.ofMinutes(15);

    /** How long an expired or revoked session, and its refresh history, is kept. */
    private static final Duration ENDED_SESSION_RETENTION = Duration.ofDays(7);

    /** The most sessions one run removes for a salon; the rest go in the next runs. */
    static final int SESSIONS_PER_RUN = 1_000;

    private final CustomerAccessService customers;

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
        expired.removeSessionsEndedBefore(now.minus(ENDED_SESSION_RETENTION), SESSIONS_PER_RUN);
    }
}
