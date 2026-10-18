package com.salonplatform.platform.infrastructure;

import java.time.Duration;
import java.time.Instant;

import com.salonplatform.platform.application.BillingJob;
import com.salonplatform.shared.jobs.PlatformJob;

import org.springframework.stereotype.Component;

/**
 * The billing job's schedule: one pass every minute through the shared scheduler, at most once per
 * occurrence across every instance. Each pass is idempotent, so its frequency decides only how soon a
 * boundary is applied after it is crossed.
 */
@Component
class BillingJobSchedule implements PlatformJob {

    private final BillingJob job;

    BillingJobSchedule(BillingJob job) {
        this.job = job;
    }

    @Override
    public String name() {
        return "subscription-billing";
    }

    @Override
    public Duration every() {
        return Duration.ofMinutes(1);
    }

    @Override
    public void run(Instant now) {
        job.run(now);
    }
}
