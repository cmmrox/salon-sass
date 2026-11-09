package com.salonplatform.staff.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.TenantId;

/**
 * A change to a person's hours for one local date (F06-S02 AC-7): different hours, or null times for not working. It is
 * added or removed, never changed. The note is for a caller with {@code team.manage} only, and is never audited or logged.
 */
@Entity
@Table(name = "staff_member_date_change")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberDateChange {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @Column(name = "member_id", nullable = false, updatable = false)
    private UUID memberId;

    @Column(name = "change_date", nullable = false, updatable = false)
    private LocalDate changeDate;

    @Column(name = "starts", updatable = false)
    private LocalTime starts;

    @Column(name = "finishes", updatable = false)
    private LocalTime finishes;

    @Column(name = "note", updatable = false)
    private String note;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, updatable = false)
    private Instant updatedAt;

    /** A new change of the current salon's branch, which Hibernate stamps. */
    public static MemberDateChange of(
            UUID branchId,
            UUID memberId,
            LocalDate date,
            LocalTime starts,
            LocalTime finishes,
            String note,
            UUID by,
            Instant at) {
        MemberDateChange change = new MemberDateChange();
        change.id = UUID.randomUUID();
        change.branchId = branchId;
        change.memberId = memberId;
        change.changeDate = date;
        change.starts = starts;
        change.finishes = finishes;
        change.note = note;
        change.createdBy = by;
        change.createdAt = at;
        change.updatedAt = at;
        return change;
    }
}
