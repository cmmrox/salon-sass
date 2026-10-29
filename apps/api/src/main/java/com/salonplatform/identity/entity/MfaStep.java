package com.salonplatform.identity.entity;

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

/** A time step whose code an account has used: the same code is never accepted twice. */
@Entity
@Table(name = "identity_mfa_step")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MfaStep {

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

    @Column(name = "step")
    private long step;

    public static MfaStep used(UUID accountId, long step, Instant at) {
        MfaStep used = new MfaStep();
        used.id = UUID.randomUUID();
        used.createdAt = at;
        used.updatedAt = at;
        used.accountId = accountId;
        used.step = step;
        return used;
    }
}
