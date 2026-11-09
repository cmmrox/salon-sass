package com.salonplatform.booking.service.impl;

import com.salonplatform.booking.domain.BookingAction;
import com.salonplatform.booking.inprocess.BookingCheckout;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Joins the sale's transaction (F10); see {@link InProcessChanges}. */
@Service
class BookingCheckoutImpl implements BookingCheckout {

    private final InProcessChanges changes;

    BookingCheckoutImpl(InProcessChanges changes) {
        this.changes = changes;
    }

    @Override
    public void complete(UUID bookingId, UUID byStaff) {
        changes.take(bookingId, BookingAction.COMPLETE, byStaff);
    }
}
