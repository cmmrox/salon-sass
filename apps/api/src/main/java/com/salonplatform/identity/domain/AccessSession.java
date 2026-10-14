package com.salonplatform.identity.domain;

import jakarta.persistence.*;

import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "identity_session")
public class AccessSession {
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

    @Column(name = "account_id")
    public UUID accountId;

    @Column(name = "customer_account_id")
    public UUID customerAccountId;

    @Column(name = "kind")
    public String kind;

    @Column(name = "refresh_hash")
    public String refreshHash;

    @Column(name = "device_label")
    public String deviceLabel;

    @Column(name = "expires_at")
    public Instant expiresAt;

    @Column(name = "revoked_at")
    public Instant revokedAt;

    @Column(name = "generation")
    public long generation;

    @Column(name = "mfa_verified", nullable = false)
    public boolean mfaVerified;

    public AccessSession() {}
}
