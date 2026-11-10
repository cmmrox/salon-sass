package com.salonplatform.shared.outbox.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.testing.StartedApplication;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import com.salonplatform.testing.TestSalons;
import com.salonplatform.testing.TestSalons.TestSalon;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Events no subscriber of the build takes (decision 119): they are counted apart from the waiting messages, so
 * that they never look like a stuck dispatcher, and the outbox's housekeeping closes them as {@code unrouted}
 * once they have waited, never attempted, for {@code salon.outbox.unrouted-after}, salon by salon, then removes
 * them a retention later. The dispatcher and the scheduler are off, so that nothing here moves on its own.
 */
class UnroutedEventsIntegrationTest {

    private static final Duration UNROUTED_AFTER = Duration.ofDays(7);

    private static final Duration RETENTION = Duration.ofDays(30);

    private static TestDatabase database;

    private static StartedApplication application;

    private static JdbcTemplate owner;

    private static TestSalons salons;

    private static TestSalon alpha;

    private static TestSalon beta;

    @BeforeAll
    static void applicationWithNothingRunningOnItsOwn() {
        database = TestDatabase.createEmpty(TestPostgres.shared());
        application = database.startWith(
                EventProbes.Subscribers.class, "--salon.outbox.enabled=false", "--salon.jobs.enabled=false");
        owner = database.asOwner();
        salons = new TestSalons(owner);
        alpha = salons.create("Unrouted Alpha", "#111111");
        beta = salons.create("Unrouted Beta", "#222222");
    }

    @AfterAll
    static void stop() {
        application.close();
    }

    @Test
    void eventsNoSubscriberHereTakesAreCountedApartFromTheWaitingMessages() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        OutboxStore.Backlog before = backlog(now);

        Instant longAgo = Instant.parse("2000-01-01T00:00:00Z");
        insert(alpha, "probe", null, "pending", 0, longAgo, now);
        insert(alpha, "event", "probe.Noted.v1", "pending", 0, now.minusSeconds(60), now);
        insert(alpha, "event", "probe.Unheard.v1", "pending", 0, longAgo.minusSeconds(60), now);
        insert(alpha, "event", "probe.FromANewerVersion.v1", "pending", 0, longAgo.minusSeconds(60), now);
        insert(beta, "event", "probe.Unheard.v1", "pending", 0, longAgo.minusSeconds(60), now);

        OutboxStore.Backlog after = backlog(now);
        assertThat(after.waiting() - before.waiting())
                .as("the delivery and the subscribed event")
                .isEqualTo(2);
        assertThat(after.unsubscribed() - before.unsubscribed())
                .as("a declared type with no subscriber, and a type this build does not know, in two salons")
                .isEqualTo(3);
        assertThat(after.oldestDueAt())
                .as("the oldest due among the waiting, not among the unsubscribed, which are older")
                .isEqualTo(longAgo);
    }

    @Test
    void theHousekeepingClosesOnlyNeverAttemptedEventsNoSubscriberTakesOnceTheirWindowHasPassed() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant outsideTheWindow = now.minus(UNROUTED_AFTER).minusSeconds(60);
        Instant insideTheWindow = now.minus(UNROUTED_AFTER).plusSeconds(60);
        UUID closed = insert(alpha, "event", "probe.Unheard.v1", "pending", 0, outsideTheWindow, outsideTheWindow);
        UUID unknownClosed =
                insert(alpha, "event", "probe.FromANewerVersion.v1", "pending", 0, outsideTheWindow, outsideTheWindow);
        UUID young = insert(alpha, "event", "probe.Unheard.v1", "pending", 0, insideTheWindow, insideTheWindow);
        UUID attempted = insert(alpha, "event", "probe.Unheard.v1", "pending", 1, outsideTheWindow, outsideTheWindow);
        UUID subscribed = insert(alpha, "event", "probe.Noted.v1", "pending", 0, outsideTheWindow, outsideTheWindow);
        UUID delivery = insert(alpha, "probe", null, "pending", 0, outsideTheWindow, outsideTheWindow);
        UUID betas = insert(beta, "event", "probe.Unheard.v1", "pending", 0, outsideTheWindow, outsideTheWindow);

        runCleanupFor(alpha, now);

        assertThat(owner.queryForMap("SELECT status, attempts, updated_at FROM outbox_message WHERE id = ?", closed))
                .containsEntry("status", "unrouted")
                .containsEntry("attempts", 0)
                .containsEntry("updated_at", Timestamp.from(now));
        assertThat(status(unknownClosed)).isEqualTo("unrouted");
        assertThat(status(young)).as("still inside its window").isEqualTo("pending");
        assertThat(status(attempted)).as("a subscriber somewhere has tried it").isEqualTo("pending");
        assertThat(status(subscribed)).as("this build subscribes to it").isEqualTo("pending");
        assertThat(status(delivery)).as("a provider's message").isEqualTo("pending");
        assertThat(status(betas))
                .as("another salon's, closed only in its own run")
                .isEqualTo("pending");

        runCleanupFor(beta, now);
        assertThat(status(betas)).isEqualTo("unrouted");
    }

    @Test
    void anUnroutedEventIsRemovedARetentionAfterItWasClosedAndNotBefore() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant published = now.minus(Duration.ofDays(60));
        UUID expired = insert(
                alpha,
                "event",
                "probe.Unheard.v1",
                "unrouted",
                0,
                published,
                now.minus(RETENTION).minusSeconds(60));
        UUID kept = insert(
                alpha,
                "event",
                "probe.Unheard.v1",
                "unrouted",
                0,
                published,
                now.minus(RETENTION).plusSeconds(60));

        runCleanupFor(alpha, now);

        assertThat(owner.queryForObject("SELECT count(*) FROM outbox_message WHERE id = ?", Integer.class, expired))
                .isZero();
        assertThat(status(kept)).isEqualTo("unrouted");
    }

    @Test
    void theDatabaseRefusesToCloseAProvidersMessageAsUnrouted() {
        UUID delivery = insert(alpha, "probe", null, "pending", 0, Instant.now(), Instant.now());

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> owner.update("UPDATE outbox_message SET status = 'unrouted' WHERE id = ?", delivery));
    }

    private static OutboxStore.Backlog backlog(Instant now) {
        return application
                .bean(OutboxStore.class)
                .backlog(now, application.bean(EventSubscribers.class).types());
    }

    private static void runCleanupFor(TestSalon salon, Instant now) {
        OutboxCleanupJob job = application.bean(OutboxCleanupJob.class);
        TransactionTemplate transaction = new TransactionTemplate(application.bean(PlatformTransactionManager.class));
        try {
            TenantContext.callAs(
                    salons.asCurrent(salon),
                    () -> transaction.execute(status -> {
                        job.runFor(salons.asCurrent(salon), now);
                        return null;
                    }));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** A message written as the owner, published (and first due) at {@code publishedAt}. */
    private static UUID insert(
            TestSalon salon,
            String channel,
            String eventType,
            String status,
            int attempts,
            Instant publishedAt,
            Instant updatedAt) {
        UUID id = UUID.randomUUID();
        owner.update(
                "INSERT INTO outbox_message (tenant_id, id, kind, channel, event_type, payload, status, attempts,"
                        + " next_attempt_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, '{}'::jsonb, ?, ?, ?, ?, ?)",
                salon.id(),
                id,
                eventType == null ? "delivery" : "event",
                channel,
                eventType,
                status,
                attempts,
                Timestamp.from(publishedAt),
                Timestamp.from(publishedAt),
                Timestamp.from(updatedAt));
        return id;
    }

    private static String status(UUID message) {
        return owner.queryForObject("SELECT status FROM outbox_message WHERE id = ?", String.class, message);
    }
}
