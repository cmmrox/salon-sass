package com.salonplatform.shared.outbox;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The dispatcher's reads and writes of the outbox. What is due across salons is asked of {@code
 * outbox_due()}, which returns ids and nothing else; everything else is done for one salon, inside
 * a transaction that the caller opened with that salon set, so that row-level security holds, and
 * with that salon named in every statement as well.
 */
@Component
class OutboxStore {

    private static final String DUE = "SELECT message_id, tenant_id FROM outbox_due(?, ?, ?, ?)";

    private static final String BACKLOG = "SELECT waiting, oldest_due_at FROM outbox_backlog(?)";

    private static final String LOCK_DUE = """
            SELECT channel, payload::text AS payload, attempts, trace_parent FROM outbox_message
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

    private static final int LAST_ERROR_LENGTH = 500;

    private final JdbcTemplate jdbc;

    OutboxStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A message that is due, and its salon. */
    record Due(UUID messageId, UUID tenantId) {}

    /** Waiting messages across every salon, and when the oldest due one became due. */
    record Backlog(long waiting, Instant oldestDueAt) {}

    /** A message this dispatcher now holds. */
    record Claim(
            UUID messageId,
            UUID tenantId,
            String channel,
            String payload,
            int attempt,
            UUID leaseToken,
            String traceParent) {}

    /** What happened when a due message was looked at. */
    sealed interface Taken permits Claimed, OutOfAttempts, NotTaken {}

    record Claimed(Claim claim) implements Taken {}

    /** Its last attempt was interrupted and it has none left: it is now dead. */
    record OutOfAttempts() implements Taken {}

    /** Not due any more, held by someone else, or its channel could not take it now. */
    record NotTaken() implements Taken {}

    List<Due> due(Instant now, Collection<String> channels, int perSalon, int max) {
        return jdbc.execute((ConnectionCallback<List<Due>>) connection -> {
            Array channelArray = connection.createArrayOf("text", channels.toArray());
            try (PreparedStatement statement = connection.prepareStatement(DUE)) {
                statement.setObject(1, utc(now));
                statement.setArray(2, channelArray);
                statement.setInt(3, perSalon);
                statement.setInt(4, max);
                List<Due> due = new ArrayList<>();
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        due.add(new Due(
                                rows.getObject("message_id", UUID.class), rows.getObject("tenant_id", UUID.class)));
                    }
                }
                return due;
            } finally {
                channelArray.free();
            }
        });
    }

    Backlog backlog(Instant now) {
        return jdbc.queryForObject(
                BACKLOG,
                (row, n) -> {
                    OffsetDateTime oldest = row.getObject("oldest_due_at", OffsetDateTime.class);
                    return new Backlog(row.getLong("waiting"), oldest == null ? null : oldest.toInstant());
                },
                utc(now));
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
        List<Object[]> rows = jdbc.query(
                LOCK_DUE,
                (row, n) -> new Object[] {
                    row.getString("channel"),
                    row.getString("payload"),
                    row.getInt("attempts"),
                    row.getString("trace_parent")
                },
                tenantId,
                messageId,
                utc(now),
                utc(now));
        if (rows.isEmpty()) {
            return new NotTaken();
        }
        String channel = (String) rows.getFirst()[0];
        int attempts = (Integer) rows.getFirst()[2];
        if (attempts >= maxAttempts) {
            jdbc.update(INTERRUPTED_FOR_GOOD, utc(now), tenantId, messageId);
            return new OutOfAttempts();
        }
        Optional<P> permit = gate.apply(channel);
        if (permit.isEmpty()) {
            return new NotTaken();
        }
        reserved.add(permit.get());
        UUID token = UUID.randomUUID();
        jdbc.update(CLAIM, utc(now.plus(lease)), token, utc(now), tenantId, messageId);
        return new Claimed(
                new Claim(messageId, tenantId, channel, (String) rows.getFirst()[1], attempts + 1, token, (String)
                        rows.getFirst()[3]));
    }

    /** Records a delivery, if the claim still holds. */
    boolean sent(Claim claim, Instant now) {
        return jdbc.update(SENT, utc(now), utc(now), claim.tenantId(), claim.messageId(), claim.leaseToken()) == 1;
    }

    boolean cancelled(Claim claim, Instant at) {
        return jdbc.update(
                        "UPDATE outbox_message SET"
                                + " status='cancelled',lease_until=NULL,lease_token=NULL,updated_at=?"
                                + " WHERE tenant_id=? AND id=? AND status='sending' AND lease_token=?",
                        utc(at),
                        claim.tenantId(),
                        claim.messageId(),
                        claim.leaseToken())
                == 1;
    }

    /** Records a failed attempt to be tried again at that time, if the claim still holds. */
    boolean retry(Claim claim, Instant nextAttemptAt, String error, Instant now) {
        return jdbc.update(
                        RETRY,
                        utc(nextAttemptAt),
                        shorten(error),
                        utc(now),
                        claim.tenantId(),
                        claim.messageId(),
                        claim.leaseToken())
                == 1;
    }

    /** Records a failed last attempt, if the claim still holds. */
    boolean dead(Claim claim, String error, Instant now) {
        return jdbc.update(DEAD, shorten(error), utc(now), claim.tenantId(), claim.messageId(), claim.leaseToken())
                == 1;
    }

    /** Removes the salon's messages delivered before that instant; with that salon set. */
    int removeSentBefore(UUID tenantId, Instant before) {
        return jdbc.update(REMOVE_SENT, tenantId, utc(before));
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static String shorten(String error) {
        String text = error == null || error.isBlank() ? "The provider failed without saying why" : error.strip();
        return text.length() <= LAST_ERROR_LENGTH ? text : text.substring(0, LAST_ERROR_LENGTH);
    }
}
