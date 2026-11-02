package com.salonplatform.staff.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.TenantId;

/**
 * A person's week's own version (decision 102(f)), so that saving their hours never conflicts with editing their profile.
 * A plain column moved on by one by every save, under the member's row lock a save takes first. A member with no row has
 * never had a week: they read as version 0 with no working days.
 */
@Entity
@Table(name = "staff_member_schedule")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberSchedule {

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

    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** A person's first week, which Hibernate stamps with the current salon: version 1. */
    public static MemberSchedule first(UUID branchId, UUID memberId, Instant at) {
        MemberSchedule schedule = new MemberSchedule();
        schedule.id = UUID.randomUUID();
        schedule.branchId = branchId;
        schedule.memberId = memberId;
        schedule.version = 1;
        schedule.createdAt = at;
        schedule.updatedAt = at;
        return schedule;
    }

    /** A later save, under the member's row lock: the version moves on by one. */
    public void saved(Instant at) {
        version++;
        updatedAt = at;
    }
}
