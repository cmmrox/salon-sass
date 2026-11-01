package com.salonplatform.staff.service;

import com.salonplatform.staff.AffectedBooking;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A salon's special closures (F04-S03): the whole of a local date closed, from today to a year ahead, with an
 * optional reason only its team sees. The bookings on the date are listed, never cancelled. The branch is the
 * salon's default branch.
 */
public interface ClosureService {

    /** A closure, with how many bookings it affects. */
    record ClosureView(UUID id, LocalDate date, String reason, int affectedBookings) {}

    /** The bookings on a date a closure would affect. */
    record Impact(LocalDate date, List<AffectedBooking> bookings) {}

    /** The closures from the salon's today on, in date order. */
    List<ClosureView> upcoming();

    /** The bookings on the date. 400 when the date is not one a closure may have. */
    Impact impact(String date);

    /**
     * Closes the date. 400 naming {@code date} (not today to a year ahead) or {@code reason} (over 60
     * characters); 409 {@code conflict} when the date is closed already.
     */
    ClosureView close(String date, String reason);

    /** Reopens the date. 404 for an id that names none of the salon's closures; 409 {@code invalid_state} once passed. */
    void reopen(String id);
}
