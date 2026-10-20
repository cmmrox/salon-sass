package com.salonplatform.shared.jobs;

import java.time.Duration;
import java.time.Instant;

/**
 * Recurring work over platform tables only (docs/architecture/multi-tenancy.md, "Background jobs"):
 * run once per occurrence across every instance, with no salon set. It opens its own platform
 * transaction and reads no tenant data. A failure is logged; the next occurrence tries again.
 */
public interface PlatformJob {

    /** A stable name: lowercase letters, digits and hyphens. It keys the job's bookkeeping. */
    String name();

    /** How often it runs. */
    Duration every();

    /**
     * Runs once.
     *
     * @param now the application's clock when the occurrence started
     */
    void run(Instant now);
}
