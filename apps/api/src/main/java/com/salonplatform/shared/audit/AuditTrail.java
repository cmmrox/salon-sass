package com.salonplatform.shared.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The one way to write to the audit trail (NFR-10). An entry joins the caller's transaction, so it
 * exists exactly when the change it records was committed; called outside a writable transaction,
 * it refuses. The salon is the current one, the time is the application's clock, and the trace is
 * the current trace. Values under a key that names a secret are never stored ({@link SecretValues}).
 */
public interface AuditTrail {

    /**
     * Records an action of the current salon, in the caller's transaction.
     *
     * @return the entry's id
     * @throws IllegalStateException outside a writable transaction, or with no salon set
     */
    UUID record(AuditAction action);

    /**
     * As {@link #record(AuditAction)}, at a time the caller already took from the application's
     * clock for the same change, so that the change and its entry carry the same instant.
     */
    UUID record(AuditAction action, Instant at);

    /** The current salon's entries about one subject, oldest first. Another salon's are never seen. */
    List<AuditRecord> about(String subjectKind, String subjectId);

    /** A page of audit entries, with how many there are in all. */
    record AuditPage(List<AuditRecord> items, long total) {}

    /**
     * Identity's protected audit facade: the salon's identity entries and its support-session entries
     * (F03-S04). Tenant filtering is still applied by Hibernate and RLS.
     */
    AuditPage identityPage(int page, int size, String subjectId);
}
