package com.salonplatform.shared.audit;

import com.salonplatform.shared.observability.Traces;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.shared.time.SalonTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one way to write to the audit trail (NFR-10). An entry joins the caller's transaction, so it
 * exists exactly when the change it records was committed; called outside a writable transaction,
 * it refuses. The salon is the current one, the time is the application's clock, and the trace is
 * the current trace. Values under a key that names a secret are never stored ({@link SecretValues}).
 */
@Service
public class AuditTrail {

    private static final TypeReference<Map<String, Object>> VALUES = new TypeReference<>() {};

    private final AuditEntryRepository entries;

    private final SalonTime time;

    private final Traces traces;

    private final JsonMapper json;

    AuditTrail(AuditEntryRepository entries, SalonTime time, Traces traces, JsonMapper json) {
        this.entries = entries;
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
        TenantContext.resolved()
                .orElseThrow(() -> new IllegalStateException(
                        "An audit entry belongs to a salon: record it inside work for one (TenantContext)"));
        UUID id = UUID.randomUUID();
        entries.persist(new AuditEntry(
                id,
                action,
                toJson(action.before()),
                toJson(action.after()),
                traces.currentTraceId().orElse(null),
                at));
        return id;
    }

    /** The current salon's entries about one subject, oldest first. Another salon's are never seen. */
    public List<AuditRecord> about(String subjectKind, String subjectId) {
        return entries.findBySubjectKindAndSubjectIdOrderByCreatedAtAscIdAsc(subjectKind, subjectId).stream()
                .map(this::toRecord)
                .toList();
    }

    public record AuditPage(List<AuditRecord> items, long total) {}

    /**
     * Identity's protected audit facade: the salon's identity entries and its support-session entries
     * (F03-S04). Tenant filtering is still applied by Hibernate and RLS.
     */
    public AuditPage identityPage(int page, int size, String subjectId) {
        PageRequest request = PageRequest.of(page, size);
        Page<AuditEntry> rows = subjectId == null
                ? entries.findIdentityEntries(request)
                : entries.findIdentityEntriesAbout(subjectId, request);
        return new AuditPage(rows.stream().map(this::toRecord).toList(), rows.getTotalElements());
    }

    private AuditRecord toRecord(AuditEntry entry) {
        return new AuditRecord(
                entry.getId(),
                entry.getTenantId(),
                entry.getCreatedAt(),
                entry.getActorKind(),
                entry.getActorId(),
                entry.getAction(),
                entry.getSubjectKind(),
                entry.getSubjectId(),
                entry.getReason(),
                fromJson(entry.getBeforeValues()),
                fromJson(entry.getAfterValues()),
                entry.getTraceId());
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
