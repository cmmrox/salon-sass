package com.salonplatform.shared.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.salonplatform.shared.api.DatabaseUnavailable;
import com.salonplatform.shared.observability.Traces;
import com.salonplatform.shared.tenancy.ActiveTenants;
import com.salonplatform.shared.tenancy.CurrentTenant;
import com.salonplatform.shared.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.SmartLifecycle;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Delivers the outbox (NFR-15). On each poll it asks what is due across salons (ids only), then for
 * each message, with that message's salon set (T-7):
 * <ol>
 * <li>claims it in a short transaction with a lease, once its channel has a free call and its
 * breaker lets the call through, and commits;</li>
 * <li>calls the channel's provider with no transaction open and no lock held, with the message id
 * as the idempotency key, within the provider's time limit;</li>
 * <li>records the outcome in a new transaction: {@code sent}, or a wait by the backoff before the
 * next attempt, or {@code dead} once the attempts are used up.</li>
 * </ol>
 *
 * <p>Two dispatchers, in one process or two, never deliver one message at the same time: a claim
 * locks the row and skips one that is locked, and a claimed message is not due again until its lease
 * runs out, which is longer than any call may take. A delivery interrupted by a stop is attempted
 * again once its lease runs out, with the same idempotency key. Every time is the application's
 * clock.
 *
 * <p>On stop it claims nothing more and gives calls in flight {@code shutdown-grace} to finish; what
 * is still in flight then is left to its lease.
 */
final class OutboxDispatcher implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    /** The pause between polls while there is work, so that a backlog drains quickly. */
    private static final Duration BUSY_PAUSE = Duration.ofMillis(50);

    private final OutboxSettings settings;

    private final OutboxStore store;

    private final Map<String, OutboxProvider> providers;

    private final Map<String, ChannelGuard> guards;

    private final ActiveTenants tenants;

    private final TransactionTemplate transactions;

    private final Clock clock;

    private final Traces traces;

    private final OutboxMetrics metrics;

    private final Backoff backoff;

    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    private volatile boolean running;

    private volatile boolean abandoning;

    private volatile boolean pollFailing;

    private Thread poller;

    private ExecutorService deliveries;

    private ExecutorService calls;

    OutboxDispatcher(OutboxSettings settings, OutboxStore store, List<OutboxProvider> providers,
            Map<String, ChannelGuard> guards, ActiveTenants tenants, PlatformTransactionManager transactionManager,
            Clock clock, Traces traces, OutboxMetrics metrics) {
        this.settings = settings;
        this.store = store;
        Map<String, OutboxProvider> byChannel = new HashMap<>();
        for (OutboxProvider provider : providers) {
            if (byChannel.put(provider.channel(), provider) != null) {
                throw new IllegalStateException("Two outbox providers deliver the channel '" + provider.channel() + "'");
            }
        }
        this.providers = Map.copyOf(byChannel);
        this.guards = guards;
        this.tenants = tenants;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.traces = traces;
        this.metrics = metrics;
        this.backoff = new Backoff(settings.backoff());
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        abandoning = false;
        deliveries = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("outbox-delivery-", 0).factory());
        calls = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("outbox-call-", 0).factory());
        poller = Thread.ofVirtual().name("outbox-dispatcher").start(this::pollUntilStopped);
        log.info("The outbox dispatcher started for the channels {}", providers.keySet());
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        poller.interrupt();
        join(poller, settings.shutdownGrace().plusSeconds(1));
        deliveries.shutdown();
        if (!awaitQuietly(deliveries, settings.shutdownGrace())) {
            // Still in flight: left to its lease, which brings it back after a restart.
            abandoning = true;
            log.warn("Stopping with {} deliveries in flight; each is attempted again once its lease runs out",
                    inFlight.size());
            calls.shutdownNow();
            deliveries.shutdownNow();
            awaitQuietly(deliveries, Duration.ofSeconds(1));
        }
        calls.shutdownNow();
        log.info("The outbox dispatcher stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Polls until stopped. Exposed for tests that drive a dispatcher by hand. */
    int pollOnce() {
        Instant now = clock.instant();
        try {
            metrics.backlog(store.backlog(now), now);
        }
        catch (RuntimeException ex) {
            log.debug("The outbox backlog could not be read", ex);
        }
        List<OutboxStore.Due> due = store.due(now, providers.keySet(), settings.perSalon(), settings.batchSize());
        Map<UUID, Optional<CurrentTenant>> salons = new HashMap<>();
        int started = 0;
        for (OutboxStore.Due message : due) {
            if (!running || inFlight.contains(message.messageId())) {
                continue;
            }
            Optional<CurrentTenant> salon = salons.computeIfAbsent(message.tenantId(), tenants::findActive);
            if (salon.isPresent() && take(salon.get(), message.messageId(), now)) {
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
            }
            catch (RuntimeException ex) {
                if (!pollFailing) {
                    pollFailing = true;
                    log.warn("The outbox dispatcher cannot read the outbox ({}); it keeps trying",
                            DatabaseUnavailable.isCauseOf(ex) ? DatabaseUnavailable.describe(ex) : ex.toString());
                }
            }
            if (!pause(started > 0 ? BUSY_PAUSE : settings.pollInterval())) {
                return;
            }
        }
    }

    /** Claims one message and starts its delivery; false when it was not taken. */
    private boolean take(CurrentTenant salon, UUID messageId, Instant now) {
        List<ChannelGuard.Permit> reserved = new ArrayList<>(1);
        OutboxStore.Taken taken;
        try {
            taken = TenantContext.callAs(salon, () -> transactions.execute(status -> store.claim(salon.id(), messageId,
                    now, settings.lease(), settings.maxAttempts(), this::permitFor, reserved)));
        }
        catch (Exception ex) {
            reserved.forEach(ChannelGuard.Permit::abandoned);
            throw ex instanceof RuntimeException runtime ? runtime : new IllegalStateException(ex);
        }
        if (taken instanceof OutboxStore.OutOfAttempts) {
            metrics.dead();
            runAs(salon, () -> log.error("Outbox message {} is dead: its last attempt was interrupted", messageId));
            return false;
        }
        if (!(taken instanceof OutboxStore.Claimed claimed)) {
            return false;
        }
        ChannelGuard.Permit permit = reserved.getFirst();
        inFlight.add(messageId);
        try {
            deliveries.execute(() -> deliver(salon, claimed.claim(), permit));
        }
        catch (RuntimeException ex) {
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

    private void deliver(CurrentTenant salon, OutboxStore.Claim claim, ChannelGuard.Permit permit) {
        try {
            traces.inSpan("outbox.delivery", salon, claim.traceParent(), () -> TenantContext.callAs(salon, () -> {
                Outcome outcome = call(salon, claim, permit);
                if (outcome != null) {
                    record(claim, outcome);
                }
                return null;
            }));
        }
        catch (Exception ex) {
            runAs(salon, () -> log.warn("The outcome of delivering outbox message {} could not be recorded ({}); "
                    + "it is attempted again once its lease runs out", claim.messageId(),
                    DatabaseUnavailable.isCauseOf(ex) ? DatabaseUnavailable.describe(ex) : ex.toString()));
        }
        finally {
            permit.abandoned();
            inFlight.remove(claim.messageId());
        }
    }

    /** What the provider did with one attempt. */
    private record Outcome(boolean delivered, String outcome, String error) {
    }

    /** Calls the provider within its time limit; null when the call was abandoned because of a stop. */
    private Outcome call(CurrentTenant salon, OutboxStore.Claim claim, ChannelGuard.Permit permit) {
        OutboxProvider provider = providers.get(claim.channel());
        OutboxDelivery delivery = new OutboxDelivery(claim.messageId(), claim.tenantId(), claim.channel(),
                claim.payload(), claim.attempt());
        Duration limit = settings.provider().timeLimit();
        Future<?> call;
        try {
            call = calls.submit(traces.inCurrentSpan(() -> TenantContext.callAs(salon, () -> {
                provider.deliver(delivery);
                return null;
            })));
        }
        catch (RuntimeException ex) {
            permit.abandoned();
            return null;
        }
        try {
            call.get(limit.toMillis(), TimeUnit.MILLISECONDS);
            permit.succeeded();
            return new Outcome(true, "sent", null);
        }
        catch (TimeoutException ex) {
            call.cancel(true);
            permit.failed();
            return new Outcome(false, "timed_out", "The provider did not answer within " + limit.toMillis() + " ms");
        }
        catch (ExecutionException ex) {
            if (abandoning) {
                permit.abandoned();
                return null;
            }
            permit.failed();
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            return new Outcome(false, "failed", cause.getMessage() == null ? cause.getClass().getSimpleName()
                    : cause.getMessage());
        }
        catch (InterruptedException | java.util.concurrent.CancellationException ex) {
            call.cancel(true);
            permit.abandoned();
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private void record(OutboxStore.Claim claim, Outcome outcome) {
        if (abandoning) {
            return;
        }
        Instant now = clock.instant();
        metrics.delivery(claim.channel(), outcome.outcome());
        if (outcome.delivered()) {
            if (Boolean.TRUE.equals(transactions.execute(status -> store.sent(claim, now)))) {
                log.info("Delivered outbox message {} on '{}' (attempt {})", claim.messageId(), claim.channel(),
                        claim.attempt());
            }
            return;
        }
        if (claim.attempt() >= settings.maxAttempts()) {
            if (Boolean.TRUE.equals(transactions.execute(status -> store.dead(claim, outcome.error(), now)))) {
                metrics.dead();
                log.error("Outbox message {} on '{}' is dead after {} attempts: {}", claim.messageId(), claim.channel(),
                        claim.attempt(), outcome.error());
            }
            return;
        }
        Instant next = now.plus(backoff.after(claim.attempt()));
        if (Boolean.TRUE.equals(transactions.execute(status -> store.retry(claim, next, outcome.error(), now)))) {
            log.warn("Delivering outbox message {} on '{}' failed (attempt {} of {}): {}; next attempt at {}",
                    claim.messageId(), claim.channel(), claim.attempt(), settings.maxAttempts(), outcome.error(), next);
        }
    }

    private static void runAs(CurrentTenant salon, Runnable work) {
        try {
            TenantContext.callAs(salon, () -> {
                work.run();
                return null;
            });
        }
        catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private boolean pause(Duration duration) {
        try {
            Thread.sleep(duration);
            return true;
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void join(Thread thread, Duration limit) {
        try {
            thread.join(limit);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean awaitQuietly(ExecutorService executor, Duration limit) {
        try {
            return executor.awaitTermination(limit.toMillis(), TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
