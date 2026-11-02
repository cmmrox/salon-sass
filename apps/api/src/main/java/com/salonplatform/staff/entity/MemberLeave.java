package com.salonplatform.staff.entity;

import com.salonplatform.staff.domain.LeaveType;
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
 * A person's leave, from one local date to another, both whole days (F06-S03 AC-1). It is added or removed, never changed.
 * Its type and note are for a caller with {@code team.manage} only, and neither is audited or logged (decision 104).
 */
@Entity
@Table(name = "staff_member_leave")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberLeave {

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

    @Column(name = "first_day", nullable = false, updatable = false)
    private LocalDate firstDay;

    @Column(name = "last_day", nullable = false, updatable = false)
    private LocalDate lastDay;

    @Column(name = "leave_type", nullable = false, updatable = false)
    private String leaveType;

    @Column(name = "note", updatable = false)
    private String note;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, updatable = false)
    private Instant updatedAt;

    /** New leave of the current salon's branch, which Hibernate stamps. */
    public static MemberLeave of(
            UUID branchId,
            UUID memberId,
            LocalDate firstDay,
            LocalDate lastDay,
            LeaveType type,
            String note,
            UUID by,
            Instant at) {
        MemberLeave leave = new MemberLeave();
        leave.id = UUID.randomUUID();
        leave.branchId = branchId;
        leave.memberId = memberId;
        leave.firstDay = firstDay;
        leave.lastDay = lastDay;
        leave.leaveType = type.value();
        leave.note = note;
        leave.createdBy = by;
        leave.createdAt = at;
        leave.updatedAt = at;
        return leave;
    }

    public LeaveType type() {
        return LeaveType.of(leaveType);
    }
}
