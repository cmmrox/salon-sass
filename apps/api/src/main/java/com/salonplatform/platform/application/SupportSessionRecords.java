package com.salonplatform.platform.application;

import com.salonplatform.platform.application.SupportSessions.SupportOperatorRef;
import com.salonplatform.platform.application.SupportSessions.SupportSalonRef;
import com.salonplatform.platform.application.SupportSessions.SupportSessionView;
import com.salonplatform.platform.infrastructure.PlatformAudit;
import com.salonplatform.platform.infrastructure.SupportSessionStore;
import com.salonplatform.platform.infrastructure.SupportSessionStore.SalonAuditEntry;
import com.salonplatform.platform.infrastructure.SupportSessionStore.Session;
import com.salonplatform.shared.observability.Traces;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The stored support sessions as every support use case reads and ends them: one session row with its
 * salon and operator, and the one way a session ends, with its platform audit and the salon's own audit
 * entry. Runs inside the caller's platform transaction.
 */
@Component
class SupportSessionRecords {

    static final String ACTIVE = "active";
    static final String ENDED = "ended";

    private final SupportSessionStore store;
    private final PlatformAudit audit;
    private final Traces traces;
    private final JsonMapper json;
    private final Clock clock;
    private final String platformDomain;

    SupportSessionRecords(
            SupportSessionStore store,
            PlatformAudit audit,
            Traces traces,
            JsonMapper json,
            Clock clock,
            @Value("${salon.platform-domain}") String platformDomain) {
        this.store = store;
        this.audit = audit;
        this.traces = traces;
        this.json = json;
        this.clock = clock;
        this.platformDomain = platformDomain;
    }

    SupportSessionView view(Session row, Instant now) {
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

    Optional<Session> find(UUID id) {
        return store.find(id);
    }

    Optional<Session> lockRow(UUID id) {
        return store.lock(id);
    }

    /**
     * Ends an open session now (or, for expiry, at its expiry instant): its row, the platform audit and
     * the salon's audit entry. An ended session is 409 {@code invalid_state}: it is never reopened.
     */
    void finish(
            Session row,
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
        store.end(row.id(), at, by, byId, now);
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

    void salonAudit(
            UUID salonId,
            String actorKind,
            String actorId,
            String action,
            UUID sessionId,
            String reason,
            Map<String, Object> after,
            Instant at) {
        store.recordInSalonAudit(new SalonAuditEntry(
                salonId,
                actorKind,
                actorId,
                action,
                sessionId,
                reason,
                json.writeValueAsString(after),
                traces.currentTraceId().orElse(null),
                at));
    }

    String operatorName(UUID operatorId) {
        return store.operatorName(operatorId);
    }

    boolean operatorActive(UUID operatorId) {
        return store.operatorActive(operatorId);
    }

    String hostname(String slug) {
        return slug + "." + platformDomain;
    }
}
