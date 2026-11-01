package com.salonplatform.staff.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.TenantId;

/**
 * A special closure (F04-S03): the branch is closed the whole of one local date, whatever its weekly hours and
 * holiday choices say. The reason is for the salon's team only, never public, logged or audited. Reopening the
 * day removes the closure.
 */
@Entity
@Table(name = "staff_closure")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Closure {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @Column(name = "closure_date", nullable = false, updatable = false)
    private LocalDate closureDate;

    @Column(name = "reason", updatable = false)
    private String reason;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** A new closure of the current salon's branch, which Hibernate stamps. */
    public static Closure of(UUID branchId, LocalDate date, String reason, UUID by, Instant at) {
        Closure closure = new Closure();
        closure.id = UUID.randomUUID();
        closure.branchId = branchId;
        closure.closureDate = date;
        closure.reason = reason;
        closure.createdBy = by;
        closure.createdAt = at;
        closure.updatedAt = at;
        return closure;
    }
}
