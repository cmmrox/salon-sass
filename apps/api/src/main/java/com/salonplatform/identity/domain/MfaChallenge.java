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
 * A staff member's second step, to verify a code or to enroll an authenticator: single use, valid for five
 * minutes, at most five attempts (docs/architecture/identity-and-access.md).
 */
@Entity
@Table(name = "identity_mfa_challenge")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MfaChallenge {

    private static final Duration LIFETIME = Duration.ofMinutes(5);

    private static final int ATTEMPTS = 5;

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "purpose")
    private String purpose;

    @Column(name = "credential_hash")
    private String credentialHash;

    @Column(name = "pending_secret")
    private String pendingSecret;

    @Column(name = "device_label")
    private String deviceLabel;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "attempts")
    private int attempts;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    /**
     * @param sessionId the session that asked for it, or null when a password sign-in did
     * @param credentialHash a hash of the challenge token handed to the caller
     */
    public static MfaChallenge issue(
            UUID accountId, UUID sessionId, String purpose, String deviceLabel, String credentialHash, Instant at) {
        MfaChallenge challenge = new MfaChallenge();
        challenge.id = UUID.randomUUID();
        challenge.createdAt = at;
        challenge.updatedAt = at;
        challenge.accountId = accountId;
        challenge.sessionId = sessionId;
        challenge.purpose = purpose;
        challenge.deviceLabel = deviceLabel;
        challenge.credentialHash = credentialHash;
        challenge.expiresAt = at.plus(LIFETIME);
        return challenge;
    }

    public boolean isEnrollment() {
        return MfaPurpose.ENROLL.equals(purpose);
    }

    /** Not consumed, not expired and with attempts left. */
    public boolean isUsableAt(Instant now) {
        return consumedAt == null && now.isBefore(expiresAt) && attempts < ATTEMPTS;
    }

    /** The encrypted authenticator secret this enrollment offers until a code confirms it. */
    public void offerSecret(String encryptedSecret, Instant at) {
        pendingSecret = encryptedSecret;
        updatedAt = at;
    }

    public void recordAttempt(Instant at) {
        attempts++;
        updatedAt = at;
    }

    public void consume(Instant at) {
        consumedAt = at;
    }
}
