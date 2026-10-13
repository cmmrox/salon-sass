package com.salonplatform.shared.infrastructure;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Lets a stopping instance leave the edge's rotation before it stops taking requests.
 *
 * <p>When the application starts to stop, its readiness turns {@code OUT_OF_SERVICE} at once
 * (Spring Boot publishes {@link ReadinessState#REFUSING_TRAFFIC} as the context closes, and so does
 * this). This is the first thing to stop, and it holds the rest of the stop for
 * {@code salon.server.drain-delay} while the server keeps answering every request it is sent: the
 * edge's health check sees the readiness and stops sending more. Only then does the server's graceful
 * shutdown begin, which finishes the requests in flight and refuses new ones. The outbox dispatcher
 * and the job scheduler, in the same phase, stop claiming work at the start of the delay.
 */
@Component
class ReadinessDrain implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ReadinessDrain.class);

    private final ApplicationEventPublisher events;

    private final Duration delay;

    private volatile boolean running;

    /** Whether this took the instance out of the rotation, so that a start after a stop puts it back. */
    private volatile boolean refused;

    ReadinessDrain(ApplicationEventPublisher events, @Value("${salon.server.drain-delay}") Duration delay) {
        this.events = events;
        this.delay = delay;
    }

    /**
     * At the first start nothing is done: Spring Boot reports the instance ready once it has
     * started. A start after a stop without a close (the context paused and resumed) reports it
     * ready again.
     */
    @Override
    public void start() {
        running = true;
        if (refused) {
            refused = false;
            AvailabilityChangeEvent.publish(events, this, ReadinessState.ACCEPTING_TRAFFIC);
        }
    }

    @Override
    public void stop() {
        drain();
    }

    /** Lets the beans of the same phase stop while the delay runs, rather than after it. */
    @Override
    public void stop(Runnable callback) {
        Thread.ofVirtual().name("readiness-drain").start(() -> {
            try {
                drain();
            }
            finally {
                callback.run();
            }
        });
    }

    private void drain() {
        if (!running) {
            return;
        }
        running = false;
        refused = true;
        AvailabilityChangeEvent.publish(events, this, ReadinessState.REFUSING_TRAFFIC);
        if (delay.isZero() || delay.isNegative()) {
            return;
        }
        log.info("Stopping: readiness is OUT_OF_SERVICE; requests are still answered for {} ms", delay.toMillis());
        try {
            Thread.sleep(delay);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Stops before anything else, the web server's graceful shutdown included. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
