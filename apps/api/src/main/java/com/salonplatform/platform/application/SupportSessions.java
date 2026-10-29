package com.salonplatform.platform.application;

import static com.salonplatform.platform.application.SupportSessionRecords.ACTIVE;
import static com.salonplatform.platform.application.SupportSessionRecords.ENDED;

import com.salonplatform.platform.domain.LifecycleTerms;
import com.salonplatform.platform.domain.OperatorPermission;
import com.salonplatform.platform.infrastructure.PlatformAudit;
import com.salonplatform.platform.infrastructure.SupportCrypto;
import com.salonplatform.platform.infrastructure.SupportSessionStore;
import com.salonplatform.platform.infrastructure.SupportSessionStore.NewSession;
import com.salonplatform.platform.infrastructure.SupportSessionStore.Salon;
import com.salonplatform.platform.infrastructure.SupportSessionStore.Session;
import com.salonplatform.shared.persistence.PlatformDatabase;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import com.salonplatform.shared.web.NotFoundException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Audited support sessions from the operator console (PLT-07, NFR-20; docs/architecture/operator-console.md,
 * "Support sessions"). A Support operator starts a session for one salon with a reason and a duration and
 * receives a single-use entry code, which the salon's host exchanges once, within 60 seconds, for a
 * read-only support credential ({@link SupportSessionEntry}). The session ends at its expiry, when its
 * operator ends it, when an Admin revokes it or when the salon's Owner revokes it
 * ({@link SalonSupportSessions}); every end takes effect on the next request.
 *
 * <p>Each change is one platform transaction: the session row, the platform audit and the salon's own
 * audit entry (through {@code audit_record_support}) commit together or not at all. Support sessions
 * and entry codes are platform records.
 */
@Service
public class SupportSessions {

    /** How long an entry code may be exchanged. */
    public static final Duration CODE_LIFE = Duration.ofSeconds(60);

    public static final int DEFAULT_MINUTES = 30;
    public static final int MIN_MINUTES = 5;
    public static final int MAX_MINUTES = 120;

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

    private final PlatformDatabase database;
    private final SupportSessionStore store;
    private final OperatorWork work;
    private final OperatorAccess access;
    private final SupportSessionRecords records;
    private final PlatformAudit audit;
    private final SupportCrypto crypto;
    private final Clock clock;
    private final String linkOrigin;

    public SupportSessions(
            PlatformDatabase database,
            SupportSessionStore store,
            OperatorWork work,
            OperatorAccess access,
            SupportSessionRecords records,
            PlatformAudit audit,
            SupportCrypto crypto,
            Clock clock,
            @Value("${salon.support.link-origin:https://%s}") String linkOrigin) {
        this.database = database;
        this.store = store;
        this.work = work;
        this.access = access;
        this.records = records;
        this.audit = audit;
        this.crypto = crypto;
        this.clock = clock;
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
            Salon salon = store.salonForStart(salonId).orElseThrow(NotFoundException::new);
            if (!"active".equals(salon.status())) {
                throw new ApplicationProblemException(409, ErrorCode.INVALID_STATE);
            }
            Instant now = clock.instant();
            // An earlier session of this operator for this salon that has run out ends now as expired,
            // so it no longer counts as the open one.
            expireAll(store.claimExpired(operator.id(), salonId, now));
            UUID id = UUID.randomUUID();
            Instant expiresAt = now.plus(Duration.ofMinutes(minutes));
            try {
                store.start(new NewSession(id, salonId, operator.id(), reason, minutes), now, expiresAt);
            } catch (DuplicateKeyException ex) {
                throw new ApplicationProblemException(409, ErrorCode.SUPPORT_SESSION_ACTIVE);
            }
            String operatorName = records.operatorName(operator.id());
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
            records.salonAudit(salonId, "support", id.toString(), "support.session_started", id, reason, after, now);
            return code(id, salon.slug(), expiresAt, now);
        });
    }

    /** A fresh entry code for the operator's own open session. */
    public SupportEntry entryCode(UUID sessionId) {
        return work.run(() -> {
            var operator = access.require(OperatorPermission.SUPPORT_START);
            Session row = records.find(sessionId).orElseThrow(NotFoundException::new);
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
            Session row = records.lockRow(sessionId).orElseThrow(NotFoundException::new);
            if (!admin && !row.operatorId().equals(operator.id())) {
                throw new ApplicationProblemException(403, ErrorCode.PERMISSION_DENIED);
            }
            String by = admin ? "admin" : "operator";
            records.finish(
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
            UUID only = all ? null : operator.id();
            List<SupportSessionView> items =
                    store.page(only, salonId, stateFilter, now, paging.size(), paging.offset()).stream()
                            .map(session -> records.view(session, now))
                            .toList();
            long total = store.count(only, salonId, stateFilter, now);
            return new SupportSessionPage(items, paging.page(), paging.size(), total);
        });
    }

    // --- expiry -------------------------------------------------------------------------------------

    /**
     * Records the end of every session whose expiry has passed (the platform job, every minute): its
     * end, the platform audit and the salon's audit entry, each once. Idempotent and safe to run late,
     * twice or concurrently.
     */
    public int expire(Instant now) {
        Integer ended = database.inTransaction(status -> expireAll(store.claimExpired(now)));
        return ended == null ? 0 : ended;
    }

    private int expireAll(List<Session> due) {
        for (Session session : due) {
            records.finish(session, "expiry", null, PlatformAudit.SYSTEM, "support-expiry", "system", "support-expiry");
        }
        return due.size();
    }

    private SupportEntry code(UUID sessionId, String slug, Instant expiresAt, Instant now) {
        String code = crypto.newCode();
        Instant codeExpiresAt = now.plus(CODE_LIFE);
        store.issueEntryCode(sessionId, SupportCrypto.codeHash(code), codeExpiresAt, now);
        return new SupportEntry(
                sessionId,
                expiresAt,
                code,
                String.format(linkOrigin, records.hostname(slug)) + "/workspace/support#" + code,
                codeExpiresAt);
    }
}
