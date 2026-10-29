package com.salonplatform.platform.application;

import com.salonplatform.identity.IdentityFacade;
import com.salonplatform.platform.domain.LifecycleTerms;
import com.salonplatform.platform.domain.OperatorPermission;
import com.salonplatform.platform.infrastructure.PlatformAudit;
import com.salonplatform.platform.infrastructure.SupportCrypto;
import com.salonplatform.shared.infrastructure.PlatformDatabase;
import com.salonplatform.shared.observability.Traces;
import com.salonplatform.shared.tenancy.RequestSupport;
import com.salonplatform.shared.tenancy.SupportCredentials;
import com.salonplatform.shared.tenancy.SupportGrant;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import com.salonplatform.shared.web.NotFoundException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Audited support sessions (PLT-07, NFR-20; docs/architecture/operator-console.md, "Support
 * sessions"). A Support operator starts a session for one salon with a reason and a duration and
 * receives a single-use entry code; the salon's host exchanges that code once, within 60 seconds, for
 * a read-only support credential. The session ends at its expiry, when its operator ends it, when an
 * Admin revokes it or when the salon's Owner revokes it; every end takes effect on the next request.
 *
 * <p>Each change is one platform transaction: the session row, the platform audit and the salon's own
 * audit entry (through {@code audit_record_support}) commit together or not at all. Support sessions
 * and entry codes are platform records; the salon's id is taken from the host for its own endpoints.
 */
@Service
public class SupportSessions implements SupportCredentials {

    /** How long an entry code may be exchanged. */
    public static final Duration CODE_LIFE = Duration.ofSeconds(60);

    public static final int DEFAULT_MINUTES = 30;
    public static final int MIN_MINUTES = 5;
    public static final int MAX_MINUTES = 120;

    static final String ACTIVE = "active";
    static final String ENDED = "ended";

    public record StartInput(String reason, Integer durationMinutes) {}

    @Schema(requiredProperties = {"id", "expiresAt", "entryCode", "entryUrl", "codeExpiresAt"})
    public record SupportEntry(
            UUID id,
            Instant expiresAt,

            @Schema(description = "Single-use; exchanged once on the salon's host within 60 seconds")
            String entryCode,

            @Schema(description = "The salon's workspace support entry page with the code in its fragment")
            String entryUrl,

            Instant codeExpiresAt) {}

    @Schema(requiredProperties = {"id", "slug", "name", "hostname"})
    public record SupportSalonRef(
            UUID id,
            String slug,

            @Schema(
                    types = {"string", "null"},
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String name,

            String hostname) {}

    @Schema(requiredProperties = {"id", "displayName"})
    public record SupportOperatorRef(UUID id, String displayName) {}

    @Schema(
            requiredProperties = {
                "id",
                "salon",
                "operator",
                "reason",
                "startedAt",
                "expiresAt",
                "endedAt",
                "endedBy",
                "state"
            })
    public record SupportSessionView(
            UUID id,
            SupportSalonRef salon,
            SupportOperatorRef operator,
            String reason,
            Instant startedAt,
            Instant expiresAt,

            @Schema(
                    types = {"string", "null"},
                    format = "date-time",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            Instant endedAt,

            @Schema(
                    types = {"string", "null"},
                    allowableValues = {"operator", "admin", "owner", "expiry"},
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String endedBy,

            @Schema(allowableValues = {"active", "ended"}) String state) {}

    @Schema(requiredProperties = {"items", "page", "size", "total"})
    public record SupportSessionPage(List<SupportSessionView> items, int page, int size, long total) {}

    @Schema(
            requiredProperties = {
                "id",
                "operatorName",
                "reason",
                "startedAt",
                "expiresAt",
                "endedAt",
                "endedBy",
                "state"
            })
    public record SalonSupportSession(
            UUID id,
            String operatorName,
            String reason,
            Instant startedAt,
            Instant expiresAt,

            @Schema(
                    types = {"string", "null"},
                    format = "date-time",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            Instant endedAt,

            @Schema(
                    types = {"string", "null"},
                    allowableValues = {"operator", "admin", "owner", "expiry"},
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String endedBy,

            @Schema(allowableValues = {"active", "ended"}) String state) {}

    @Schema(requiredProperties = {"items", "page", "size", "total"})
    public record SalonSupportSessionPage(List<SalonSupportSession> items, int page, int size, long total) {}

    @Schema(requiredProperties = {"accessToken", "sessionId", "issuedAt", "expiresAt"})
    public record SupportCredential(String accessToken, UUID sessionId, Instant issuedAt, Instant expiresAt) {}

    /** One stored session row. */
    private record Row(
            UUID id,
            UUID salonId,
            String slug,
            String salonName,
            UUID operatorId,
            String operatorName,
            String reason,
            Instant startedAt,
            Instant expiresAt,
            Instant endedAt,
            String endedBy) {

        boolean open(Instant now) {
            return endedAt == null && now.isBefore(expiresAt);
        }
    }

    private final PlatformDatabase database;
    private final OperatorWork work;
    private final OperatorAccess access;
    private final PlatformAudit audit;
    private final SupportCrypto crypto;
    private final IdentityFacade identity;
    private final Traces traces;
    private final JsonMapper json;
    private final Clock clock;
    private final String platformDomain;
    private final String linkOrigin;

    public SupportSessions(
            PlatformDatabase database,
            OperatorWork work,
            OperatorAccess access,
            PlatformAudit audit,
            SupportCrypto crypto,
            IdentityFacade identity,
            Traces traces,
            JsonMapper json,
            Clock clock,
            @Value("${salon.platform-domain}") String platformDomain,
            @Value("${salon.support.link-origin:https://%s}") String linkOrigin) {
        this.database = database;
        this.work = work;
        this.access = access;
        this.audit = audit;
        this.crypto = crypto;
        this.identity = identity;
        this.traces = traces;
        this.json = json;
        this.clock = clock;
        this.platformDomain = platformDomain;
        this.linkOrigin = linkOrigin;
    }

    // --- operator ---------------------------------------------------------------------------------

    /**
     * Starts a session for an active (or past-due, or read-only) salon; a suspended or closed salon is
     * 409 {@code invalid_state}; a second open session of the same operator for the same salon is 409
     * {@code support_session_active}.
     */
    public SupportEntry start(UUID salonId, StartInput input) {
        List<FieldProblem> errors = new ArrayList<>();
        String reason = LifecycleTerms.reason(input.reason());
        if (reason == null) {
            errors.add(new FieldProblem(
                    "reason",
                    "must be plain text of " + LifecycleTerms.REASON_MIN + " to " + LifecycleTerms.REASON_MAX
                            + " characters"));
        }
        int minutes = input.durationMinutes() == null ? DEFAULT_MINUTES : input.durationMinutes();
        if (minutes < MIN_MINUTES || minutes > MAX_MINUTES) {
            errors.add(new FieldProblem(
                    "durationMinutes", "must be a whole number of minutes from " + MIN_MINUTES + " to " + MAX_MINUTES));
        }
        return work.run(() -> {
            var operator = access.require(OperatorPermission.SUPPORT_START);
            if (!errors.isEmpty()) {
                errors.sort(java.util.Comparator.comparing(FieldProblem::field));
                throw new ApplicationProblemException(400, ErrorCode.BAD_REQUEST, errors);
            }
            // The registry row is shared-locked: a suspension or closure waits for this start, or this
            // start sees its result.
            var salon = database
                    .jdbc()
                    .query(
                            "SELECT slug, status FROM tenant WHERE id = ? FOR SHARE",
                            (row, n) -> new String[] {row.getString("slug"), row.getString("status")},
                            salonId)
                    .stream()
                    .findFirst()
                    .orElseThrow(NotFoundException::new);
            if (!"active".equals(salon[1])) {
                throw new ApplicationProblemException(409, ErrorCode.INVALID_STATE);
            }
            Instant now = clock.instant();
            // An earlier session of this operator for this salon that has run out ends now as expired,
            // so it no longer counts as the open one.
            expireDue(now, "operator_id = ? AND salon_id = ?", operator.id(), salonId);
            UUID id = UUID.randomUUID();
            Instant expiresAt = now.plus(Duration.ofMinutes(minutes));
            try {
                database.jdbc()
                        .update(
                                """
                        INSERT INTO support_session (id, salon_id, operator_id, reason, duration_minutes, started_at,
                            expires_at, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                                id,
                                salonId,
                                operator.id(),
                                reason,
                                minutes,
                                Timestamp.from(now),
                                Timestamp.from(expiresAt),
                                Timestamp.from(now),
                                Timestamp.from(now));
            } catch (DuplicateKeyException ex) {
                throw new ApplicationProblemException(409, ErrorCode.SUPPORT_SESSION_ACTIVE);
            }
            String operatorName = operatorName(operator.id());
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("salonId", salonId.toString());
            details.put("reason", reason);
            details.put("durationMinutes", minutes);
            details.put("expiresAt", expiresAt.toString());
            audit.record(
                    PlatformAudit.OPERATOR,
                    operator.id().toString(),
                    "support.session_started",
                    "support_session",
                    id.toString(),
                    details,
                    now);
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("operatorName", operatorName);
            after.put("reason", reason);
            after.put("expiresAt", expiresAt.toString());
            salonAudit(salonId, "support", id.toString(), "support.session_started", id, reason, after, now);
            return code(id, salon[0], expiresAt, now);
        });
    }

    /** A fresh entry code for the operator's own open session. */
    public SupportEntry entryCode(UUID sessionId) {
        return work.run(() -> {
            var operator = access.require(OperatorPermission.SUPPORT_START);
            Row row = find(sessionId).orElseThrow(NotFoundException::new);
            if (!row.operatorId().equals(operator.id())) {
                throw new ApplicationProblemException(403, ErrorCode.PERMISSION_DENIED);
            }
            Instant now = clock.instant();
            if (!row.open(now)) {
                throw new ApplicationProblemException(409, ErrorCode.INVALID_STATE);
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("salonId", row.salonId().toString());
            audit.record(
                    PlatformAudit.OPERATOR,
                    operator.id().toString(),
                    "support.entry_code_issued",
                    "support_session",
                    row.id().toString(),
                    details,
                    now);
            return code(row.id(), row.slug(), row.expiresAt(), now);
        });
    }

    /**
     * Ends a session from the console: Support its own ({@code operator}), Admin any ({@code admin}).
     * Another operator's session is 403 {@code permission_denied} for Support; an ended one is 409
     * {@code invalid_state}.
     */
    public void end(UUID sessionId) {
        work.run(() -> {
            var operator = access.authenticated();
            boolean admin = operator.role().permissions().contains(OperatorPermission.SUPPORT_REVOKE);
            if (!admin && !operator.role().permissions().contains(OperatorPermission.SUPPORT_START)) {
                throw new ApplicationProblemException(403, ErrorCode.PERMISSION_DENIED);
            }
            Row row = lockRow(sessionId).orElseThrow(NotFoundException::new);
            if (!admin && !row.operatorId().equals(operator.id())) {
                throw new ApplicationProblemException(403, ErrorCode.PERMISSION_DENIED);
            }
            String by = admin ? "admin" : "operator";
            finish(
                    row,
                    by,
                    operator.id().toString(),
                    PlatformAudit.OPERATOR,
                    operator.id().toString(),
                    admin ? "operator" : "support",
                    admin ? operator.id().toString() : row.id().toString());
            return null;
        });
    }

    /** Admin: every session; Support: its own. Filtered by salon and state. */
    public SupportSessionPage list(Paging paging, String salonFilter, String stateFilter) {
        List<FieldProblem> errors = new ArrayList<>();
        UUID salon = null;
        if (salonFilter != null) {
            try {
                if (salonFilter.length() != 36) {
                    throw new IllegalArgumentException();
                }
                salon = UUID.fromString(salonFilter);
            } catch (IllegalArgumentException ex) {
                errors.add(new FieldProblem("salonId", "must be a UUID"));
            }
        }
        if (stateFilter != null && !ACTIVE.equals(stateFilter) && !ENDED.equals(stateFilter)) {
            errors.add(new FieldProblem("state", "must be active or ended"));
        }
        UUID salonId = salon;
        return work.run(() -> {
            var operator = access.authenticated();
            boolean all = operator.role().permissions().contains(OperatorPermission.SUPPORT_READ);
            if (!all && !operator.role().permissions().contains(OperatorPermission.SUPPORT_START)) {
                throw new ApplicationProblemException(403, ErrorCode.PERMISSION_DENIED);
            }
            if (!errors.isEmpty()) {
                throw new ApplicationProblemException(400, ErrorCode.BAD_REQUEST, errors);
            }
            Instant now = clock.instant();
            Timestamp at = Timestamp.from(now);
            String where =
                    " WHERE (?::uuid IS NULL OR s.operator_id = ?::uuid) AND (?::uuid IS NULL OR s.salon_id = ?::uuid)"
                            + " AND (?::text IS NULL OR (?::text = 'active') = (s.ended_at IS NULL AND s.expires_at > ?))";
            UUID only = all ? null : operator.id();
            Object[] args = {only, only, salonId, salonId, stateFilter, stateFilter, at};
            List<Object> page = new ArrayList<>(java.util.Arrays.asList(args));
            page.add(paging.size());
            page.add(paging.offset());
            List<SupportSessionView> items = database.jdbc()
                    .query(
                            SELECT + where + " ORDER BY s.started_at DESC, s.id LIMIT ? OFFSET ?",
                            (row, n) -> view(row(row), now),
                            page.toArray());
            Long total =
                    database.jdbc().queryForObject("SELECT count(*) FROM support_session s" + where, Long.class, args);
            return new SupportSessionPage(items, paging.page(), paging.size(), total == null ? 0 : total);
        });
    }

    // --- the salon's Owner, on the salon's host -----------------------------------------------------

    /** The salon's own sessions, active and past, for its Owner. */
    public SalonSupportSessionPage salonList(Paging paging) {
        UUID salonId = TenantContext.require().id();
        identity.require("support.manage");
        return database.inTransaction(status -> {
            Instant now = clock.instant();
            List<SalonSupportSession> items = database.jdbc()
                    .query(
                            SELECT + " WHERE s.salon_id = ? ORDER BY s.started_at DESC, s.id LIMIT ? OFFSET ?",
                            (row, n) -> {
                                Row each = row(row);
                                SupportSessionView view = view(each, now);
                                return new SalonSupportSession(
                                        each.id(),
                                        each.operatorName(),
                                        each.reason(),
                                        each.startedAt(),
                                        each.expiresAt(),
                                        view.endedAt(),
                                        view.endedBy(),
                                        view.state());
                            },
                            salonId,
                            paging.size(),
                            paging.offset());
            Long total = database.jdbc()
                    .queryForObject("SELECT count(*) FROM support_session WHERE salon_id = ?", Long.class, salonId);
            return new SalonSupportSessionPage(items, paging.page(), paging.size(), total == null ? 0 : total);
        });
    }

    /** The Owner revokes an active session of the salon; another salon's is not found. */
    public void salonRevoke(UUID sessionId) {
        UUID salonId = TenantContext.require().id();
        IdentityFacade.Principal owner = identity.require("support.manage");
        work.run(() -> {
            Row row = lockRow(sessionId)
                    .filter(each -> each.salonId().equals(salonId))
                    .orElseThrow(NotFoundException::new);
            finish(
                    row,
                    "owner",
                    owner.id().toString(),
                    "staff",
                    owner.id().toString(),
                    "staff",
                    owner.id().toString());
            return null;
        });
    }

    // --- the support session itself, on the salon's host --------------------------------------------

    /**
     * Exchanges an entry code for the support credential: once, within its 60 seconds, on the host of
     * the salon it was issued for, while its session is open. Anything else is 401 {@code invalid_code}
     * and issues nothing.
     */
    public SupportCredential enter(String code) {
        UUID salonId = TenantContext.require().id();
        if (!SupportCrypto.codeShaped(code)) {
            throw new ApplicationProblemException(401, ErrorCode.INVALID_CODE);
        }
        SupportCredential credential = database.inTransaction(status -> {
            Instant now = clock.instant();
            Timestamp at = Timestamp.from(now);
            // Consumed in one statement: of simultaneous exchanges exactly one updates the row.
            List<Object[]> consumed = database.jdbc()
                    .query(
                            """
                    UPDATE support_entry_code c SET consumed_at = ?, updated_at = ?
                      FROM support_session s
                     WHERE c.code_hash = ? AND c.consumed_at IS NULL AND c.expires_at > ?
                       AND s.id = c.session_id AND s.salon_id = ? AND s.ended_at IS NULL AND s.expires_at > ?
                    RETURNING s.id, s.operator_id, s.expires_at
                    """,
                            (row, n) -> new Object[] {
                                row.getObject(1, UUID.class),
                                row.getObject(2, UUID.class),
                                row.getTimestamp(3).toInstant()
                            },
                            at,
                            at,
                            SupportCrypto.codeHash(code),
                            at,
                            salonId,
                            at);
            if (consumed.isEmpty()) {
                return null;
            }
            UUID sessionId = (UUID) consumed.get(0)[0];
            UUID operatorId = (UUID) consumed.get(0)[1];
            Instant expiresAt = (Instant) consumed.get(0)[2];
            if (!operatorActive(operatorId)) {
                status.setRollbackOnly();
                return null;
            }
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("expiresAt", expiresAt.toString());
            salonAudit(
                    salonId, "support", sessionId.toString(), "support.session_entered", sessionId, null, after, now);
            Instant issued = now.truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            return new SupportCredential(
                    crypto.credential(salonId, sessionId, operatorId, issued, expiresAt), sessionId, issued, expiresAt);
        });
        if (credential == null) {
            throw new ApplicationProblemException(401, ErrorCode.INVALID_CODE);
        }
        return credential;
    }

    /** The support session ends itself (the operator's End session in the workspace). */
    public void endCurrent() {
        SupportGrant grant = RequestSupport.current()
                .orElseThrow(() -> new ApplicationProblemException(401, ErrorCode.INVALID_SESSION));
        work.run(() -> {
            Row row = lockRow(grant.sessionId())
                    .filter(each -> each.salonId().equals(grant.salonId()))
                    .orElseThrow(() -> new ApplicationProblemException(401, ErrorCode.INVALID_SESSION));
            if (!row.open(clock.instant())) {
                throw new ApplicationProblemException(401, ErrorCode.INVALID_SESSION);
            }
            finish(
                    row,
                    "operator",
                    grant.operatorId().toString(),
                    PlatformAudit.OPERATOR,
                    grant.operatorId().toString(),
                    "support",
                    row.id().toString());
            return null;
        });
    }

    // --- credential verification for the request filter ----------------------------------------------

    @Override
    public Optional<SupportGrant> verify(String credential, Supplier<UUID> salon) {
        Optional<SupportCrypto.Claims> claims = crypto.claims(credential);
        if (claims.isEmpty()) {
            return Optional.empty();
        }
        UUID salonId = salon.get();
        SupportCrypto.Claims verified = claims.get();
        if (!verified.salonId().equals(salonId)) {
            throw new NotFoundException();
        }
        if (!crypto.current(verified)) {
            throw new ApplicationProblemException(401, ErrorCode.INVALID_SESSION);
        }
        // The stored session decides: its exact expiry, and whether it is still open and its operator active.
        Row live = database.inTransaction(status -> {
            Instant now = clock.instant();
            return find(verified.sessionId())
                    .filter(row -> row.salonId().equals(salonId)
                            && row.operatorId().equals(verified.operatorId())
                            && row.open(now)
                            && operatorActive(row.operatorId()))
                    .orElse(null);
        });
        if (live == null) {
            throw new ApplicationProblemException(401, ErrorCode.INVALID_SESSION);
        }
        return Optional.of(new SupportGrant(verified.sessionId(), salonId, verified.operatorId(), live.expiresAt()));
    }

    // --- expiry -------------------------------------------------------------------------------------

    /**
     * Records the end of every session whose expiry has passed (the platform job, every minute): its
     * end, the platform audit and the salon's audit entry, each once. Idempotent and safe to run late,
     * twice or concurrently.
     */
    public int expire(Instant now) {
        Integer ended = database.inTransaction(status -> expireDue(now, "true"));
        return ended == null ? 0 : ended;
    }

    private int expireDue(Instant now, String condition, Object... args) {
        List<Object> params = new ArrayList<>(List.of(args));
        params.add(Timestamp.from(now));
        List<Row> due = database.jdbc()
                .query(
                        SELECT + " WHERE " + condition
                                + " AND s.ended_at IS NULL AND s.expires_at <= ? ORDER BY s.expires_at, s.id FOR UPDATE OF s SKIP LOCKED",
                        (row, n) -> row(row),
                        params.toArray());
        for (Row row : due) {
            finish(row, "expiry", null, PlatformAudit.SYSTEM, "support-expiry", "system", "support-expiry");
        }
        return due.size();
    }

    // --- shared ---------------------------------------------------------------------------------------

    private static final String SELECT = """
            SELECT s.id, s.salon_id, t.slug, t.name AS salon_name, s.operator_id, o.display_name, s.reason,
                   s.started_at, s.expires_at, s.ended_at, s.ended_by
              FROM support_session s JOIN tenant t ON t.id = s.salon_id JOIN operator_user o ON o.id = s.operator_id
            """;

    private static Row row(ResultSet row) throws SQLException {
        Timestamp ended = row.getTimestamp("ended_at");
        return new Row(
                row.getObject("id", UUID.class),
                row.getObject("salon_id", UUID.class),
                row.getString("slug"),
                row.getString("salon_name"),
                row.getObject("operator_id", UUID.class),
                row.getString("display_name"),
                row.getString("reason"),
                row.getTimestamp("started_at").toInstant(),
                row.getTimestamp("expires_at").toInstant(),
                ended == null ? null : ended.toInstant(),
                row.getString("ended_by"));
    }

    private SupportSessionView view(Row row, Instant now) {
        boolean open = row.open(now);
        Instant endedAt = row.endedAt() != null ? row.endedAt() : open ? null : row.expiresAt();
        String endedBy = row.endedBy() != null ? row.endedBy() : open ? null : "expiry";
        return new SupportSessionView(
                row.id(),
                new SupportSalonRef(row.salonId(), row.slug(), row.salonName(), hostname(row.slug())),
                new SupportOperatorRef(row.operatorId(), row.operatorName()),
                row.reason(),
                row.startedAt(),
                row.expiresAt(),
                endedAt,
                endedBy,
                open ? ACTIVE : ENDED);
    }

    private Optional<Row> find(UUID id) {
        return database.jdbc().query(SELECT + " WHERE s.id = ?", (row, n) -> row(row), id).stream()
                .findFirst();
    }

    private Optional<Row> lockRow(UUID id) {
        return database.jdbc().query(SELECT + " WHERE s.id = ? FOR UPDATE OF s", (row, n) -> row(row), id).stream()
                .findFirst();
    }

    /**
     * Ends an open session now (or, for expiry, at its expiry instant): its row, the platform audit and
     * the salon's audit entry. An ended session is 409 {@code invalid_state}: it is never reopened.
     */
    private void finish(
            Row row,
            String by,
            String byId,
            String platformActorKind,
            String platformActorId,
            String salonActorKind,
            String salonActorId) {
        Instant now = clock.instant();
        boolean expiry = "expiry".equals(by);
        if (row.endedAt() != null || (!expiry && !row.open(now))) {
            throw new ApplicationProblemException(409, ErrorCode.INVALID_STATE);
        }
        Instant at = expiry ? row.expiresAt() : now;
        database.jdbc().update("""
                UPDATE support_session SET ended_at = ?, ended_by = ?, ended_by_id = ?, updated_at = ? WHERE id = ?
                """, Timestamp.from(at), by, byId, Timestamp.from(now), row.id());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("salonId", row.salonId().toString());
        details.put("endedBy", by);
        String action = switch (by) {
            case "admin", "owner" -> "support.session_revoked";
            case "expiry" -> "support.session_expired";
            default -> "support.session_ended";
        };
        audit.record(
                platformActorKind,
                platformActorId,
                action,
                "support_session",
                row.id().toString(),
                details,
                now);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("endedBy", by);
        salonAudit(row.salonId(), salonActorKind, salonActorId, "support.session_ended", row.id(), null, after, at);
    }

    private void salonAudit(
            UUID salonId,
            String actorKind,
            String actorId,
            String action,
            UUID sessionId,
            String reason,
            Map<String, Object> after,
            Instant at) {
        database.jdbc()
                .queryForObject(
                        "SELECT audit_record_support(?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)",
                        Boolean.class,
                        salonId,
                        UUID.randomUUID(),
                        actorKind,
                        actorId,
                        action,
                        sessionId.toString(),
                        reason,
                        json.writeValueAsString(after),
                        traces.currentTraceId().orElse(null),
                        Timestamp.from(at));
    }

    private String operatorName(UUID operatorId) {
        return database.jdbc()
                .queryForObject("SELECT display_name FROM operator_user WHERE id = ?", String.class, operatorId);
    }

    private boolean operatorActive(UUID operatorId) {
        return Boolean.TRUE.equals(database
                .jdbc()
                .query("SELECT active FROM operator_user WHERE id = ?", (row, n) -> row.getBoolean(1), operatorId)
                .stream()
                .findFirst()
                .orElse(false));
    }

    private SupportEntry code(UUID sessionId, String slug, Instant expiresAt, Instant now) {
        String code = crypto.newCode();
        Instant codeExpiresAt = now.plus(CODE_LIFE);
        database.jdbc()
                .update(
                        """
                INSERT INTO support_entry_code (id, session_id, code_hash, expires_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                        UUID.randomUUID(),
                        sessionId,
                        SupportCrypto.codeHash(code),
                        Timestamp.from(codeExpiresAt),
                        Timestamp.from(now),
                        Timestamp.from(now));
        return new SupportEntry(
                sessionId,
                expiresAt,
                code,
                String.format(linkOrigin, hostname(slug)) + "/workspace/support#" + code,
                codeExpiresAt);
    }

    private String hostname(String slug) {
        return slug + "." + platformDomain;
    }
}
