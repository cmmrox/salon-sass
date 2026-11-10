package com.salonplatform.booking.entity;

import com.salonplatform.booking.domain.BookingState;
import com.salonplatform.booking.domain.CancellationReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.TenantId;

/**
 * One change of a booking's state (BKG-06): from what to what, when by the application's clock, who (a staff account,
 * or the system for an expiry), and why when it was a cancellation. Written once; the database refuses an update.
 */
@Entity
@Immutable
@Table(name = "booking_state_change")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BookingStateChange {

    static final String STAFF = "staff";
    static final String SYSTEM = "system";

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @Column(name = "booking_id", nullable = false, updatable = false)
    private UUID bookingId;

    @Column(name = "position", nullable = false, updatable = false)
    private int position;

    @Column(name = "from_state", updatable = false)
    private String fromState;

    @Column(name = "to_state", nullable = false, updatable = false)
    private String toState;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    @Column(name = "actor_kind", nullable = false, updatable = false)
    private String actorKind;

    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Column(name = "reason", updatable = false)
    private String reason;

    @Column(name = "note", updatable = false)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, updatable = false)
    private Instant updatedAt;

    /**
     * A change of the booking, made by a staff account or, when {@code staffId} is null, by the system.
     *
     * @param position 1 for the placing, then the next number for each change
     * @param from the state before; null when the hold was placed
     */
    public static BookingStateChange of(
            Booking booking,
            int position,
            BookingState from,
            BookingState to,
            UUID staffId,
            CancellationReason reason,
            String note,
            Instant at) {
        BookingStateChange change = new BookingStateChange();
        change.id = UUID.randomUUID();
        change.branchId = booking.getBranchId();
        change.bookingId = booking.getId();
        change.position = position;
        change.fromState = from == null ? null : from.value();
        change.toState = to.value();
        change.changedAt = at;
        change.actorKind = staffId == null ? SYSTEM : STAFF;
        change.actorId = staffId;
        change.reason = reason == null ? null : reason.value();
        change.note = note;
        change.createdAt = at;
        change.updatedAt = at;
        return change;
    }

    public Optional<BookingState> from() {
        return Optional.ofNullable(fromState).map(BookingState::of);
    }

    public BookingState to() {
        return BookingState.of(toState);
    }

    /** Whether the system made it (an expiry), rather than a member of staff. */
    public boolean bySystem() {
        return SYSTEM.equals(actorKind);
    }
}
