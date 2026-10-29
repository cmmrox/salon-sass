package com.salonplatform.shared.outbox;

/**
 * Handles one type of event between modules. A module registers its subscriber as a Spring bean in its
 * {@code event/} folder; the outbox's dispatcher delivers each event of that type to it once, for the
 * event's salon, with that salon set (docs/engineering/backend.md, "Events between modules").
 *
 * <p>{@link #handle} runs in a transaction of its own, opened by the dispatcher, in which the outbox also
 * records that this subscriber processed the event: both commit together, so a repeated delivery is
 * ignored. A subscriber that throws is attempted again with the outbox's backoff, until the outbox's
 * maximum attempts; the other subscribers of the same event are not run again once they succeeded. A
 * failure's message is stored as the event's last error and logged: it must never contain a secret or
 * personal data.
 *
 * @param <E> the event record, annotated with {@link EventType}
 */
public interface EventSubscriber<E extends Record> {

    /**
     * The subscriber's stable name, recorded with every event it processes: {@code <module>.<what it
     * does>}, such as {@code identity.local-message-capture}. Renaming a subscriber makes it a new one, which
     * would process again every event still in the outbox.
     */
    String name();

    /** The event record this subscriber handles. */
    Class<E> event();

    /** Handles one event, inside the transaction that records it as processed. */
    void handle(E event);
}
