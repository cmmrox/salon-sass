package com.salonplatform.booking.inprocess;

import java.util.UUID;

/**
 * Checkout (BKG-06, F10): the only way a booking is {@code completed}, from {@code arrived} or {@code in_service}.
 * Unused until F10. Joins the caller's transaction, so a booking is completed exactly when its sale commits. The change
 * takes the booking's row lock and writes its history and the salon's audit trail.
 */
public interface BookingCheckout {

    /**
     * Completes the booking.
     *
     * @param byStaff the staff account at checkout
     * @throws com.salonplatform.shared.web.NotFoundException for a booking the salon does not have or does not show
     * @throws com.salonplatform.shared.web.ApplicationProblemException 409 {@code invalid_state} from any other state
     */
    void complete(UUID bookingId, UUID byStaff);
}
