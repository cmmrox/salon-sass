package com.salonplatform.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.TenantId;

/**
 * A one-time code sent to a customer's number: valid for five minutes, five attempts, consumed once, and
 * invalidated by a newer request for the same number (docs/architecture/identity-and-access.md). The number
 * and the code are kept only as hashes and an encrypted recipient.
 */
@Entity
@Table(name = "identity_customer_challenge")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustomerChallenge {

    private static final Duration LIFETIME = Duration.ofMinutes(5);

    private static final int ATTEMPTS = 5;

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "number_hash", nullable = false)
    private String numberHash;

    @Column(name = "encrypted_recipient", nullable = false)
    private String encryptedRecipient;

    @Column(name = "code_hash", nullable = false)
    private String codeHash;

    @Column(name = "device_hash", nullable = false)
    private String deviceHash;

    @Column(name = "device_label", nullable = false)
    private String deviceLabel;

    @Column(nullable = false)
    private String channel;

    @Column(name = "message_id", nullable = false)
    private UUID messageId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "invalidated_at")
    private Instant invalidatedAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * @param id chosen by the caller, which binds the code's hash and the encrypted message to it before the
     *     challenge is created
     */
    public static CustomerChallenge issue(UUID id, Delivery delivery, Instant at) {
        CustomerChallenge challenge = new CustomerChallenge();
        challenge.id = id;
        challenge.createdAt = at;
        challenge.updatedAt = at;
        challenge.numberHash = delivery.numberHash();
        challenge.encryptedRecipient = delivery.encryptedRecipient();
        challenge.codeHash = delivery.codeHash();
        challenge.deviceHash = delivery.deviceHash();
        challenge.deviceLabel = delivery.deviceLabel();
        challenge.channel = delivery.channel();
        challenge.messageId = delivery.messageId();
        challenge.expiresAt = at.plus(LIFETIME);
        return challenge;
    }

    /** Invalidated, consumed, or out of attempts. */
    public boolean isSpent() {
        return invalidatedAt != null || consumedAt != null || attempts >= ATTEMPTS;
    }

    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public void invalidate(Instant at) {
        invalidatedAt = at;
        updatedAt = at;
    }

    public void recordAttempt(Instant at) {
        attempts++;
        updatedAt = at;
    }

    public void consume(Instant at) {
        consumedAt = at;
    }

    /** How the code reaches the customer, and what proves it later: hashes and encrypted values only. */
    public record Delivery(
            String numberHash,
            String encryptedRecipient,
            String codeHash,
            String deviceHash,
            String deviceLabel,
            String channel,
            UUID messageId) {}
}
