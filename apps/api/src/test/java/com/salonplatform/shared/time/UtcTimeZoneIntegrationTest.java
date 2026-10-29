package com.salonplatform.shared.time;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.salonplatform.testing.StartedApplication;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * AC-4: the API runs in UTC and talks to the database in UTC, whatever the machine's time zone is.
 * Started here on a machine set to Pacific/Chatham (UTC+12:45 or +13:45): the server time, a probe's
 * times and what the database stores are the same instants as under UTC.
 */
class UtcTimeZoneIntegrationTest {

    private static final String CLOCK = "2026-03-29T00:30:00Z";

    @AfterEach
    void utcAsEveryOtherTestRuns() {
        TimeZone.setDefault(TimeZone.getTimeZone(UtcTimeZone.UTC));
    }

    @Test
    void underAnotherMachineTimeZoneEveryTimeIsTheSameInstant() {
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Chatham"));
        TestDatabase database = TestDatabase.createEmpty(TestPostgres.shared());

        try (StartedApplication application = database.start(
                "--spring.profiles.active=local", "--salon.outbox.enabled=false", "--salon.jobs.enabled=false")) {
            assertThat(TimeZone.getDefault().getID())
                    .as("the API's own time zone")
                    .isEqualTo("UTC");
            assertThat(application.jdbc().queryForObject("SHOW TimeZone", String.class))
                    .as("the time zone of the API's database sessions")
                    .isEqualTo("UTC");

            Instant server = Instant.parse(
                    JsonPath.parse(application.http().get("/api/v1/meta").body())
                            .read("$.time"));
            assertThat(Duration.between(server, Instant.now()).abs())
                    .as("meta's server time")
                    .isLessThan(Duration.ofSeconds(5));

            application
                    .http()
                    .sendOnHost(
                            "PUT",
                            "localhost:" + application.port(),
                            "/local/clock",
                            Map.of("Content-Type", "application/json"),
                            "{\"instant\":\"" + CLOCK + "\"}");
            DocumentContext probe = JsonPath.parse(application
                    .http()
                    .postJsonOnHost("gamma.localhost:" + application.port(), "/local/probes", "{}")
                    .body());

            assertThat(probe.<String>read("$.createdAt")).isEqualTo(CLOCK);
            assertThat(probe.<String>read("$.createdAtLocal")).isEqualTo("2026-03-29T00:30:00+00:00");
            String id = probe.read("$.id");
            for (String sql : new String[] {
                "SELECT created_at FROM outbox_message WHERE id = ?::uuid",
                "SELECT next_attempt_at FROM outbox_message WHERE id = ?::uuid",
                "SELECT created_at FROM audit_entry WHERE subject_id = ?"
            }) {
                Instant stored = database.asOwner()
                        .queryForObject(
                                sql,
                                (row, n) ->
                                        row.getObject(1, OffsetDateTime.class).toInstant(),
                                id);
                assertThat(Duration.between(Instant.parse(CLOCK), stored))
                        .as(sql)
                        .isBetween(Duration.ZERO, Duration.ofSeconds(1));
            }
            // As text in UTC, read by the database itself: no driver or JVM zone in between.
            assertThat(database.asOwner()
                            .queryForObject(
                                    "SELECT to_char(created_at AT TIME ZONE 'UTC', "
                                            + "'YYYY-MM-DD\"T\"HH24:MI') FROM outbox_message WHERE id = ?::uuid",
                                    String.class,
                                    id))
                    .isEqualTo("2026-03-29T00:30");
        }
    }

    @Test
    void theListenerSetsUtcWhateverTheMachineSays() {
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Chatham"));

        UtcTimeZone.apply();

        assertThat(TimeZone.getDefault().getID()).isEqualTo("UTC");
    }
}
