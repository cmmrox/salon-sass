package com.salonplatform.staff.entity;

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

/**
 * A branch's review of one published holiday year (F04-S03), from its first acceptance: until then the year
 * has no row, nothing of it applies, and its version is 0. Every acceptance and every later change of one
 * day's choice moves the version on by one, under the row lock it takes first.
 */
@Entity
@Table(name = "staff_holiday_review")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HolidayReview {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @Column(name = "year", nullable = false, updatable = false)
    private int year;

    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "accepted_at", nullable = false)
    private Instant acceptedAt;

    @Column(name = "accepted_by", nullable = false)
    private UUID acceptedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** A branch's first acceptance of the year: version 1. Hibernate stamps the current salon. */
    public static HolidayReview firstAcceptance(UUID branchId, int year, UUID by, Instant at) {
        HolidayReview review = new HolidayReview();
        review.id = UUID.randomUUID();
        review.branchId = branchId;
        review.year = year;
        review.version = 1;
        review.acceptedAt = at;
        review.acceptedBy = by;
        review.createdAt = at;
        review.updatedAt = at;
        return review;
    }

    /** A later acceptance, under the row lock. */
    public void accepted(UUID by, Instant at) {
        version++;
        acceptedAt = at;
        acceptedBy = by;
        updatedAt = at;
    }

    /** One day's choice changed after acceptance, under the row lock. */
    public void dayChanged(Instant at) {
        version++;
        updatedAt = at;
    }
}
