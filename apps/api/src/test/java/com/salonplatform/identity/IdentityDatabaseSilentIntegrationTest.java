package com.salonplatform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import com.salonplatform.identity.adapter.IdentityCrypto;
import com.salonplatform.testing.Http;
import com.salonplatform.testing.Http.Exchange;
import com.salonplatform.testing.StartedApplication;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import com.salonplatform.testing.TestSalons;
import com.salonplatform.testing.TestSalons.TestSalon;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * F02-S01 AC-10 (BUG-F02-015): an identity request that is waiting on the database when it goes silent
 * is answered 503 {@code service_unavailable} within five seconds, even though the transaction's
 * rollback then fails on the connection the driver gave up. That second failure must not replace the
 * first. The request runs through identity's transaction and its Spring Data repositories, the way
 * {@code GET /api/v1/identity/me} does.
 */
class IdentityDatabaseSilentIntegrationTest {

    private static final Duration LIMIT = Duration.ofSeconds(5);

    @Test
    void anIdentityRequestWaitingWhenTheDatabaseGoesSilentIsAnswered503AndTheApiRecovers() throws Exception {
        try (PostgreSQLContainer postgres = TestPostgres.newContainer()) {
            postgres.start();
            try (StartedApplication application = StartedApplication.against(postgres)) {
                JdbcTemplate owner = TestDatabase.of(postgres).asOwner();
                TestSalon alpha = new TestSalons(owner).create("Alpha Silent", "#D6086A");
                String password = UUID.randomUUID() + " synthetic";
                owner.update(
                        "INSERT INTO identity_staff (id, tenant_id, created_at, updated_at, email, password_hash, role,"
                                + " active, professional_grant, version)"
                                + " VALUES (?, ?, now(), now(), 'manager@example.test', ?, 'MANAGER', true, false, 0)",
                        UUID.randomUUID(),
                        alpha.id(),
                        application.bean(IdentityCrypto.class).encodePassword(password));
                Http http = application.http();
                String host = alpha.host(application.port());
                String token = signIn(http, host, password);
                assertThat(me(http, host, token).status()).isEqualTo(200);

                // The request is part-way through its database work (waiting on a lock the owning role
                // holds) at the moment the database stops answering.
                try (Connection locker = TestDatabase.of(postgres).connectAsOwner();
                        ExecutorService client = Executors.newVirtualThreadPerTaskExecutor()) {
                    locker.setAutoCommit(false);
                    try (Statement lock = locker.createStatement()) {
                        lock.execute("LOCK TABLE identity_staff IN ACCESS EXCLUSIVE MODE");
                    }
                    long started = System.nanoTime();
                    Future<Exchange> inFlight = client.submit(() -> me(http, host, token));
                    await().atMost(Duration.ofSeconds(2))
                            .pollInterval(Duration.ofMillis(20))
                            .until(() -> waitingOnALock(owner) >= 1);
                    freeze(postgres);
                    Exchange answer;
                    try {
                        answer = inFlight.get(LIMIT.toMillis(), TimeUnit.MILLISECONDS);
                    } finally {
                        thaw(postgres);
                    }
                    Duration took = Duration.ofNanos(System.nanoTime() - started);
                    locker.rollback();

                    assertThat(answer.status()).as("answered %s", answer.body()).isEqualTo(503);
                    assertThat(JsonPath.parse(answer.body()).<String>read("$.code"))
                            .isEqualTo("service_unavailable");
                    assertThat(took).isLessThan(LIMIT);
                }

                await().atMost(LIMIT)
                        .pollInterval(Duration.ofMillis(100))
                        .untilAsserted(
                                () -> assertThat(me(http, host, token).status()).isEqualTo(200));
            }
        }
    }

    private static String signIn(Http http, String host, String password) {
        Exchange signed = http.sendOnHost(
                "POST",
                host,
                "/api/v1/identity/staff/sign-in",
                Map.of("Content-Type", "application/json"),
                "{\"identifier\":\"manager@example.test\",\"password\":\"" + password
                        + "\",\"deviceLabel\":\"JUnit device\"}");
        assertThat(signed.status()).as(signed.body()).isEqualTo(200);
        return JsonPath.parse(signed.body()).read("$.credentials.accessToken");
    }

    private static Exchange me(Http http, String host, String token) {
        return http.sendOnHost("GET", host, "/api/v1/identity/me", Map.of("Authorization", "Bearer " + token), null);
    }

    private static long waitingOnALock(JdbcTemplate owner) {
        List<Long> waiting = owner.queryForList(
                "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()",
                Long.class);
        return waiting.getFirst();
    }

    private static void freeze(PostgreSQLContainer postgres) {
        postgres.getDockerClient().pauseContainerCmd(postgres.getContainerId()).exec();
    }

    private static void thaw(PostgreSQLContainer postgres) {
        Boolean paused = postgres.getDockerClient()
                .inspectContainerCmd(postgres.getContainerId())
                .exec()
                .getState()
                .getPaused();
        if (Boolean.TRUE.equals(paused)) {
            postgres.getDockerClient()
                    .unpauseContainerCmd(postgres.getContainerId())
                    .exec();
        }
    }
}
