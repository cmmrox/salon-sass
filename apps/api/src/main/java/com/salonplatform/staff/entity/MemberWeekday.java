package com.salonplatform.staff.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.TenantId;

/** One weekday of a person's week, the times null when they do not work it. A save replaces the rows. */
@Entity
@Table(name = "staff_member_weekday")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberWeekday {

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

    @Column(name = "weekday", nullable = false, updatable = false)
    private short weekday;

    @Column(name = "starts", updatable = false)
    private LocalTime starts;

    @Column(name = "finishes", updatable = false)
    private LocalTime finishes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, updatable = false)
    private Instant updatedAt;

    /** A new row of the current salon's branch, which Hibernate stamps. */
    public static MemberWeekday of(
            UUID branchId, UUID memberId, DayOfWeek day, LocalTime starts, LocalTime finishes, Instant at) {
        MemberWeekday row = new MemberWeekday();
        row.id = UUID.randomUUID();
        row.branchId = branchId;
        row.memberId = memberId;
        row.weekday = (short) day.getValue();
        row.starts = starts;
        row.finishes = finishes;
        row.createdAt = at;
        row.updatedAt = at;
        return row;
    }

    public DayOfWeek day() {
        return DayOfWeek.of(weekday);
    }
}
