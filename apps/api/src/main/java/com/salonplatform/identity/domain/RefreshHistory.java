package com.salonplatform.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.TenantId;

/** A refresh credential a session has rotated away from: presented again, it gives the replay away. */
@Entity
@Table(name = "identity_refresh_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshHistory {

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

    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "credential_hash")
    private String credentialHash;

    public static RefreshHistory spent(UUID sessionId, String credentialHash, Instant at) {
        RefreshHistory history = new RefreshHistory();
        history.id = UUID.randomUUID();
        history.createdAt = at;
        history.updatedAt = at;
        history.sessionId = sessionId;
        history.credentialHash = credentialHash;
        return history;
    }
}
