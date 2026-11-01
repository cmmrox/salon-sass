package com.salonplatform.catalogue.entity;

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
 * A branch's category list (F05-S01): created with the salon's default categories, never by the application. Its
 * version is raised by every add, delete and move of a category, so that a move made from a stale list is refused.
 * Every such change, and every new service, first locks this row until its transaction ends, so they wait for each
 * other: the lock, not an optimistic check, guards the version, which is why it is a plain counter here.
 */
@Entity
@Table(name = "catalogue_category_list")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CategoryList {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @Column(name = "version", nullable = false)
    private long listVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Records a change of the list's categories or their order: its version goes up by one. */
    public void changed(Instant at) {
        listVersion++;
        updatedAt = at;
    }
}
