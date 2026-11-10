package com.salonplatform.booking.service.impl;

import com.salonplatform.booking.domain.BookingAction;
import com.salonplatform.booking.inprocess.BookingPayments;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Joins the payment's transaction (F08); see {@link InProcessChanges}. */
@Service
class BookingPaymentsImpl implements BookingPayments {

    private final InProcessChanges changes;

    BookingPaymentsImpl(InProcessChanges changes) {
        this.changes = changes;
    }

    @Override
    public void begin(UUID bookingId, UUID byStaff) {
        changes.take(bookingId, BookingAction.BEGIN_PAYMENT, byStaff);
    }

    @Override
    public void settle(UUID bookingId, UUID byStaff) {
        changes.take(bookingId, BookingAction.SETTLE_PAYMENT, byStaff);
    }

    @Override
    public void abandon(UUID bookingId, UUID byStaff) {
        changes.take(bookingId, BookingAction.ABANDON_PAYMENT, byStaff);
    }
}
