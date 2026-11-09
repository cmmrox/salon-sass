package com.salonplatform.booking.service.impl;

import com.salonplatform.booking.domain.BookingAction;
import com.salonplatform.booking.domain.BookingStateMachine;
import com.salonplatform.booking.entity.Booking;
import com.salonplatform.booking.repository.BookingRepository;
import com.salonplatform.booking.service.impl.BookingChanges.Why;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.shared.web.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * A change another module makes in its own transaction (the in-process contracts of F08 and F10): it joins that
 * transaction, which sets the salon, and refuses to run outside a writable one, where the change would commit apart
 * from the payment or sale that caused it.
 */
@Component
class InProcessChanges {

    private final BookingRepository bookings;
    private final BookingChanges changes;
    private final Clock clock;

    InProcessChanges(BookingRepository bookings, BookingChanges changes, Clock clock) {
        this.bookings = bookings;
        this.changes = changes;
        this.clock = clock;
    }

    void take(UUID bookingId, BookingAction action, UUID byStaff) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("A booking's in-process change joins the caller's writable transaction");
        }
        ZoneId zone = TenantContext.require().timeZone();
        Instant now = clock.instant();
        Booking booking = bookings.lockBooking(bookingId).orElseThrow(NotFoundException::new);
        boolean shown = BookingStateMachine.shown(booking.situation(zone), now);
        if (!shown && !(action == BookingAction.BEGIN_PAYMENT && BookingStateMachine.heldOrExpired(booking.state()))) {
            throw new NotFoundException();
        }
        changes.take(booking, action, zone, byStaff, Why.NONE, now);
        bookings.flush();
    }
}
