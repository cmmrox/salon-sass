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

/** A single service a member performs, and no other of its category because of it (decision 099). Held as catalogue's id. A save replaces the rows. */
@Entity
@Table(name = "staff_member_service")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberSingleService {

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

    @Column(name = "service_id", nullable = false, updatable = false)
    private UUID serviceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, updatable = false)
    private Instant updatedAt;

    /** A new row of the current salon's branch, which Hibernate stamps. */
    public static MemberSingleService of(UUID branchId, UUID memberId, UUID serviceId, Instant at) {
        MemberSingleService row = new MemberSingleService();
        row.id = UUID.randomUUID();
        row.branchId = branchId;
        row.memberId = memberId;
        row.serviceId = serviceId;
        row.createdAt = at;
        row.updatedAt = at;
        return row;
    }
}
