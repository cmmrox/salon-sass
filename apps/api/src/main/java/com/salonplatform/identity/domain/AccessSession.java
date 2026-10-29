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
 * A signed-in device of a staff member or a customer. Only a hash of its current refresh credential is
 * kept; each rotation moves its generation on, so an access token of an earlier generation stops working.
 */
@Entity
@Table(name = "identity_session")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccessSession {

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

    @Column(name = "customer_account_id")
    private UUID customerAccountId;

    @Column(name = "kind")
    private String kind;

    @Column(name = "refresh_hash")
    private String refreshHash;

    @Column(name = "device_label")
    private String deviceLabel;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "generation")
    private long generation;

    @Column(name = "mfa_verified", nullable = false)
    private boolean mfaVerified;

    /** A staff member's session, which ends at {@code at} plus its lifetime whatever happens to it. */
    public static AccessSession forStaff(
            UUID accountId, String deviceLabel, boolean mfaVerified, Instant at, Duration lifetime) {
        AccessSession session = opened(SessionKind.STAFF, deviceLabel, at, lifetime);
        session.accountId = accountId;
        session.mfaVerified = mfaVerified;
        return session;
    }

    /** A customer's session, which ends at {@code at} plus its lifetime whatever happens to it. */
    public static AccessSession forCustomer(UUID customerAccountId, String deviceLabel, Instant at, Duration lifetime) {
        AccessSession session = opened(SessionKind.CUSTOMER, deviceLabel, at, lifetime);
        session.customerAccountId = customerAccountId;
        return session;
    }

    private static AccessSession opened(String kind, String deviceLabel, Instant at, Duration lifetime) {
        AccessSession session = new AccessSession();
        session.id = UUID.randomUUID();
        session.createdAt = at;
        session.updatedAt = at;
        session.kind = kind;
        session.deviceLabel = deviceLabel;
        session.expiresAt = at.plus(lifetime);
        return session;
    }

    public boolean isStaff() {
        return SessionKind.STAFF.equals(kind);
    }

    /** The staff account or the customer the session belongs to. */
    public UUID ownerId() {
        return isStaff() ? accountId : customerAccountId;
    }

    /** Replaces the refresh credential, moving the generation on. */
    public void rotate(String newRefreshHash, Instant at) {
        refreshHash = newRefreshHash;
        generation++;
        updatedAt = at;
    }

    public void revoke(Instant at) {
        revokedAt = at;
        updatedAt = at;
    }
}
