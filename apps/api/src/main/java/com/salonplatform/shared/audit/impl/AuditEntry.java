package com.salonplatform.shared.audit.impl;

import com.salonplatform.shared.audit.AuditAction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

/**
 * One row of the audit trail. Written once and never changed: Hibernate treats it as immutable, the
 * application's role may only insert and read it, and the database refuses an update from anyone.
 */
@Entity
@Immutable
@Table(name = "audit_entry")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class AuditEntry {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "actor_kind", nullable = false)
    private String actorKind;

    @Column(name = "actor_id", nullable = false)
    private String actorId;

    @Column(name = "action", nullable = false)
    private String action;

    @Column(name = "subject_kind", nullable = false)
    private String subjectKind;

    @Column(name = "subject_id", nullable = false)
    private String subjectId;

    @Column(name = "reason")
    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_values")
    private String beforeValues;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_values")
    private String afterValues;

    @Column(name = "trace_id")
    private String traceId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * @param beforeValues the values before the change, as JSON with secret values already redacted
     * @param afterValues the values after the change, likewise
     */
    AuditEntry(UUID id, AuditAction action, String beforeValues, String afterValues, String traceId, Instant at) {
        this.id = id;
        this.actorKind = action.actor().kind().value();
        this.actorId = action.actor().id();
        this.action = action.action();
        this.subjectKind = action.subjectKind();
        this.subjectId = action.subjectId();
        this.reason = action.reason();
        this.beforeValues = beforeValues;
        this.afterValues = afterValues;
        this.traceId = traceId;
        this.createdAt = at;
        this.updatedAt = at;
    }
}
