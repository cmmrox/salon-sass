package com.salonplatform.shared.jobs;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

/**
 * Local test hook only, for the platform's local reset: brings every recurring-job occurrence that a moved
 * test clock pushed past now back to now. Here because the scheduler's table is {@code shared}'s and only
 * {@code shared} names it (module-ownership.yaml). It runs as the owning role (the migration credentials,
 * which exist only on a developer's machine in the {@code local} profile), because the application's role
 * reaches the table only through {@code job_claim()} and {@code job_finish()}. Named direct-SQL exception 4
 * of docs/engineering/backend.md, set-based work on a table that holds no salon's data.
 */
@Component
@Profile("local")
public class LocalJobRewind {

    private final JdbcClient owner;

    LocalJobRewind(Environment environment) {
        this.owner = JdbcClient.create(new DriverManagerDataSource(
                environment.getRequiredProperty("spring.datasource.url"),
                environment.getRequiredProperty("spring.flyway.user"),
                environment.getRequiredProperty("spring.flyway.password")));
    }

    /** Brings every job occurrence due after {@code now} back to it; returns how many. */
    public int rewindTo(Instant now) {
        Timestamp time = Timestamp.from(now);
        return owner.sql("UPDATE scheduled_job SET next_run_at = ?, updated_at = ? WHERE next_run_at > ?")
                .params(time, time, time)
                .update();
    }
}
