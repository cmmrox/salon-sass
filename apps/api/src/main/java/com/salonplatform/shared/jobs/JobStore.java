package com.salonplatform.shared.jobs;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The scheduler's bookkeeping, reached only through {@code job_claim()} and {@code job_finish()}:
 * the application's role cannot read the table itself.
 */
@Component
class JobStore {

    private final JdbcTemplate jdbc;

    JobStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The occurrence of the job this instance has claimed, if one was due and free. */
    Optional<Instant> claim(String job, Instant now, Duration every, String holder, Instant holdUntil) {
        OffsetDateTime occurrence = jdbc.queryForObject(
                "SELECT job_claim(?, ?, make_interval(secs => ?), ?, ?)",
                (row, n) -> row.getObject(1, OffsetDateTime.class),
                job, utc(now), every.toMillis() / 1000.0, holder, utc(holdUntil));
        return Optional.ofNullable(occurrence).map(OffsetDateTime::toInstant);
    }

    void finish(String job, String holder, Instant now) {
        jdbc.queryForObject("SELECT job_finish(?, ?, ?)::text", String.class, job, holder, utc(now));
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
