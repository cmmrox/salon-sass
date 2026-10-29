package com.salonplatform.shared.outbox;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Component;

/**
 * The outbox's direct SQL: the dispatcher's leasing and conditional status updates of messages and events,
 * and the cancellation and
 * erasure of one message (named direct-SQL exceptions, {@code docs/engineering/backend.md}). What is due across
 * salons is asked of {@code outbox_due()}, which returns ids and nothing else; everything else is done for one
 * salon, inside a transaction that the caller opened with that salon set, so that row-level security holds, and
 * with that salon named in every statement as well.
 */
@Component
class OutboxStore {

    private static final String DUE = "SELECT message_id, tenant_id FROM outbox_due(?, ?, ?, ?)";

    private static final String BACKLOG = "SELECT waiting, oldest_due_at FROM outbox_backlog(?)";

    private static final String LOCK_DUE = """
            SELECT channel, event_type, payload::text AS payload, attempts, trace_parent FROM outbox_message
             WHERE tenant_id = ? AND id = ?
               AND ((status = 'pending' AND next_attempt_at <= ?) OR (status = 'sending' AND lease_until <= ?))
               FOR UPDATE SKIP LOCKED
            """;

    private static final String CLAIM = """
            UPDATE outbox_message
               SET status = 'sending', attempts = attempts + 1, lease_until = ?, lease_token = ?, updated_at = ?
             WHERE tenant_id = ? AND id = ?
            """;

    private static final String INTERRUPTED_FOR_GOOD = """
            UPDATE outbox_message
               SET status = 'dead', lease_until = NULL, lease_token = NULL, updated_at = ?,
                   last_error = 'Its last attempt was interrupted, and no attempts are left'
             WHERE tenant_id = ? AND id = ?
            """;

    private static final String SENT = """
            UPDATE outbox_message
               SET status = 'sent', sent_at = ?, lease_until = NULL, lease_token = NULL, last_error = NULL, updated_at = ?
             WHERE tenant_id = ? AND id = ? AND status = 'sending' AND lease_token = ?
            """;

    private static final String RETRY = """
            UPDATE outbox_message
               SET status = 'pending', next_attempt_at = ?, lease_until = NULL, lease_token = NULL, last_error = ?,
                   updated_at = ?
             WHERE tenant_id = ? AND id = ? AND status = 'sending' AND lease_token = ?
            """;

    private static final String DEAD = """
            UPDATE outbox_message
               SET status = 'dead', lease_until = NULL, lease_token = NULL, last_error = ?, updated_at = ?
             WHERE tenant_id = ? AND id = ? AND status = 'sending' AND lease_token = ?
            """;

    private static final String REMOVE_SENT = """
            DELETE FROM outbox_message WHERE tenant_id = ? AND status = 'sent' AND sent_at < ?
            """;

    private static final String CANCEL_UNDELIVERED = """
            UPDATE outbox_message SET status = 'cancelled', lease_until = NULL, lease_token = NULL, updated_at = ?
             WHERE tenant_id = ? AND id = ? AND status IN ('pending', 'sending', 'dead')
            """;

    private static final String CANCEL_CLAIMED = """
            UPDATE outbox_message SET status = 'cancelled', lease_until = NULL, lease_token = NULL, updated_at = ?
             WHERE tenant_id = ? AND id = ? AND status = 'sending' AND lease_token = ?
            """;

    private static final String REMOVE = "DELETE FROM outbox_message WHERE tenant_id = ? AND id = ?";

    private static final int LAST_ERROR_LENGTH = 500;

    private final JdbcClient jdbc;

    OutboxStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A message that is due, and its salon. */
    record Due(UUID messageId, UUID tenantId) {}

    /** Waiting messages across every salon, and when the oldest due one became due. */
    record Backlog(long waiting, Instant oldestDueAt) {}

    /**
     * A message this dispatcher now holds.
     *
     * @param eventType for an event, its type name; null for a provider's delivery
     */
    record Claim(
            UUID messageId,
            UUID tenantId,
            String channel,
            String eventType,
            String payload,
            int attempt,
            UUID leaseToken,
            String traceParent) {

        boolean isEvent() {
            return eventType != null;
        }
    }

    /** What happened when a due message was looked at. */
    sealed interface Taken permits Claimed, OutOfAttempts, NotTaken {}

    record Claimed(Claim claim) implements Taken {}

    /** Its last attempt was interrupted and it has none left: it is now dead. */
    record OutOfAttempts() implements Taken {}

    /** Not due any more, held by someone else, or its channel could not take it now. */
    record NotTaken() implements Taken {}

    /** A due message, locked for this dispatcher's transaction. */
    private record Locked(String channel, String eventType, String payload, int attempts, String traceParent) {}

    List<Due> due(Instant now, Collection<String> channels, int perSalon, int max) {
        return jdbc.sql(DUE)
                .params(utc(now), new SqlArrayValue("text", channels.toArray()), perSalon, max)
                .query((row, number) ->
                        new Due(row.getObject("message_id", UUID.class), row.getObject("tenant_id", UUID.class)))
                .list();
    }

    Backlog backlog(Instant now) {
        return jdbc.sql(BACKLOG)
                .param(utc(now))
                .query((row, number) -> {
                    OffsetDateTime oldest = row.getObject("oldest_due_at", OffsetDateTime.class);
                    return new Backlog(row.getLong("waiting"), oldest == null ? null : oldest.toInstant());
                })
                .single();
    }

    /**
     * Claims the message for one attempt, if it is still due and nobody else holds it: in the
     * caller's transaction, with the message's salon set. {@code gate} is asked, with the message's
     * channel, whether an attempt may start now; when it says no the message is left untouched.
     *
     * @param gate answers with what it reserved for the attempt, or nothing
     */
    <P> Taken claim(
            UUID tenantId,
            UUID messageId,
            Instant now,
            Duration lease,
            int maxAttempts,
            Function<String, Optional<P>> gate,
            List<P> reserved) {
        Optional<Locked> due = jdbc.sql(LOCK_DUE)
                .params(tenantId, messageId, utc(now), utc(now))
                .query((row, number) -> new Locked(
                        row.getString("channel"),
                        row.getString("event_type"),
                        row.getString("payload"),
                        row.getInt("attempts"),
                        row.getString("trace_parent")))
                .optional();
        if (due.isEmpty()) {
            return new NotTaken();
        }
        Locked message = due.get();
        if (message.attempts() >= maxAttempts) {
            jdbc.sql(INTERRUPTED_FOR_GOOD).params(utc(now), tenantId, messageId).update();
            return new OutOfAttempts();
        }
        Optional<P> permit = gate.apply(message.channel());
        if (permit.isEmpty()) {
            return new NotTaken();
        }
        reserved.add(permit.get());
        UUID token = UUID.randomUUID();
        jdbc.sql(CLAIM)
                .params(utc(now.plus(lease)), token, utc(now), tenantId, messageId)
                .update();
        return new Claimed(new Claim(
                messageId,
                tenantId,
                message.channel(),
                message.eventType(),
                message.payload(),
                message.attempts() + 1,
                token,
                message.traceParent()));
    }

    /** Records a delivery, if the claim still holds. */
    boolean sent(Claim claim, Instant now) {
        return jdbc.sql(SENT)
                        .params(utc(now), utc(now), claim.tenantId(), claim.messageId(), claim.leaseToken())
                        .update()
                == 1;
    }

    /** Records that the provider found the message cancelled, if the claim still holds. */
    boolean cancelled(Claim claim, Instant at) {
        return jdbc.sql(CANCEL_CLAIMED)
                        .params(utc(at), claim.tenantId(), claim.messageId(), claim.leaseToken())
                        .update()
                == 1;
    }

    /** Records a failed attempt to be tried again at that time, if the claim still holds. */
    boolean retry(Claim claim, Instant nextAttemptAt, String error, Instant now) {
        return jdbc.sql(RETRY)
                        .params(
                                utc(nextAttemptAt),
                                shorten(error),
                                utc(now),
                                claim.tenantId(),
                                claim.messageId(),
                                claim.leaseToken())
                        .update()
                == 1;
    }

    /** Records a failed last attempt, if the claim still holds. */
    boolean dead(Claim claim, String error, Instant now) {
        return jdbc.sql(DEAD)
                        .params(shorten(error), utc(now), claim.tenantId(), claim.messageId(), claim.leaseToken())
                        .update()
                == 1;
    }

    /** Removes the salon's messages delivered before that instant; with that salon set. */
    int removeSentBefore(UUID tenantId, Instant before) {
        return jdbc.sql(REMOVE_SENT).params(tenantId, utc(before)).update();
    }

    /**
     * Cancels one of the salon's undelivered messages, in the caller's transaction with that salon set. A
     * message already sent or cancelled is left as it is, and a claim in flight can no longer complete it.
     */
    boolean cancelUndelivered(UUID tenantId, UUID messageId, Instant at) {
        return jdbc.sql(CANCEL_UNDELIVERED).params(utc(at), tenantId, messageId).update() == 1;
    }

    /** Removes one of the salon's messages, whatever its state, in the caller's transaction with that salon set. */
    void remove(UUID tenantId, UUID messageId) {
        jdbc.sql(REMOVE).params(tenantId, messageId).update();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static String shorten(String error) {
        String text = error == null || error.isBlank() ? "The provider failed without saying why" : error.strip();
        return text.length() <= LAST_ERROR_LENGTH ? text : text.substring(0, LAST_ERROR_LENGTH);
    }
}
