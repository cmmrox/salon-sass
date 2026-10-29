package com.salonplatform.identity.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "identity_mfa_challenge")
public class MfaChallenge {
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

    @Column(name = "session_id")
    public UUID sessionId;

    @Column(name = "purpose")
    public String purpose;

    @Column(name = "credential_hash")
    public String credentialHash;

    @Column(name = "pending_secret")
    public String pendingSecret;

    @Column(name = "device_label")
    public String deviceLabel;

    @Column(name = "expires_at")
    public Instant expiresAt;

    @Column(name = "attempts")
    public int attempts;

    @Column(name = "consumed_at")
    public Instant consumedAt;

    public MfaChallenge() {}
}
