package com.salonplatform.shared.outbox.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.shared.tenancy.TenantContext;
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
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The outbox's direct SQL ({@link OutboxStore}), the named exceptions Hibernate's filter never sees: cancelling
 * and erasing one message, and the dispatcher's claim, its outcomes and its cleanup. Each names its salon; here
 * each is run in alpha's transaction naming beta's message, so that row-level security alone stands between
 * them, and none of beta's messages changes. Naming alpha's own, the same statements do their work.
 */
class OutboxStoreRowLevelSecurityAloneIntegrationTest {

    private static final Duration LEASE = Duration.ofSeconds(60);

    private static TestDatabase database;

    private static StartedApplication application;

    private static TestSalons salons;

    private static OutboxStore store;

    private static TransactionTemplate transaction;

    private TestSalon alpha;

    private TestSalon beta;

    private UUID alphasMessage;

    private UUID betasMessage;

    private UUID betasSentMessage;

    @BeforeAll
    static void applicationWithRowLevelSecurityOn() {
        database = TestDatabase.createEmpty(TestPostgres.shared());
        application = database.start();
        salons = new TestSalons(database.asOwner());
        store = application.bean(OutboxStore.class);
        transaction = new TransactionTemplate(application.bean(PlatformTransactionManager.class));
    }

    @AfterAll
    static void stop() {
        application.close();
    }

    @BeforeEach
    void aWaitingMessageForEachSalonAndASentOneForBeta() {
        alpha = salons.create("Outbox Alpha", "#111111");
        beta = salons.create("Outbox Beta", "#222222");
        alphasMessage = message(alpha, "pending", null);
        betasMessage = message(beta, "pending", null);
        betasSentMessage = message(beta, "sent", Instant.now().minus(Duration.ofDays(60)));
    }

    @Test
    void cancellingAndErasingAnotherSalonsMessageReachesNothing() {
        List<Map<String, Object>> before = betasMessages();

        boolean cancelled = inAlphasTransaction(() -> store.cancelUndelivered(beta.id(), betasMessage, Instant.now()));
        inAlphasTransaction(() -> {
            store.remove(beta.id(), betasMessage);
            store.remove(beta.id(), betasSentMessage);
            return null;
        });

        assertThat(cancelled).isFalse();
        assertThat(betasMessages()).isEqualTo(before);
    }

    @Test
    void cancellingAndErasingTheSalonsOwnMessageDoesItsWork() {
        boolean cancelled =
                inAlphasTransaction(() -> store.cancelUndelivered(alpha.id(), alphasMessage, Instant.now()));

        assertThat(cancelled).isTrue();
        assertThat(status(alphasMessage)).isEqualTo("cancelled");

        inAlphasTransaction(() -> {
            store.remove(alpha.id(), alphasMessage);
            return null;
        });
        assertThat(database.asOwner()
                        .queryForObject(
                                "SELECT count(*) FROM outbox_message WHERE id = ?", Integer.class, alphasMessage))
                .isZero();
    }

    @Test
    void theDispatchersStatementsNamingAnotherSalonsMessageReachNothing() {
        List<Map<String, Object>> before = betasMessages();
        Instant now = Instant.now();
        OutboxStore.Claim forged =
                new OutboxStore.Claim(betasMessage, beta.id(), "probe", null, "{}", 1, UUID.randomUUID(), null);

        OutboxStore.Taken taken = inAlphasTransaction(() -> store.claim(
                beta.id(), betasMessage, now, LEASE, 10, channel -> Optional.of(channel), new ArrayList<>()));
        boolean anyOutcome = inAlphasTransaction(() -> store.sent(forged, now)
                || store.retry(forged, now, "failed", now)
                || store.dead(forged, "failed", now)
                || store.cancelled(forged, now));
        int removed = inAlphasTransaction(() -> store.removeSentBefore(beta.id(), now));

        assertThat(taken).isInstanceOf(OutboxStore.NotTaken.class);
        assertThat(anyOutcome).isFalse();
        assertThat(removed).isZero();
        assertThat(betasMessages()).isEqualTo(before);
    }

    @Test
    void theDispatchersClaimOnTheSalonsOwnMessageTakesIt() {
        OutboxStore.Taken taken = inAlphasTransaction(() -> store.claim(
                alpha.id(),
                alphasMessage,
                Instant.now(),
                LEASE,
                10,
                channel -> Optional.of(channel),
                new ArrayList<>()));

        assertThat(taken).isInstanceOf(OutboxStore.Claimed.class);
        assertThat(status(alphasMessage)).isEqualTo("sending");
    }

    private UUID message(TestSalon salon, String status, Instant sentAt) {
        UUID id = UUID.randomUUID();
        database.asOwner().update("""
                        INSERT INTO outbox_message (tenant_id, id, channel, payload, status, next_attempt_at, sent_at,
                            created_at, updated_at)
                        VALUES (?, ?, 'probe', '{}', ?, now() - interval '1 minute', ?, now(), now())
                        """, salon.id(), id, status, sentAt == null ? null : java.sql.Timestamp.from(sentAt));
        return id;
    }

    private List<Map<String, Object>> betasMessages() {
        return database.asOwner()
                .queryForList("SELECT * FROM outbox_message WHERE tenant_id = ? ORDER BY id", beta.id());
    }

    private String status(UUID message) {
        return database.asOwner()
                .queryForObject("SELECT status FROM outbox_message WHERE id = ?", String.class, message);
    }

    private <T> T inAlphasTransaction(Supplier<T> work) {
        try {
            return TenantContext.callAs(salons.asCurrent(alpha), () -> transaction.execute(status -> work.get()));
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
