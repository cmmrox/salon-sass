package com.salonplatform.booking.inprocess;

import java.util.UUID;

/**
 * A hold's payment (BKG-04, F08): while it is in progress the hold is {@code pending_payment}, which the expiry job
 * never ends; it then becomes {@code confirmed}, or {@code held} again when the payment does not go through. Unused until
 * F08. Joins the caller's transaction: the payment's record and the booking's state commit together, so a booking is
 * never confirmed by a payment that was rolled back. Each change takes the booking's row lock, writes its history and
 * the salon's audit trail, and confirming writes {@link com.salonplatform.booking.BookingConfirmed} to the outbox.
 *
 * <p>Each method refuses with {@code 404 not_found} for a booking the salon does not have or does not show, and
 * {@code 409 invalid_state} (or {@code hold_expired} for a hold that has run out) when the state machine refuses.
 */
public interface BookingPayments {

    /**
     * The payment starts: a live hold becomes {@code pending_payment}.
     *
     * @param byStaff the staff account that started it, or null when the system did
     */
    void begin(UUID bookingId, UUID byStaff);

    /** The payment succeeded: {@code pending_payment} becomes {@code confirmed}. */
    void settle(UUID bookingId, UUID byStaff);

    /** The payment did not go through: {@code pending_payment} is a hold again, with its expiry as it was. */
    void abandon(UUID bookingId, UUID byStaff);
}
