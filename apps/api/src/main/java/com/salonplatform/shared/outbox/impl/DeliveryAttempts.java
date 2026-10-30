package com.salonplatform.shared.outbox.impl;

import com.salonplatform.shared.observability.Traces;
import com.salonplatform.shared.outbox.OutboxCancelled;
import com.salonplatform.shared.outbox.OutboxDelivery;
import com.salonplatform.shared.outbox.OutboxProvider;
import com.salonplatform.shared.outbox.OutboxSettings;
import com.salonplatform.shared.tenancy.CurrentTenant;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.shared.web.DatabaseUnavailable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What happens to one message once {@link OutboxDispatcher} has claimed it, with its salon set: the attempt,
 * made with no transaction open and within the provider's time limit (the channel's provider for a message,
 * the subscribers of its type for an event), and its outcome, recorded in a new transaction: {@code sent}, a
 * wait by the backoff before the next attempt, or {@code dead} once the attempts are used up. Logged as
 * {@link OutboxDispatcher}, whose work this is.
 *
 * <p>Its calls run on an executor of their own, opened and closed with the dispatcher. Once the dispatcher
 * abandons the calls in flight on stop, no outcome is recorded: the claim's lease brings the message back.
 */
final class DeliveryAttempts {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxSettings settings;

    private final OutboxStore store;

    private final Map<String, OutboxProvider> providers;

    private final EventSubscribers events;

    private final TransactionTemplate transactions;

    private final Clock clock;

    private final Traces traces;

    private final OutboxMetrics metrics;

    private final Backoff backoff;

    private volatile boolean abandoning;

    private ExecutorService calls;

    DeliveryAttempts(
            OutboxSettings settings,
            OutboxStore store,
            List<OutboxProvider> providers,
            EventSubscribers events,
            PlatformTransactionManager transactionManager,
            Clock clock,
            Traces traces,
            OutboxMetrics metrics) {
        this.settings = settings;
        this.store = store;
        Map<String, OutboxProvider> byChannel = new HashMap<>();
        for (OutboxProvider provider : providers) {
            if (EventTypes.CHANNEL.equals(provider.channel())) {
                throw new IllegalStateException("The channel '" + EventTypes.CHANNEL
                        + "' carries events between modules; no provider delivers it");
            }
            if (byChannel.put(provider.channel(), provider) != null) {
                throw new IllegalStateException(
                        "Two outbox providers deliver the channel '" + provider.channel() + "'");
            }
        }
        this.providers = Map.copyOf(byChannel);
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.traces = traces;
        this.metrics = metrics;
        this.backoff = new Backoff(settings.backoff());
    }

    /** The channels with a provider here. */
    Set<String> providerChannels() {
        return providers.keySet();
    }

    /** Opens the executor the calls run on, when the dispatcher starts. */
    void open() {
        abandoning = false;
        calls = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("outbox-call-", 0).factory());
    }

    /** Gives up the calls in flight on stop: their outcomes are not recorded, and their leases bring them back. */
    void abandon() {
        abandoning = true;
        calls.shutdownNow();
    }

    /** Closes the executor, when the dispatcher has stopped. */
    void close() {
        calls.shutdownNow();
    }

    /** Makes the attempt for a claimed message and records its outcome; the permit is released whatever happens. */
    void deliver(CurrentTenant salon, OutboxStore.Claim claim, ChannelGuard.Permit permit) {
        try {
            traces.inSpan(
                    "outbox.delivery",
                    salon,
                    claim.traceParent(),
                    () -> TenantContext.callAs(salon, () -> {
                        try {
                            Outcome outcome = call(salon, claim, permit);
                            if (outcome != null) {
                                record(claim, outcome);
                            }
                        } catch (RuntimeException ex) {
                            // Written inside the span, so that the line carries the
                            // delivery's trace.
                            traces.failed(ex);
                            outcomeNotRecorded(claim, ex);
                        }
                        return null;
                    }));
        } catch (Exception ex) {
            runAs(salon, () -> outcomeNotRecorded(claim, ex));
        } finally {
            permit.abandoned();
        }
    }

    private static void outcomeNotRecorded(OutboxStore.Claim claim, Exception ex) {
        log.warn(
                "The outcome of delivering outbox message {} could not be recorded ({}); it is"
                        + " attempted again once its lease runs out",
                claim.messageId(),
                DatabaseUnavailable.isCauseOf(ex) ? DatabaseUnavailable.describe(ex) : ex.toString());
    }

    /** What the provider did with one attempt. */
    private record Outcome(boolean delivered, String outcome, String error) {}

    /**
     * Makes the attempt within the provider's time limit; null when the call was abandoned because of a
     * stop.
     */
    private Outcome call(CurrentTenant salon, OutboxStore.Claim claim, ChannelGuard.Permit permit) {
        Duration limit = settings.provider().timeLimit();
        Future<?> call;
        try {
            call = calls.submit(traces.inCurrentSpan(() -> TenantContext.callAs(salon, () -> {
                attempt(claim);
                return null;
            })));
        } catch (RuntimeException ex) {
            permit.abandoned();
            return null;
        }
        try {
            call.get(limit.toMillis(), TimeUnit.MILLISECONDS);
            permit.succeeded();
            return new Outcome(true, "sent", null);
        } catch (TimeoutException ex) {
            call.cancel(true);
            permit.failed();
            return new Outcome(false, "timed_out", "The provider did not answer within " + limit.toMillis() + " ms");
        } catch (ExecutionException ex) {
            if (abandoning) {
                permit.abandoned();
                return null;
            }
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            if (cause instanceof OutboxCancelled) {
                permit.succeeded();
                return new Outcome(false, "cancelled", null);
            }
            permit.failed();
            return new Outcome(
                    false,
                    "failed",
                    cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
        } catch (InterruptedException | java.util.concurrent.CancellationException ex) {
            call.cancel(true);
            permit.abandoned();
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** One attempt: the event's subscribers for an event, the channel's provider for a message. */
    private void attempt(OutboxStore.Claim claim) throws Exception {
        if (claim.isEvent()) {
            events.deliver(claim.messageId(), claim.eventType(), claim.payload());
            return;
        }
        providers
                .get(claim.channel())
                .deliver(new OutboxDelivery(
                        claim.messageId(), claim.tenantId(), claim.channel(), claim.payload(), claim.attempt()));
    }

    private void record(OutboxStore.Claim claim, Outcome outcome) {
        if (abandoning) {
            return;
        }
        Instant now = clock.instant();
        metrics.delivery(claim, outcome.outcome());
        if ("cancelled".equals(outcome.outcome())) {
            transactions.execute(status -> store.cancelled(claim, now));
            return;
        }
        if (outcome.delivered()) {
            if (Boolean.TRUE.equals(transactions.execute(status -> store.sent(claim, now)))) {
                log.info(
                        "Delivered outbox message {} on '{}' (attempt {})",
                        claim.messageId(),
                        destination(claim),
                        claim.attempt());
            }
            return;
        }
        if (claim.attempt() >= settings.maxAttempts()) {
            if (Boolean.TRUE.equals(transactions.execute(status -> store.dead(claim, outcome.error(), now)))) {
                metrics.dead();
                log.error(
                        "Outbox message {} on '{}' is dead after {} attempts: {}",
                        claim.messageId(),
                        destination(claim),
                        claim.attempt(),
                        outcome.error());
            }
            return;
        }
        Instant next = now.plus(backoff.after(claim.attempt()));
        if (Boolean.TRUE.equals(transactions.execute(status -> store.retry(claim, next, outcome.error(), now)))) {
            log.warn(
                    "Delivering outbox message {} on '{}' failed (attempt {} of {}): {}; next" + " attempt at {}",
                    claim.messageId(),
                    destination(claim),
                    claim.attempt(),
                    settings.maxAttempts(),
                    outcome.error(),
                    next);
        }
    }

    /** Where a message goes, for the logs: its event type, or its provider's channel. */
    private static String destination(OutboxStore.Claim claim) {
        return claim.isEvent() ? claim.eventType() : claim.channel();
    }

    /** Runs the work with the salon set, so that what it logs carries the salon. */
    static void runAs(CurrentTenant salon, Runnable work) {
        try {
            TenantContext.callAs(salon, () -> {
                work.run();
                return null;
            });
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
