package com.salonplatform.staff.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.TenantId;

/** A break on a date with different hours. It goes with its change. */
@Entity
@Table(name = "staff_member_date_change_break")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberDateChangeBreak {

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

    @Column(name = "change_id", nullable = false, updatable = false)
    private UUID changeId;

    @Column(name = "starts", nullable = false, updatable = false)
    private LocalTime starts;

    @Column(name = "finishes", nullable = false, updatable = false)
    private LocalTime finishes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, updatable = false)
    private Instant updatedAt;

    /** A new row of the current salon's branch, which Hibernate stamps. */
    public static MemberDateChangeBreak of(
            UUID branchId, UUID memberId, UUID changeId, LocalTime starts, LocalTime finishes, Instant at) {
        MemberDateChangeBreak row = new MemberDateChangeBreak();
        row.id = UUID.randomUUID();
        row.branchId = branchId;
        row.memberId = memberId;
        row.changeId = changeId;
        row.starts = starts;
        row.finishes = finishes;
        row.createdAt = at;
        row.updatedAt = at;
        return row;
    }
}
