package com.salonplatform.shared.outbox.impl;

import com.salonplatform.shared.outbox.EventSubscriber;
import com.salonplatform.shared.outbox.EventType;
import com.salonplatform.shared.outbox.Outbox;
import com.salonplatform.shared.tenancy.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Probe events and their subscribers, added to an application started by an events test: what each
 * subscriber was given, for which salon, and when. The records are kept statically, so that two
 * applications in one test (two dispatchers) add to the same tally; each test uses marks of its own.
 */
final class EventProbes {

    static final String FIRST = "probe.first";

    static final String SECOND = "probe.second";

    static final String STEADY = "probe.steady";

    static final String FLAKY = "probe.flaky";

    static final String SLOW = "probe.slow";

    static final String REACHER = "probe.reacher";

    /** Handled by {@value #FIRST} and {@value #SECOND}. */
    @EventType("probe.Noted.v1")
    record Noted(UUID mark) {}

    /** Handled by {@value #STEADY}, and by {@value #FLAKY}, which fails as often as it is told to. */
    @EventType("probe.Flaky.v1")
    record Flaky(UUID mark) {}

    /** Handled by {@value #SLOW}, which holds its transaction open for a while. */
    @EventType("probe.Slow.v1")
    record Slow(UUID mark) {}

    /** Handled by {@value #REACHER}, which tries to reach another salon's message. */
    @EventType("probe.Reached.v1")
    record Reached(UUID mark, UUID othersMessage) {}

    /** No subscriber handles it. */
    @EventType("probe.Unheard.v1")
    record Unheard(UUID mark) {}

    /** A record with no event type. */
    record NotAnEvent(UUID mark) {}

    /**
     * What the reaching subscriber saw while it handled one salon's event.
     *
     * @param salon the salon set while it ran
     * @param sqlSalons the salons whose messages direct SQL, with no salon named, returned
     * @param sqlFoundOthers how many rows direct SQL found by the other salon's message id
     * @param sqlChangedOthers how many rows direct SQL changed by that id
     * @param processedSalons the salons whose processed records direct SQL returned
     * @param jpaSalons the salons whose messages the repository returned
     * @param jpaFoundOthers whether the repository found the other salon's message by its id
     * @param written the salon stored with a message it wrote through the repository
     */
    record Reach(
            UUID salon,
            List<UUID> sqlSalons,
            int sqlFoundOthers,
            int sqlChangedOthers,
            List<UUID> processedSalons,
            List<UUID> jpaSalons,
            boolean jpaFoundOthers,
            UUID written) {}

    private static final Map<String, List<UUID>> HANDLED = new ConcurrentHashMap<>();

    private static final Map<UUID, List<Instant>> FLAKY_ATTEMPTS = new ConcurrentHashMap<>();

    private static final Map<UUID, AtomicInteger> FLAKY_FAILURES = new ConcurrentHashMap<>();

    private static final Map<UUID, Reach> REACHES = new ConcurrentHashMap<>();

    private EventProbes() {}

    /** The salon set each time that subscriber handled the event with that mark, in order. */
    static List<UUID> handled(String subscriber, UUID mark) {
        return List.copyOf(HANDLED.getOrDefault(subscriber + "/" + mark, List.of()));
    }

    /** When the flaky subscriber was given the event with that mark, failing or not. */
    static List<Instant> flakyAttempts(UUID mark) {
        return List.copyOf(FLAKY_ATTEMPTS.getOrDefault(mark, List.of()));
    }

    /** The flaky subscriber fails this many times on the event with that mark before it succeeds. */
    static void failFlaky(UUID mark, int times) {
        FLAKY_FAILURES.put(mark, new AtomicInteger(times));
    }

    static Reach reach(UUID mark) {
        return REACHES.get(mark);
    }

    private static void handle(String subscriber, UUID mark) {
        HANDLED.computeIfAbsent(subscriber + "/" + mark, key -> new CopyOnWriteArrayList<>())
                .add(TenantContext.require().id());
    }

    private static <E extends Record> EventSubscriber<E> subscriber(String name, Class<E> event, Consumer<E> work) {
        return new EventSubscriber<>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Class<E> event() {
                return event;
            }

            @Override
            public void handle(E handled) {
                work.accept(handled);
            }
        };
    }

    /** The probe subscribers, as beans of the application under test. */
    @Configuration(proxyBeanMethods = false)
    static class Subscribers {

        @Bean
        EventSubscriber<Noted> firstOfNoted() {
            return subscriber(FIRST, Noted.class, event -> handle(FIRST, event.mark()));
        }

        @Bean
        EventSubscriber<Noted> secondOfNoted() {
            return subscriber(SECOND, Noted.class, event -> handle(SECOND, event.mark()));
        }

        @Bean
        EventSubscriber<Flaky> steadyOfFlaky() {
            return subscriber(STEADY, Flaky.class, event -> handle(STEADY, event.mark()));
        }

        @Bean
        EventSubscriber<Flaky> flakyOfFlaky() {
            return subscriber(FLAKY, Flaky.class, event -> {
                FLAKY_ATTEMPTS
                        .computeIfAbsent(event.mark(), mark -> new CopyOnWriteArrayList<>())
                        .add(Instant.now());
                AtomicInteger failures = FLAKY_FAILURES.getOrDefault(event.mark(), new AtomicInteger());
                if (failures.getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
                    throw new IllegalStateException("the flaky probe refused " + event.mark());
                }
                handle(FLAKY, event.mark());
            });
        }

        @Bean
        EventSubscriber<Slow> slowOfSlow() {
            return subscriber(SLOW, Slow.class, event -> {
                handle(SLOW, event.mark());
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
            });
        }

        @Bean
        EventSubscriber<Reached> reacherOfReached(JdbcClient jdbc, OutboxMessageRepository messages, Outbox outbox) {
            return subscriber(REACHER, Reached.class, event -> {
                UUID others = event.othersMessage();
                List<UUID> sqlSalons = jdbc.sql("SELECT DISTINCT tenant_id FROM outbox_message")
                        .query(UUID.class)
                        .list();
                int sqlFound = jdbc.sql("SELECT count(*) FROM outbox_message WHERE id = ?")
                        .param(others)
                        .query(Integer.class)
                        .single();
                int sqlChanged = jdbc.sql("UPDATE outbox_message SET last_error = 'reached' WHERE id = ?")
                        .param(others)
                        .update();
                List<UUID> processedSalons = jdbc.sql("SELECT DISTINCT tenant_id FROM shared_processed_event")
                        .query(UUID.class)
                        .list();
                List<UUID> jpaSalons = messages.findAll().stream()
                        .map(OutboxMessage::getTenantId)
                        .distinct()
                        .toList();
                boolean jpaFound = messages.findById(others).isPresent();
                UUID written = outbox.enqueue("reached", Map.of());
                messages.flush();
                UUID writtenFor = jdbc.sql("SELECT tenant_id FROM outbox_message WHERE id = ?")
                        .param(written)
                        .query(UUID.class)
                        .single();
                REACHES.put(
                        event.mark(),
                        new Reach(
                                TenantContext.require().id(),
                                sqlSalons,
                                sqlFound,
                                sqlChanged,
                                processedSalons,
                                jpaSalons,
                                jpaFound,
                                writtenFor));
            });
        }
    }
}
