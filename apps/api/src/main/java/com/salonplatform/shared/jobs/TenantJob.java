package com.salonplatform.shared.jobs;

import com.salonplatform.shared.tenancy.CurrentTenant;
import java.time.Duration;
import java.time.Instant;

/**
 * Recurring work over salon data (T-7). On each occurrence the scheduler runs it once for every
 * active salon, one salon at a time, each in a transaction of its own with that salon set, so that
 * it can read and write that salon's data and nothing else. A failure for one salon is logged and
 * the others still run.
 */
public interface TenantJob {

    /** A stable name: lowercase letters, digits and hyphens. It keys the job's bookkeeping. */
    String name();

    /** How often it runs. */
    Duration every();

    /**
     * Runs for one salon, inside that salon's transaction.
     *
     * @param salon the salon set for this run
     * @param now the application's clock when the occurrence started
     */
    void runFor(CurrentTenant salon, Instant now) throws Exception;
}
