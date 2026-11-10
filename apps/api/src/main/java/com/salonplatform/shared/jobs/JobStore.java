package com.salonplatform.shared.jobs;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The scheduler's bookkeeping, reached only through {@code job_claim()}, {@code job_renew()} and {@code job_finish()}:
 * the application's role cannot read the table itself. Calls to {@code SECURITY DEFINER} functions and job
 * leasing, named direct-SQL exceptions; the table holds no salon's data.
 */
@Component
class JobStore {

    private final JdbcClient jdbc;

    JobStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The occurrence of the job this instance has claimed, if one was due and free. */
    Optional<Instant> claim(String job, Instant now, Duration every, String holder, Instant holdUntil) {
        return jdbc.sql("SELECT job_claim(?, ?, make_interval(secs => ?), ?, ?)")
                .params(job, utc(now), every.toMillis() / 1000.0, holder, utc(holdUntil))
                .query((row, number) -> row.getObject(1, OffsetDateTime.class))
                .optional()
                .map(OffsetDateTime::toInstant);
    }

    /**
     * Extends the claim this instance holds on a running job until that instant; false when it no longer holds it
     * (its hold ran out and another instance claimed the job).
     */
    boolean renew(String job, String holder, Instant holdUntil) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT job_renew(?, ?, ?)")
                .params(job, holder, utc(holdUntil))
                .query(Boolean.class)
                .single());
    }

    void finish(String job, String holder, Instant now) {
        jdbc.sql("SELECT job_finish(?, ?, ?)::text")
                .params(job, holder, utc(now))
                .query(String.class)
                .optional();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
