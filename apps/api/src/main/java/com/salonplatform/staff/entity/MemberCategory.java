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

/** A whole category a member performs: every service in it, and any added to it later (decision 099). Held as catalogue's id. A save replaces the rows. */
@Entity
@Table(name = "staff_member_category")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberCategory {

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

    @Column(name = "category_id", nullable = false, updatable = false)
    private UUID categoryId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, updatable = false)
    private Instant updatedAt;

    /** A new row of the current salon's branch, which Hibernate stamps. */
    public static MemberCategory of(UUID branchId, UUID memberId, UUID categoryId, Instant at) {
        MemberCategory row = new MemberCategory();
        row.id = UUID.randomUUID();
        row.branchId = branchId;
        row.memberId = memberId;
        row.categoryId = categoryId;
        row.createdAt = at;
        row.updatedAt = at;
        return row;
    }
}
