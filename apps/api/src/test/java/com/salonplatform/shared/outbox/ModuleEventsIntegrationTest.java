package com.salonplatform.shared.outbox;

import static com.salonplatform.shared.outbox.EventProbes.FIRST;
import static com.salonplatform.shared.outbox.EventProbes.FLAKY;
import static com.salonplatform.shared.outbox.EventProbes.SECOND;
import static com.salonplatform.shared.outbox.EventProbes.SLOW;
import static com.salonplatform.shared.outbox.EventProbes.STEADY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.salonplatform.shared.observability.Traces;
import com.salonplatform.shared.outbox.EventProbes.Flaky;
import com.salonplatform.shared.outbox.EventProbes.NotAnEvent;
import com.salonplatform.shared.outbox.EventProbes.Noted;
import com.salonplatform.shared.outbox.EventProbes.Reach;
import com.salonplatform.shared.outbox.EventProbes.Reached;
import com.salonplatform.shared.outbox.EventProbes.Unheard;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.testing.CapturedLogs;
import com.salonplatform.testing.StartedApplication;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import com.salonplatform.testing.TestSalons;
import com.salonplatform.testing.TestSalons.TestSalon;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Events between modules through the outbox (F23-S05): published in the publisher's transaction, delivered by
 * the running dispatcher to every subscriber of their type once, for their salon, with the outbox's retry
 * and dead-letter rules. Row-level security is on here, and the reaching subscriber uses direct SQL, so the
 * database alone stands between salons on that path; {@code ModuleEventsHibernateFilterAloneIntegrationTest}
 * is the other layer on its own.
 */
class ModuleEventsIntegrationTest {

    private static final Duration PATIENCE = Duration.ofSeconds(15);

    /** Settings of every application here: a quick dispatcher, a short backoff and three attempts. */
    private static final String[] DISPATCHER = {
        "--salon.outbox.enabled=true",
        "--salon.outbox.poll-interval=20ms",
        "--salon.outbox.backoff.initial=300ms",
        "--salon.outbox.backoff.max=600ms",
        "--salon.outbox.max-attempts=3"
    };

    private static TestDatabase database;

    private static StartedApplication application;

    private static JdbcTemplate owner;

    private static TestSalons salons;

    private static TestSalon alpha;

    private static TestSalon beta;

    private static Outbox outbox;

    private static TransactionTemplate transaction;

    @BeforeAll
    static void applicationWithTheDispatcherOn() {
        database = TestDatabase.createEmpty(TestPostgres.shared());
        application = database.startWith(EventProbes.Subscribers.class, DISPATCHER);
        owner = database.asOwner();
        salons = new TestSalons(owner);
        alpha = salons.create("Events Alpha", "#111111");
        beta = salons.create("Events Beta", "#222222");
        outbox = application.bean(Outbox.class);
        transaction = new TransactionTemplate(application.bean(PlatformTransactionManager.class));
    }

    @AfterAll
    static void stop() {
        application.close();
    }

    // --- AC-1 -------------------------------------------------------------------------------------------

    @Test
    void aCommittedEventReachesEverySubscriberOfItsTypeOnceWithItsSalonSet() throws Exception {
        UUID mark = UUID.randomUUID();

        UUID event = publish(alpha, new Noted(mark));

        awaitStatus(event, "sent");
        assertThat(EventProbes.handled(FIRST, mark)).containsExactly(alpha.id());
        assertThat(EventProbes.handled(SECOND, mark)).containsExactly(alpha.id());
        assertThat(owner.queryForList(
                        "SELECT subscriber FROM shared_processed_event WHERE tenant_id = ? AND event_id = ?",
                        String.class,
                        alpha.id(),
                        event))
                .containsExactlyInAnyOrder(FIRST, SECOND);
        Thread.sleep(200);
        assertThat(EventProbes.handled(FIRST, mark)).hasSize(1);
        assertThat(EventProbes.handled(SECOND, mark)).hasSize(1);
    }

    @Test
    void anEventIsStoredWithItsVersionedTypeAndItsPayloadAsJson() throws Exception {
        UUID mark = UUID.randomUUID();

        UUID event = publish(alpha, new Unheard(mark));

        Map<String, Object> row = owner.queryForMap(
                "SELECT tenant_id, kind, channel, event_type, payload::text AS payload FROM outbox_message"
                        + " WHERE id = ?",
                event);
        assertThat(row)
                .containsEntry("tenant_id", alpha.id())
                .containsEntry("kind", "event")
                .containsEntry("channel", "event")
                .containsEntry("event_type", "probe.Unheard.v1");
        assertThat(application.bean(JsonMapper.class).readTree((String) row.get("payload")))
                .isEqualTo(application.bean(JsonMapper.class).readTree("{\"mark\":\"" + mark + "\"}"));
    }

    @Test
    void anEventTypeWithNoSubscriberIsDeliveredAtOnce() throws Exception {
        UUID event = publish(alpha, new Unheard(UUID.randomUUID()));

        awaitStatus(event, "sent");
        assertThat(owner.queryForObject("SELECT attempts FROM outbox_message WHERE id = ?", Integer.class, event))
                .isEqualTo(1);
    }

    @Test
    void anEventPublishedInATransactionThatRollsBackIsNeverDelivered() throws Exception {
        UUID mark = UUID.randomUUID();
        int before = events(alpha);

        assertThatIllegalStateException()
                .isThrownBy(() -> as(
                        alpha,
                        () -> inTransaction(() -> {
                            outbox.publish(new Noted(mark));
                            throw new IllegalStateException("the change failed");
                        })));

        Thread.sleep(300);
        assertThat(events(alpha)).isEqualTo(before);
        assertThat(EventProbes.handled(FIRST, mark)).isEmpty();
        assertThat(EventProbes.handled(SECOND, mark)).isEmpty();
    }

    @Test
    void publishingIsRefusedOutsideAWritableTransactionWithNoSalonOrForARecordWithNoEventType() {
        Noted event = new Noted(UUID.randomUUID());

        assertThatIllegalStateException().isThrownBy(() -> as(alpha, () -> outbox.publish(event)));
        assertThatIllegalStateException().isThrownBy(() -> inTransaction(() -> outbox.publish(event)));
        assertThatIllegalStateException()
                .isThrownBy(() -> as(alpha, () -> {
                    TransactionTemplate readOnly =
                            new TransactionTemplate(application.bean(PlatformTransactionManager.class));
                    readOnly.setReadOnly(true);
                    return readOnly.execute(status -> outbox.publish(event));
                }));
        assertThatIllegalArgumentException()
                .isThrownBy(() ->
                        as(alpha, () -> inTransaction(() -> outbox.publish(new NotAnEvent(UUID.randomUUID())))));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> as(alpha, () -> inTransaction(() -> outbox.enqueue("event", Map.of()))));
    }

    // --- AC-2 -------------------------------------------------------------------------------------------

    @Test
    void aDeliveryRepeatedAfterTheSubscribersCommittedAppliesNothingTwice() throws Exception {
        UUID mark = UUID.randomUUID();
        UUID event = publish(alpha, new Noted(mark));
        awaitStatus(event, "sent");

        // The lease ran out before the dispatcher recorded the outcome: the event is due again.
        owner.update(
                "UPDATE outbox_message SET status = 'sending', sent_at = NULL, lease_token = ?,"
                        + " lease_until = now() - interval '1 second' WHERE id = ?",
                UUID.randomUUID(),
                event);

        awaitStatus(event, "sent");
        assertThat(owner.queryForObject("SELECT attempts FROM outbox_message WHERE id = ?", Integer.class, event))
                .isEqualTo(2);
        assertThat(EventProbes.handled(FIRST, mark)).hasSize(1);
        assertThat(EventProbes.handled(SECOND, mark)).hasSize(1);
    }

    @Test
    void twoDeliveriesOfOneEventAtTheSameTimeApplyItsSubscribersChangeOnce() throws Exception {
        UUID mark = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        String payload = "{\"mark\":\"" + mark + "\"}";
        // Already delivered as far as the dispatcher knows, so that only the two calls below deliver it.
        owner.update(
                "INSERT INTO outbox_message (tenant_id, id, kind, channel, event_type, payload, status, attempts,"
                        + " next_attempt_at, created_at, updated_at, sent_at)"
                        + " VALUES (?, ?, 'event', 'event', 'probe.Slow.v1', ?::jsonb, 'sent', 1, now(), now(), now(),"
                        + " now())",
                alpha.id(),
                event,
                payload);
        EventSubscribers subscribers = application.bean(EventSubscribers.class);

        try (ExecutorService both = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Object>> deliveries = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                deliveries.add(both.submit(() -> as(alpha, () -> {
                    subscribers.deliver(event, "probe.Slow.v1", payload);
                    return null;
                })));
            }
            for (Future<Object> delivery : deliveries) {
                delivery.get();
            }
        }

        assertThat(EventProbes.handled(SLOW, mark)).hasSize(1);
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM shared_processed_event WHERE event_id = ?", Integer.class, event))
                .isEqualTo(1);
    }

    @Test
    void twoDispatchersRacingForTheSameEventsDeliverEachToEachSubscriberOnce() throws Exception {
        List<UUID> marks = new ArrayList<>();
        List<UUID> published = new ArrayList<>();
        // A second application on the same database, whose dispatcher races the first one's.
        try (StartedApplication _ = database.startWith(EventProbes.Subscribers.class, DISPATCHER)) {
            for (int i = 0; i < 40; i++) {
                UUID mark = UUID.randomUUID();
                marks.add(mark);
                published.add(publish(i % 2 == 0 ? alpha : beta, new Noted(mark)));
            }
            await(
                    () -> owner.queryForObject(
                                    "SELECT count(*) FROM outbox_message WHERE status = 'sent' AND id = ANY (?)",
                                    Integer.class,
                                    (Object) published.toArray(UUID[]::new))
                            == published.size(),
                    "every event delivered");
        }

        for (int i = 0; i < marks.size(); i++) {
            UUID salon = i % 2 == 0 ? alpha.id() : beta.id();
            assertThat(EventProbes.handled(FIRST, marks.get(i)))
                    .as("first, event %d", i)
                    .containsExactly(salon);
            assertThat(EventProbes.handled(SECOND, marks.get(i)))
                    .as("second, event %d", i)
                    .containsExactly(salon);
        }
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM shared_processed_event WHERE event_id = ANY (?)", Integer.class, (Object)
                                published.toArray(UUID[]::new)))
                .isEqualTo(2 * published.size());
    }

    // --- AC-3 -------------------------------------------------------------------------------------------

    @Test
    void aFailingSubscriberIsRetriedWithTheBackoffWithoutRunningTheOneThatSucceededAgain() throws Exception {
        UUID mark = UUID.randomUUID();
        EventProbes.failFlaky(mark, 2);

        UUID event = publish(alpha, new Flaky(mark));

        awaitStatus(event, "sent");
        assertThat(owner.queryForObject("SELECT attempts FROM outbox_message WHERE id = ?", Integer.class, event))
                .isEqualTo(3);
        assertThat(EventProbes.handled(STEADY, mark)).containsExactly(alpha.id());
        assertThat(EventProbes.handled(FLAKY, mark)).containsExactly(alpha.id());
        List<Instant> attempts = EventProbes.flakyAttempts(mark);
        assertThat(attempts).hasSize(3);
        assertThat(Duration.between(attempts.get(0), attempts.get(1))).isGreaterThanOrEqualTo(Duration.ofMillis(300));
        assertThat(Duration.between(attempts.get(1), attempts.get(2))).isGreaterThanOrEqualTo(Duration.ofMillis(600));
    }

    @Test
    void anEventWhoseSubscriberKeepsFailingGoesDeadWithItsLastErrorWhileOtherEventsFlow() throws Exception {
        UUID mark = UUID.randomUUID();
        EventProbes.failFlaky(mark, Integer.MAX_VALUE);
        UUID failing = publish(alpha, new Flaky(mark));
        await(() -> EventProbes.flakyAttempts(mark).size() == 1, "the first attempt");

        UUID other = UUID.randomUUID();
        UUID flowing = publish(alpha, new Noted(other));
        awaitStatus(flowing, "sent");

        assertThat(status(failing)).isIn("pending", "sending");
        assertThat(EventProbes.handled(FIRST, other)).containsExactly(alpha.id());
        awaitStatus(failing, "dead");
        assertThat(owner.queryForObject("SELECT last_error FROM outbox_message WHERE id = ?", String.class, failing))
                .isEqualTo(FLAKY + ": the flaky probe refused " + mark);
        assertThat(EventProbes.flakyAttempts(mark)).hasSize(3);
        assertThat(EventProbes.handled(STEADY, mark)).containsExactly(alpha.id());
    }

    @Test
    void aPayloadThatDoesNotFitItsRecordIsRefusedRatherThanGuessed() {
        UUID unknownField = insertDueEvent(alpha, "probe.Noted.v1", "{\"mark\":\"" + UUID.randomUUID() + "\",\"x\":1}");
        UUID missingField = insertDueEvent(alpha, "probe.Noted.v1", "{}");

        awaitStatus(unknownField, "dead");
        awaitStatus(missingField, "dead");
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM shared_processed_event WHERE event_id IN (?, ?)",
                        Integer.class,
                        unknownField,
                        missingField))
                .isZero();
    }

    @Test
    void aDeliveryContinuesThePublishersTraceAndCarriesItsSalon() throws Exception {
        UUID mark = UUID.randomUUID();
        EventProbes.failFlaky(mark, 1);
        Traces traces = application.bean(Traces.class);
        try (CapturedLogs logs = CapturedLogs.of(EventSubscribers.class.getName())) {
            String[] publishersTrace = new String[1];
            UUID event = traces.inSpan("test.publish", salons.asCurrent(alpha), null, () -> {
                publishersTrace[0] = traces.currentTraceId().orElseThrow();
                return publish(alpha, new Flaky(mark));
            });

            awaitStatus(event, "sent");
            List<ILoggingEvent> failed = logs.events(
                    line -> line.getFormattedMessage().startsWith("Subscriber " + FLAKY + " failed on event " + event));
            assertThat(failed).hasSize(1);
            assertThat(CapturedLogs.context(failed.getFirst()))
                    .containsEntry(TenantContext.LOG_FIELD, alpha.id().toString())
                    .containsEntry("trace.id", publishersTrace[0]);
        }
    }

    // --- AC-4 -------------------------------------------------------------------------------------------

    @Test
    void aSubscriberHandlingOneSalonsEventReachesNothingOfAnotherSalonThroughTheDatabase() throws Exception {
        UUID betasMessage = as(beta, () -> inTransaction(() -> outbox.enqueue("unreachable", Map.of())));
        publish(beta, new Noted(UUID.randomUUID()));
        awaitProcessedRowFor(beta);
        UUID mark = UUID.randomUUID();

        publish(alpha, new Reached(mark, betasMessage));

        await(() -> EventProbes.reach(mark) != null, "the reaching subscriber ran");
        Reach reach = EventProbes.reach(mark);
        assertThat(reach.salon()).isEqualTo(alpha.id());
        assertThat(reach.sqlSalons()).containsExactly(alpha.id());
        assertThat(reach.sqlFoundOthers()).isZero();
        assertThat(reach.sqlChangedOthers()).isZero();
        assertThat(reach.processedSalons()).containsOnly(alpha.id());
        assertThat(reach.written()).isEqualTo(alpha.id());
        assertThat(owner.queryForObject(
                        "SELECT last_error FROM outbox_message WHERE id = ?", String.class, betasMessage))
                .isNull();
    }

    // --- AC-5 -------------------------------------------------------------------------------------------

    @Test
    void aSuspendedSalonsEventsWaitUntilItIsActiveAgain() throws Exception {
        TestSalon gamma = salons.create("Events Gamma", "#333333");
        salons.suspend(gamma);
        UUID mark = UUID.randomUUID();

        UUID event = publish(gamma, new Noted(mark));

        Thread.sleep(500);
        assertThat(status(event)).isEqualTo("pending");
        assertThat(owner.queryForObject("SELECT attempts FROM outbox_message WHERE id = ?", Integer.class, event))
                .isZero();
        assertThat(EventProbes.handled(FIRST, mark)).isEmpty();

        salons.activate(gamma);

        awaitStatus(event, "sent");
        assertThat(EventProbes.handled(FIRST, mark)).containsExactly(gamma.id());
        assertThat(EventProbes.handled(SECOND, mark)).containsExactly(gamma.id());
    }

    // --- helpers -----------------------------------------------------------------------------------------

    private static UUID publish(TestSalon salon, Record event) throws Exception {
        return as(salon, () -> inTransaction(() -> outbox.publish(event)));
    }

    /** An event written as the owner, due at once, with a payload the publisher could not have written. */
    private static UUID insertDueEvent(TestSalon salon, String type, String payload) {
        UUID id = UUID.randomUUID();
        owner.update(
                "INSERT INTO outbox_message (tenant_id, id, kind, channel, event_type, payload, status, attempts,"
                        + " next_attempt_at, created_at, updated_at)"
                        + " VALUES (?, ?, 'event', 'event', ?, ?::jsonb, 'pending', 0, now(), now(), now())",
                salon.id(),
                id,
                type,
                payload);
        return id;
    }

    private static int events(TestSalon salon) {
        return owner.queryForObject(
                "SELECT count(*) FROM outbox_message WHERE tenant_id = ? AND kind = 'event'",
                Integer.class,
                salon.id());
    }

    private static String status(UUID message) {
        return owner.queryForObject("SELECT status FROM outbox_message WHERE id = ?", String.class, message);
    }

    private static void awaitStatus(UUID message, String expected) {
        await(() -> expected.equals(status(message)), "message " + message + " " + expected);
    }

    private static void awaitProcessedRowFor(TestSalon salon) {
        await(
                () -> owner.queryForObject(
                                "SELECT count(*) FROM shared_processed_event WHERE tenant_id = ?",
                                Integer.class,
                                salon.id())
                        > 0,
                "a processed record of " + salon.slug());
    }

    private static void await(BooleanSupplier condition, String what) {
        Instant until = Instant.now().plus(PATIENCE);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(until)) {
                throw new AssertionError("Timed out waiting for " + what);
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError(ex);
            }
        }
    }

    private static <T> T inTransaction(Supplier<T> work) {
        return transaction.execute(status -> work.get());
    }

    private static <T> T as(TestSalon salon, Callable<T> work) throws Exception {
        return TenantContext.callAs(salons.asCurrent(salon), work);
    }
}
