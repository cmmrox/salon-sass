package com.salonplatform.identity.domain;

import jakarta.persistence.*;

import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "identity_staff")
public class StaffAccount {
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

    @Column(name = "email")
    public String email;

    @Column(name = "mobile")
    public String mobile;

    @Column(name = "password_hash")
    public String passwordHash;

    @Column(name = "role")
    public String role;

    @Column(name = "active")
    public boolean active;

    @Column(name = "professional_grant")
    public boolean professionalGrant;

    @Column(name = "mfa_secret")
    public String mfaSecret;

    @Version
    @Column(name = "version")
    public long version;

    public StaffAccount() {}
}
