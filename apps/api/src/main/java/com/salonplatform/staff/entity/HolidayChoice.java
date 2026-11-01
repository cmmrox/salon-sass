package com.salonplatform.staff.entity;

import com.salonplatform.staff.domain.HolidayChoiceKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.TenantId;

/**
 * A branch's choice for one published holiday (F04-S03), made against the revision of that day it records.
 * It applies while the published day still carries that revision; a revision that changes the day puts it
 * back to review, and a choice for a withdrawn day is kept but applies to nothing.
 */
@Entity
@Table(name = "staff_holiday_choice")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HolidayChoice {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @Column(name = "review_id", nullable = false, updatable = false)
    private UUID reviewId;

    @Column(name = "holiday_date", nullable = false, updatable = false)
    private LocalDate holidayDate;

    @Column(name = "choice", nullable = false)
    private String choice;

    @Column(name = "opens")
    private LocalTime opens;

    @Column(name = "closes")
    private LocalTime closes;

    @Column(name = "day_revision", nullable = false)
    private int dayRevision;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** A first choice for the day. Hibernate stamps the current salon. */
    public static HolidayChoice made(
            UUID branchId,
            UUID reviewId,
            LocalDate date,
            HolidayChoiceKind kind,
            LocalTime opens,
            LocalTime closes,
            int dayRevision,
            Instant at) {
        HolidayChoice choice = new HolidayChoice();
        choice.id = UUID.randomUUID();
        choice.branchId = branchId;
        choice.reviewId = reviewId;
        choice.holidayDate = date;
        choice.write(kind, opens, closes, dayRevision);
        choice.createdAt = at;
        choice.updatedAt = at;
        return choice;
    }

    public HolidayChoiceKind kind() {
        return HolidayChoiceKind.of(choice);
    }

    /** Whether this choice is about the day as it is published now. */
    public boolean madeAgainst(int publishedRevision) {
        return dayRevision == publishedRevision;
    }

    /** A new choice for the day, against its current revision. */
    public void change(HolidayChoiceKind kind, LocalTime opens, LocalTime closes, int dayRevision, Instant at) {
        write(kind, opens, closes, dayRevision);
        updatedAt = at;
    }

    private void write(HolidayChoiceKind kind, LocalTime opens, LocalTime closes, int dayRevision) {
        this.choice = kind.value();
        this.opens = kind == HolidayChoiceKind.SPECIAL ? opens : null;
        this.closes = kind == HolidayChoiceKind.SPECIAL ? closes : null;
        this.dayRevision = dayRevision;
    }
}
