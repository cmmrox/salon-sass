package com.salonplatform.shared.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * An entry of the audit trail, as it was stored.
 *
 * @param id the entry's id
 * @param tenantId the salon it belongs to
 * @param occurredAt when the action happened, by the application's clock
 * @param actorKind the kind of actor, in lower case
 * @param actorId the actor's id
 * @param action what was done
 * @param subjectKind the kind of thing it was done to
 * @param subjectId that thing's id
 * @param reason why, or null
 * @param before the values before, secrets removed, or null
 * @param after the values after, secrets removed, or null
 * @param traceId the trace of the work that wrote it, or null
 */
public record AuditRecord(UUID id, UUID tenantId, Instant occurredAt, String actorKind, String actorId, String action,
        String subjectKind, String subjectId, String reason, Map<String, Object> before, Map<String, Object> after,
        String traceId) {
}
