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
 * Part of one local date when a person cannot be booked, with a label (F06-S03 AC-4). It is added or removed, never changed,
 * and is stored as entered, so it may lie outside the person's hours or on their leave.
 */
@Entity
@Table(name = "staff_member_blocked_time")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberBlockedTime {

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

    @Column(name = "block_date", nullable = false, updatable = false)
    private LocalDate blockDate;

    @Column(name = "starts", nullable = false, updatable = false)
    private LocalTime starts;

    @Column(name = "finishes", nullable = false, updatable = false)
    private LocalTime finishes;

    @Column(name = "label", nullable = false, updatable = false)
    private String label;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, updatable = false)
    private Instant updatedAt;

    /** A new block of the current salon's branch, which Hibernate stamps. */
    public static MemberBlockedTime of(
            UUID branchId,
            UUID memberId,
            LocalDate date,
            LocalTime starts,
            LocalTime finishes,
            String label,
            UUID by,
            Instant at) {
        MemberBlockedTime block = new MemberBlockedTime();
        block.id = UUID.randomUUID();
        block.branchId = branchId;
        block.memberId = memberId;
        block.blockDate = date;
        block.starts = starts;
        block.finishes = finishes;
        block.label = label;
        block.createdBy = by;
        block.createdAt = at;
        block.updatedAt = at;
        return block;
    }
}
