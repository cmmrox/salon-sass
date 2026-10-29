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
 * Attempts counted against one key (a hashed identifier, address, number or device) in one dimension,
 * within a window that starts with the first attempt.
 */
@Entity
@Table(name = "identity_rate_bucket")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RateBucket {

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

    @Column(name = "dimension")
    private String dimension;

    @Column(name = "key_hash")
    private String keyHash;

    @Column(name = "window_start")
    private Instant windowStart;

    @Column(name = "attempts")
    private int attempts;

    /** A bucket with no attempt yet, its window starting now. */
    public static RateBucket open(String dimension, String keyHash, Instant at) {
        RateBucket bucket = new RateBucket();
        bucket.id = UUID.randomUUID();
        bucket.createdAt = at;
        bucket.updatedAt = at;
        bucket.dimension = dimension;
        bucket.keyHash = keyHash;
        bucket.windowStart = at;
        return bucket;
    }

    /**
     * Counts one attempt, in a new window when the last one has run out.
     *
     * @return whether the attempt is within the ceiling
     */
    public boolean countAttempt(Instant now, Duration window, int ceiling) {
        if (!now.isBefore(windowStart.plus(window))) {
            windowStart = now;
            attempts = 0;
        }
        attempts++;
        updatedAt = now;
        return attempts <= ceiling;
    }

    /** Counts an accepted request, after the caller has checked every dimension's ceiling first. */
    public void countAccepted(Instant currentWindowStart, int attemptsSoFar, Instant at) {
        windowStart = currentWindowStart;
        attempts = attemptsSoFar + 1;
        updatedAt = at;
    }
}
