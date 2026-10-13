package com.salonplatform.identity.domain;

import jakarta.persistence.*;

import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "identity_customer")
public class CustomerAccount {
    @Id public UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    public UUID tenantId;

    @Column(nullable = false)
    public String mobile;

    @Column(nullable = false)
    public String name;

    @Column(nullable = false)
    public boolean active;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @Version public long version;

    public CustomerAccount() {}
}
