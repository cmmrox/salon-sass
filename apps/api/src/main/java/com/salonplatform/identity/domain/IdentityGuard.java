package com.salonplatform.identity.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "identity_guard")
public class IdentityGuard {
    @Id
    @Column(nullable = false, updatable = false)
    public UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    public UUID tenantId;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    public IdentityGuard() {}
}
