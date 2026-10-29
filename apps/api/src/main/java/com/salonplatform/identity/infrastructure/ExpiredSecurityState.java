package com.salonplatform.identity.infrastructure;

import com.salonplatform.shared.tenancy.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Removes the current salon's expired short-term security state: set-based cleanup, a named direct-SQL
 * exception ({@code docs/engineering/backend.md}, "Persistence and migrations"). Every statement names
 * {@code tenant_id}; row-level security checks it again.
 */
@Component
@RequiredArgsConstructor
public class ExpiredSecurityState {

    private final JdbcClient jdbc;

    /** Second-step challenges past their expiry. */
    public void removeMfaChallengesExpiredBy(Instant now) {
        jdbc.sql("DELETE FROM identity_mfa_challenge WHERE tenant_id = ? AND expires_at <= ?")
                .params(TenantContext.require().id(), Timestamp.from(now))
                .update();
    }

    /** Used authenticator steps older than the given one, which no code can match any more. */
    public void removeMfaStepsBefore(long step) {
        jdbc.sql("DELETE FROM identity_mfa_step WHERE tenant_id = ? AND step < ?")
                .params(TenantContext.require().id(), step)
                .update();
    }

    /** Staff sign-in rate buckets whose window started at or before the given instant. */
    public void removeStaffRateBucketsStartedBy(Instant windowStart) {
        jdbc.sql("""
                        DELETE FROM identity_rate_bucket
                         WHERE tenant_id = ? AND window_start <= ? AND dimension LIKE 'staff-%'
                        """)
                .params(TenantContext.require().id(), Timestamp.from(windowStart))
                .update();
    }

    /** Customer codes past their expiry. */
    public void removeCustomerChallengesExpiredBy(Instant now) {
        jdbc.sql("DELETE FROM identity_customer_challenge WHERE tenant_id = ? AND expires_at <= ?")
                .params(TenantContext.require().id(), Timestamp.from(now))
                .update();
    }

    /** Customer code rate buckets, except the salon's daily ceiling, whose window started by the instant. */
    public void removeCustomerRateBucketsStartedBy(Instant windowStart) {
        jdbc.sql("""
                        DELETE FROM identity_rate_bucket
                         WHERE tenant_id = ? AND dimension LIKE 'customer-%' AND dimension <> 'customer-day'
                           AND window_start <= ?
                        """)
                .params(TenantContext.require().id(), Timestamp.from(windowStart))
                .update();
    }
}
