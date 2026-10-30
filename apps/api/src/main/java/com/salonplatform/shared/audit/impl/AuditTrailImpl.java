package com.salonplatform.shared.audit.impl;

import com.salonplatform.shared.audit.AuditAction;
import com.salonplatform.shared.audit.AuditRecord;
import com.salonplatform.shared.audit.AuditTrail;
import com.salonplatform.shared.audit.SecretValues;
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

@Service
class AuditTrailImpl implements AuditTrail {

    private static final TypeReference<Map<String, Object>> VALUES = new TypeReference<>() {};

    private final AuditEntryRepository entries;

    private final SalonTime time;

    private final Traces traces;

    private final JsonMapper json;

    AuditTrailImpl(AuditEntryRepository entries, SalonTime time, Traces traces, JsonMapper json) {
        this.entries = entries;
        this.time = time;
        this.traces = traces;
        this.json = json;
    }

    @Override
    public UUID record(AuditAction action) {
        return record(action, time.now());
    }

    @Override
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

    @Override
    public List<AuditRecord> about(String subjectKind, String subjectId) {
        return entries.findBySubjectKindAndSubjectIdOrderByCreatedAtAscIdAsc(subjectKind, subjectId).stream()
                .map(this::toRecord)
                .toList();
    }

    @Override
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
