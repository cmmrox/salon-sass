package com.salonplatform.shared.outbox;

import com.salonplatform.shared.tenancy.ActiveTenants;
import com.salonplatform.shared.tenancy.CurrentTenant;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.shared.web.DatabaseUnavailable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Delivers the outbox (NFR-15): provider messages, and events between modules (NFR-22). On each poll it
 * asks what is due across salons (ids only), then for each message, with that message's salon set (T-7):
 *
 * <ol>
 *   <li>claims it in a short transaction with a lease, once its channel has a free call and its
 *       breaker, if it has one, lets the call through, and commits;
 *   <li>hands it to {@link DeliveryAttempts}, which calls the channel's provider with no transaction open
 *       and no lock held, with the message id as the idempotency key, within the provider's time limit
 *       (for an event, the subscribers of its type, each in a transaction of its own, {@link
 *       EventSubscribers}), and records the outcome in a new transaction: {@code sent}, or a wait by the
 *       backoff before the next attempt, or {@code dead} once the attempts are used up.
 * </ol>
 *
 * <p>Two dispatchers, in one process or two, never deliver one message at the same time: a claim
 * locks the row and skips one that is locked, and a claimed message is not due again until its
 * lease runs out, which is longer than any call may take. A delivery interrupted by a stop is
 * attempted again once its lease runs out, with the same idempotency key. Every time is the
 * application's clock.
 *
 * <p>On stop it claims nothing more (the claim in progress, if any, is finished, not interrupted)
 * and gives calls in flight {@code shutdown-grace} to finish and record their outcome; what is
 * still in flight then is abandoned and left to its lease.
 */
final class OutboxDispatcher implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    /** The pause between polls while there is work, so that a backlog drains quickly. */
    private static final Duration BUSY_PAUSE = Duration.ofMillis(50);

    private final OutboxSettings settings;

    private final OutboxStore store;

    private final DeliveryAttempts attempts;

    /** The channels this dispatcher delivers: its providers', and the events' channel. */
    private final Set<String> channels;

    private final Map<String, ChannelGuard> guards;

    private final ActiveTenants tenants;

    private final TransactionTemplate transactions;

    private final Clock clock;

    private final OutboxMetrics metrics;

    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    private volatile boolean running;

    private volatile boolean pollFailing;

    /**
     * Released on stop, so that the wait between polls ends at once and no claim is interrupted.
     */
    private volatile CountDownLatch stopping = new CountDownLatch(1);

    private Thread poller;

    private ExecutorService deliveries;

    OutboxDispatcher(
            OutboxSettings settings,
            OutboxStore store,
            DeliveryAttempts attempts,
            Map<String, ChannelGuard> guards,
            ActiveTenants tenants,
            PlatformTransactionManager transactionManager,
            Clock clock,
            OutboxMetrics metrics) {
        this.settings = settings;
        this.store = store;
        this.attempts = attempts;
        Set<String> delivered = new HashSet<>(attempts.providerChannels());
        delivered.add(EventTypes.CHANNEL);
        this.channels = Set.copyOf(delivered);
        this.guards = guards;
        this.tenants = tenants;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.metrics = metrics;
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        stopping = new CountDownLatch(1);
        deliveries = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("outbox-delivery-", 0).factory());
        attempts.open();
        poller = Thread.ofVirtual().name("outbox-dispatcher").start(this::pollUntilStopped);
        log.info("The outbox dispatcher started for the channels {}", channels);
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        stopping.countDown();
        if (!join(poller, settings.shutdownGrace().plusSeconds(1))) {
            poller.interrupt();
        }
        deliveries.shutdown();
        if (!awaitQuietly(deliveries, settings.shutdownGrace())) {
            // Still in flight: left to its lease, which brings it back after a restart.
            log.warn(
                    "Stopping with {} deliveries in flight; each is attempted again once its lease" + " runs out",
                    inFlight.size());
            attempts.abandon();
            deliveries.shutdownNow();
            awaitQuietly(deliveries, Duration.ofSeconds(1));
        }
        attempts.close();
        log.info("The outbox dispatcher stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * One poll: refreshes the backlog meters, asks what is due across salons, and starts the
     * delivery of each message it could claim. Returns how many it started.
     */
    private int pollOnce() {
        Instant now = clock.instant();
        try {
            metrics.backlog(store.backlog(now), now);
        } catch (RuntimeException ex) {
            log.debug("The outbox backlog could not be read", ex);
        }
        List<OutboxStore.Due> due = store.due(now, channels, settings.perSalon(), settings.batchSize());
        Map<UUID, Optional<Deliverable>> salons = new HashMap<>();
        int started = 0;
        for (OutboxStore.Due message : due) {
            if (!running || inFlight.contains(message.messageId())) {
                continue;
            }
            Optional<Deliverable> salon = salons.computeIfAbsent(message.tenantId(), this::deliverable);
            if (salon.isPresent() && take(salon.get().salon(), salon.get().closed(), message.messageId(), now)) {
                started++;
            }
        }
        return started;
    }

    private void pollUntilStopped() {
        while (running) {
            int started = 0;
            try {
                started = pollOnce();
                if (pollFailing) {
                    pollFailing = false;
                    log.info("The outbox dispatcher can read the outbox again");
                }
            } catch (RuntimeException ex) {
                if (!pollFailing) {
                    pollFailing = true;
                    log.warn(
                            "The outbox dispatcher cannot read the outbox ({}); it keeps trying",
                            DatabaseUnavailable.isCauseOf(ex) ? DatabaseUnavailable.describe(ex) : ex.toString());
                }
            }
            if (!pause(started > 0 ? BUSY_PAUSE : settings.pollInterval())) {
                return;
            }
        }
    }

    /**
     * A salon whose messages may be delivered: an active one (every channel), or a closed one, for
     * which only {@link OutboxLifecycle#PLATFORM_EMAIL} is delivered (F03-S03).
     */
    private record Deliverable(CurrentTenant salon, boolean closed) {}

    private Optional<Deliverable> deliverable(UUID id) {
        Optional<CurrentTenant> active = tenants.findActive(id);
        if (active.isPresent()) {
            return Optional.of(new Deliverable(active.get(), false));
        }
        return tenants.findClosed(id).map(salon -> new Deliverable(salon, true));
    }

    /** Claims one message and starts its delivery; false when it was not taken. */
    private boolean take(CurrentTenant salon, boolean closed, UUID messageId, Instant now) {
        List<ChannelGuard.Permit> reserved = new ArrayList<>(1);
        OutboxStore.Taken taken;
        try {
            taken = TenantContext.callAs(
                    salon,
                    () -> transactions.execute(status -> store.claim(
                            salon.id(),
                            messageId,
                            now,
                            settings.lease(),
                            settings.maxAttempts(),
                            channel -> closed && !OutboxLifecycle.PLATFORM_EMAIL.equals(channel)
                                    ? Optional.empty()
                                    : permitFor(channel),
                            reserved)));
        } catch (Exception ex) {
            reserved.forEach(ChannelGuard.Permit::abandoned);
            throw ex instanceof RuntimeException runtime ? runtime : new IllegalStateException(ex);
        }
        if (taken instanceof OutboxStore.OutOfAttempts) {
            metrics.dead();
            DeliveryAttempts.runAs(
                    salon, () -> log.error("Outbox message {} is dead: its last attempt was interrupted", messageId));
            return false;
        }
        if (!(taken instanceof OutboxStore.Claimed claimed)) {
            return false;
        }
        ChannelGuard.Permit permit = reserved.getFirst();
        inFlight.add(messageId);
        try {
            deliveries.execute(() -> {
                try {
                    attempts.deliver(salon, claimed.claim(), permit);
                } finally {
                    inFlight.remove(messageId);
                }
            });
        } catch (RuntimeException ex) {
            // Stopping: the claim's lease brings it back.
            inFlight.remove(messageId);
            permit.abandoned();
            return false;
        }
        return true;
    }

    private Optional<ChannelGuard.Permit> permitFor(String channel) {
        ChannelGuard guard = guards.get(channel);
        return guard == null ? Optional.empty() : guard.tryAcquire();
    }

    /** Waits between polls; false when the dispatcher was stopped meanwhile. */
    private boolean pause(Duration duration) {
        try {
            return !stopping.await(duration.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Whether the thread ended within the limit. */
    private static boolean join(Thread thread, Duration limit) {
        try {
            return thread.join(limit);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean awaitQuietly(ExecutorService executor, Duration limit) {
        try {
            return executor.awaitTermination(limit.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
