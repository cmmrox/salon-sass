package com.salonplatform.identity.domain;

import jakarta.persistence.*;

import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "identity_customer_challenge")
public class CustomerChallenge {
    @Id public UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    public UUID tenantId;

    @Column(name = "number_hash", nullable = false)
    public String numberHash;

    @Column(name = "encrypted_recipient", nullable = false)
    public String encryptedRecipient;

    @Column(name = "code_hash", nullable = false)
    public String codeHash;

    @Column(name = "device_hash", nullable = false)
    public String deviceHash;

    @Column(name = "device_label", nullable = false)
    public String deviceLabel;

    @Column(nullable = false)
    public String channel;

    @Column(name = "message_id", nullable = false)
    public UUID messageId;

    @Column(name = "expires_at", nullable = false)
    public Instant expiresAt;

    @Column(nullable = false)
    public int attempts;

    @Column(name = "invalidated_at")
    public Instant invalidatedAt;

    @Column(name = "consumed_at")
    public Instant consumedAt;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    public CustomerChallenge() {}
}
