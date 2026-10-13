package com.salonplatform.shared.audit;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.salonplatform.shared.observability.Traces;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.shared.time.SalonTime;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The one way to write to the audit trail (NFR-10). An entry joins the caller's transaction, so it
 * exists exactly when the change it records was committed; called outside a writable transaction,
 * it refuses. The salon is the current one, the time is the application's clock, and the trace is
 * the current trace. Values under a key that names a secret are never stored ({@link SecretValues}).
 */
@Service
public class AuditTrail {

    private static final TypeReference<Map<String, Object>> VALUES = new TypeReference<>() {
    };

    @PersistenceContext
    private EntityManager entityManager;

    private final SalonTime time;

    private final Traces traces;

    private final JsonMapper json;

    AuditTrail(SalonTime time, Traces traces, JsonMapper json) {
        this.time = time;
        this.traces = traces;
        this.json = json;
    }

    /**
     * Records an action of the current salon, in the caller's transaction.
     *
     * @return the entry's id
     * @throws IllegalStateException outside a writable transaction, or with no salon set
     */
    public UUID record(AuditAction action) {
        return record(action, time.now());
    }

    /**
     * As {@link #record(AuditAction)}, at a time the caller already took from the application's
     * clock for the same change, so that the change and its entry carry the same instant.
     */
    public UUID record(AuditAction action, Instant at) {
        requireWritableTransaction();
        TenantContext.resolved().orElseThrow(() -> new IllegalStateException(
                "An audit entry belongs to a salon: record it inside work for one (TenantContext)"));
        UUID id = UUID.randomUUID();
        entityManager.persist(new AuditEntry(id, action.actor(), action.action(), action.subjectKind(),
                action.subjectId(), action.reason(), toJson(action.before()), toJson(action.after()),
                traces.currentTraceId().orElse(null), at));
        return id;
    }

    /** The current salon's entries about one subject, oldest first. Another salon's are never seen. */
    public List<AuditRecord> about(String subjectKind, String subjectId) {
        return entityManager.createQuery("""
                select e from AuditEntry e
                 where e.subjectKind = :kind and e.subjectId = :id
                 order by e.createdAt, e.id
                """, AuditEntry.class)
                .setParameter("kind", subjectKind).setParameter("id", subjectId)
                .getResultStream().map(this::toRecord).toList();
    }

    private AuditRecord toRecord(AuditEntry entry) {
        return new AuditRecord(entry.id(), entry.tenantId(), entry.createdAt(), entry.actorKind(), entry.actorId(),
                entry.action(), entry.subjectKind(), entry.subjectId(), entry.reason(), fromJson(entry.beforeValues()),
                fromJson(entry.afterValues()), entry.traceId());
    }

    private String toJson(Map<String, ?> values) {
        return values == null ? null : json.writeValueAsString(SecretValues.redact(values));
    }

    private Map<String, Object> fromJson(String values) {
        return values == null ? null : json.readValue(values, VALUES);
    }

    private static void requireWritableTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException(
                    "An audit entry is written in the transaction of the change it records; there is none here");
        }
    }
}
