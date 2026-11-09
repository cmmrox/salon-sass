package com.salonplatform.booking.service;

import com.salonplatform.booking.domain.BookingAction;
import com.salonplatform.booking.service.BookingReadService.BookingView;
import java.util.UUID;

/**
 * Staff's changes to a booking's state (F07-S01 AC-5, AC-7, AC-12), each through the state machine with the booking's
 * row locked, so of two at once one applies and the other is judged against its result. Confirm, release, cancel,
 * no-show and its undoing need {@code booking.manage}; arrived and in service {@code booking.attend}, a Professional on
 * their own bookings only.
 */
public interface BookingChangeService {

    /** A cancellation as a request asks for it; nothing here has been checked. */
    record CancelInput(String reason, String note) {}

    /**
     * Takes a staff action other than release and cancel.
     *
     * @throws com.salonplatform.shared.web.ApplicationProblemException 403; 409 {@code invalid_state},
     *     {@code hold_expired} or, for an undone no-show whose time was taken, {@code slot_taken}
     * @throws com.salonplatform.shared.web.NotFoundException for one the caller cannot reach or see
     */
    BookingView change(UUID id, BookingAction action);

    /** Cancels a confirmed booking before it starts, with a reason and an optional note. */
    BookingView cancel(UUID id, CancelInput input);

    /** Gives up a hold at once, freeing its time. */
    void release(UUID id);
}
