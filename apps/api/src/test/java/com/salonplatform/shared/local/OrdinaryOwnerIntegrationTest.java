package com.salonplatform.shared.local;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import com.jayway.jsonpath.DocumentContext;
import com.salonplatform.testing.StartedApplication;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import org.junit.jupiter.api.Test;

import static com.salonplatform.shared.local.ProbeClient.ALPHA;
import static com.salonplatform.shared.local.ProbeClient.BETA;
import static com.salonplatform.shared.local.ProbeClient.status;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole path on a database whose owning role is not a superuser, as it is everywhere but on a
 * developer's machine: the migrations run, and because forced row-level security then holds for
 * the owner too, the dispatcher and the cleanup job only find a salon's messages by setting that
 * salon. Messages are delivered and removed all the same, and one salon still cannot see another's.
 */
class OrdinaryOwnerIntegrationTest {

    @Test
    void messagesAreDeliveredAndCleanedUpSalonBySalonWhenTheOwnerIsNotASuperuser() {
        TestDatabase database = TestDatabase.createEmptyOwnedByAnOrdinaryRole(TestPostgres.shared());
        try (StartedApplication application = database.start(ProbeClient.localWithDispatcher(
                "--salon.jobs.enabled=true", "--salon.jobs.poll-interval=100ms"))) {
            assertThat(database.asOwner().queryForMap("SELECT r.rolname AS owner, r.rolsuper AS superuser, "
                    + "r.rolbypassrls AS bypass FROM pg_class c JOIN pg_roles r ON r.oid = c.relowner "
                    + "WHERE c.relname = 'outbox_message'"))
                    .isEqualTo(Map.of("owner", TestDatabase.ORDINARY_OWNER, "superuser", false, "bypass", false));
            ProbeClient probes = new ProbeClient(application);
            Instant start = Instant.now();

            String alphas = probes.probe(ALPHA, "{}");
            String betas = probes.probe(BETA, "{\"failures\":1}");
            DocumentContext sent = probes.awaitProbe(ALPHA, alphas, Duration.ofSeconds(5), status("sent"));
            assertThat(sent.<Integer>read("$.providerCalls")).isEqualTo(1);
            assertThat(application.http().getOnHost(probes.host(BETA), "/local/probes/" + alphas).status())
                    .as("alpha's probe, asked for on beta").isEqualTo(404);

            DocumentContext failed = probes.awaitProbe(BETA, betas, Duration.ofSeconds(5), ProbeClient.attempts(1));
            probes.setClock(Instant.parse(failed.<String>read("$.nextAttemptAt")).plusSeconds(1));
            probes.awaitProbe(BETA, betas, Duration.ofSeconds(5), status("sent"));

            probes.setClock(start.plus(Duration.ofDays(31)));
            Instant until = Instant.now().plusSeconds(5);
            while (database.asOwner().queryForObject("SELECT count(*) FROM outbox_message", Integer.class) > 0) {
                assertThat(Instant.now()).as("both salons' delivered messages are removed within 5 seconds").isBefore(until);
                ProbeClient.sleep(50);
            }
            assertThat(database.asOwner().queryForObject("SELECT count(*) FROM audit_entry", Integer.class)).isEqualTo(2);
        }
    }
}
