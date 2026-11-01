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

/**
 * How a branch's Popular list is made (CAT-06, decision 095): the services the salon chose, or the most booked of
 * the last 90 days. Created on first use by {@code PopularSettingRows}, never by this entity, so that two first
 * uses wait for one row; a choice of a Popular service locks it, so the limit of six holds.
 */
@Entity
@Table(name = "catalogue_popular_setting")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PopularSetting {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @Column(name = "mode", nullable = false)
    private String mode;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public void switchTo(String newMode, Instant at) {
        mode = newMode;
        updatedAt = at;
    }
}
