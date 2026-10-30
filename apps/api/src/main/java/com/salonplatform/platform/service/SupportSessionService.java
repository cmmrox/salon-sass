package com.salonplatform.platform.service;

import com.salonplatform.platform.domain.Paging;
import com.salonplatform.platform.dto.SupportEntry;
import com.salonplatform.platform.dto.SupportSessionPage;
import java.time.Instant;
import java.util.UUID;

/**
 * Audited support sessions from the operator console (PLT-07, NFR-20; docs/architecture/operator-console.md,
 * "Support sessions"). A Support operator starts a session for one salon with a reason and a duration and
 * receives a single-use entry code, which the salon's host exchanges once, within 60 seconds, for a
 * read-only support credential ({@link SupportEntryService}). The session ends at its expiry, when its
 * operator ends it, when an Admin revokes it or when the salon's Owner revokes it
 * ({@link SalonSupportSessionService}); every end takes effect on the next request.
 *
 * <p>Each change is one platform transaction: the session row, the platform audit and the salon's own
 * audit entry (through {@code audit_record_support}) commit together or not at all. Support sessions
 * and entry codes are platform records.
 */
public interface SupportSessionService {

    record StartInput(String reason, Integer durationMinutes) {}

    /**
     * Starts a session for an active (or past-due, or read-only) salon; a suspended or closed salon is
     * 409 {@code invalid_state}; a second open session of the same operator for the same salon is 409
     * {@code support_session_active}.
     */
    SupportEntry start(UUID salonId, StartInput input);

    /** A fresh entry code for the operator's own open session. */
    SupportEntry entryCode(UUID sessionId);

    /**
     * Ends a session from the console: Support its own ({@code operator}), Admin any ({@code admin}).
     * Another operator's session is 403 {@code permission_denied} for Support; an ended one is 409
     * {@code invalid_state}.
     */
    void end(UUID sessionId);

    /** Admin: every session; Support: its own. Filtered by salon and state. */
    SupportSessionPage list(Paging paging, String salonFilter, String stateFilter);

    /**
     * Records the end of every session whose expiry has passed (the platform job, every minute): its
     * end, the platform audit and the salon's audit entry, each once. Idempotent and safe to run late,
     * twice or concurrently.
     */
    int expire(Instant now);
}
