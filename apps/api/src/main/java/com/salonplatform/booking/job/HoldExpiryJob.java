package com.salonplatform.booking.job;

import com.salonplatform.booking.service.HoldExpiryService;
import com.salonplatform.shared.jobs.TenantJob;
import com.salonplatform.shared.tenancy.CurrentTenant;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Ends holds whose 10 minutes have passed (BKG-04, F07-S01 AC-6), every minute and at most once per occurrence across
 * instances (the scheduler's claim), salon by salon in the scheduler's tenant transaction, so it can reach no other
 * salon's holds. A hold waiting for its payment is never ended.
 */
@Component
class HoldExpiryJob implements TenantJob {

    private final HoldExpiryService expiry;

    HoldExpiryJob(HoldExpiryService expiry) {
        this.expiry = expiry;
    }

    @Override
    public String name() {
        return "booking-hold-expiry";
    }

    @Override
    public Duration every() {
        return Duration.ofMinutes(1);
    }

    @Override
    public void runFor(CurrentTenant salon, Instant now) {
        expiry.endRunOutHolds(now);
    }
}
