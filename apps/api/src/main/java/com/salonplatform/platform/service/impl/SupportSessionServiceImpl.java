package com.salonplatform.platform.service.impl;

import static com.salonplatform.platform.service.impl.SupportSessionRecords.ACTIVE;
import static com.salonplatform.platform.service.impl.SupportSessionRecords.ENDED;

import com.salonplatform.platform.adapter.SupportCrypto;
import com.salonplatform.platform.domain.LifecycleTerms;
import com.salonplatform.platform.domain.OperatorPermission;
import com.salonplatform.platform.domain.Paging;
import com.salonplatform.platform.dto.SupportEntry;
import com.salonplatform.platform.dto.SupportSessionPage;
import com.salonplatform.platform.dto.SupportSessionView;
import com.salonplatform.platform.repository.PlatformAudit;
import com.salonplatform.platform.repository.SupportSessionStore;
import com.salonplatform.platform.repository.SupportSessionStore.NewSession;
import com.salonplatform.platform.repository.SupportSessionStore.Salon;
import com.salonplatform.platform.repository.SupportSessionStore.Session;
import com.salonplatform.platform.service.SupportSessionService;
import com.salonplatform.shared.persistence.PlatformDatabase;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import com.salonplatform.shared.web.NotFoundException;
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

@Service
class SupportSessionServiceImpl implements SupportSessionService {

    /** How long an entry code may be exchanged. */
    public static final Duration CODE_LIFE = Duration.ofSeconds(60);

    public static final int DEFAULT_MINUTES = 30;
    public static final int MIN_MINUTES = 5;
    public static final int MAX_MINUTES = 120;

    private final PlatformDatabase database;
    private final SupportSessionStore store;
    private final OperatorWork work;
    private final OperatorAccess access;
    private final SupportSessionRecords records;
    private final PlatformAudit audit;
    private final SupportCrypto crypto;
    private final Clock clock;
    private final String linkOrigin;

    SupportSessionServiceImpl(
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

    @Override
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

    @Override
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

    @Override
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

    @Override
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

    @Override
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
