package com.salonplatform.catalogue.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.TenantId;

/** One of the salon's categories, in its order (CAT-02). Its name is unique in the branch regardless of case. */
@Entity
@Table(name = "catalogue_category")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Category {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "position", nullable = false)
    private int position;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** A new category of the branch at this position, which Hibernate stamps with the current salon. */
    public static Category added(UUID branchId, String name, int position, Instant at) {
        Category category = new Category();
        category.id = UUID.randomUUID();
        category.branchId = branchId;
        category.name = name;
        category.position = position;
        category.createdAt = at;
        category.updatedAt = at;
        return category;
    }

    public void rename(String newName, Instant at) {
        name = newName;
        updatedAt = at;
    }

    /** Takes another category's place in the order; the two positions are swapped in one transaction. */
    public void moveTo(int newPosition, Instant at) {
        position = newPosition;
        updatedAt = at;
    }
}
