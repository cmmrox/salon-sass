package com.salonplatform.platform.infrastructure;

import static com.salonplatform.platform.infrastructure.PlatformTimes.instant;
import static com.salonplatform.platform.infrastructure.PlatformTimes.time;

import com.salonplatform.shared.infrastructure.PlatformDatabase;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Operator console sessions and their spent refresh credentials ({@code operator_session},
 * {@code operator_refresh_history}), on the platform connection: named direct-SQL exception 1 of
 * docs/engineering/backend.md, platform tables with no {@code tenant_id}. Every statement joins the
 * platform transaction the calling use case opened.
 */
@Component
public class OperatorSessionStore {

    /** A session as authorization and renewal read it. */
    public record Session(
            UUID id, UUID userId, boolean mfaVerified, long generation, Instant expiresAt, Instant revokedAt) {}

    private static final String SESSION =
            "SELECT id, user_id, mfa_verified, generation, expires_at, revoked_at FROM operator_session";

    private final PlatformDatabase database;

    OperatorSessionStore(PlatformDatabase database) {
        this.database = database;
    }

    public void create(UUID id, UUID operator, String refreshHash, String label, Instant expires, Instant now) {
        database.jdbc()
                .sql("""
                        INSERT INTO operator_session (id, user_id, refresh_hash, device_label, mfa_verified, generation,
                            expires_at, created_at, updated_at)
                        VALUES (?, ?, ?, ?, true, 1, ?, ?, ?)
                        """)
                .params(id, operator, refreshHash, label, time(expires), time(now), time(now))
                .update();
    }

    public Optional<Session> findById(UUID id) {
        return database.jdbc().sql(SESSION + " WHERE id = ?").param(id).query((row, n) -> session(row)).list().stream()
                .findFirst();
    }

    /** The session holding this refresh credential, locked until the renewal's transaction ends. */
    public Optional<Session> lockByRefreshHash(String refreshHash) {
        return database
                .jdbc()
                .sql(SESSION + " WHERE refresh_hash = ? FOR UPDATE")
                .param(refreshHash)
                .query((row, n) -> session(row))
                .list()
                .stream()
                .findFirst();
    }

    /** The sessions that once held this (now spent) refresh credential. */
    public List<UUID> sessionsThatSpent(String refreshHash) {
        return database.jdbc()
                .sql("SELECT session_id FROM operator_refresh_history WHERE credential_hash = ?")
                .param(refreshHash)
                .query(UUID.class)
                .list();
    }

    /** Replaces the session's refresh credential, keeping the spent one so a replay can be recognised. */
    public void rotate(UUID session, String spentHash, String nextHash, Instant now) {
        database.jdbc()
                .sql("""
                        INSERT INTO operator_refresh_history (id, session_id, credential_hash, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?)
                        """)
                .params(UUID.randomUUID(), session, spentHash, time(now), time(now))
                .update();
        database.jdbc().sql("""
                        UPDATE operator_session SET refresh_hash = ?, generation = generation + 1, updated_at = ? WHERE id = ?
                        """).params(nextHash, time(now), session).update();
    }

    /** @return whether a live session was revoked by this call */
    public boolean revoke(UUID session, Instant now) {
        return database.jdbc()
                        .sql(
                                "UPDATE operator_session SET revoked_at = ?, updated_at = ? WHERE id = ? AND revoked_at IS NULL")
                        .params(time(now), time(now), session)
                        .update()
                > 0;
    }

    public void revokeAllOf(UUID operator, Instant now) {
        database.jdbc()
                .sql(
                        "UPDATE operator_session SET revoked_at = ?, updated_at = ? WHERE user_id = ? AND revoked_at IS NULL")
                .params(time(now), time(now), operator)
                .update();
    }

    /** Local fixtures only: revokes every live session of a fixture operator. */
    public void revokeFixtureSessions(Instant now) {
        database.jdbc().sql("""
                        UPDATE operator_session SET revoked_at = ?, updated_at = ?
                         WHERE revoked_at IS NULL AND user_id IN (SELECT id FROM operator_user WHERE fixture)
                        """).params(time(now), time(now)).update();
    }

    private static Session session(ResultSet row) throws SQLException {
        return new Session(
                row.getObject("id", UUID.class),
                row.getObject("user_id", UUID.class),
                row.getBoolean("mfa_verified"),
                row.getLong("generation"),
                instant(row.getObject("expires_at")),
                instant(row.getObject("revoked_at")));
    }
}
