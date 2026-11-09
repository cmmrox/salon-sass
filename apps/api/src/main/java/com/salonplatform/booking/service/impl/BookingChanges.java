package com.salonplatform.booking.service.impl;

import com.salonplatform.booking.BookingConfirmed;
import com.salonplatform.booking.domain.BookingAction;
import com.salonplatform.booking.domain.BookingState;
import com.salonplatform.booking.domain.BookingStateMachine;
import com.salonplatform.booking.domain.BookingStateMachine.Allowed;
import com.salonplatform.booking.domain.BookingStateMachine.Decision;
import com.salonplatform.booking.domain.BookingStateMachine.Refused;
import com.salonplatform.booking.domain.CancellationReason;
import com.salonplatform.booking.entity.Booking;
import com.salonplatform.booking.entity.BookingStateChange;
import com.salonplatform.booking.repository.BookingRepository;
import com.salonplatform.booking.repository.BookingStateChangeRepository;
import com.salonplatform.shared.outbox.Outbox;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Every change of a booking's state goes through here, inside the changing transaction (BKG-06, AC-9): the state
 * machine decides, the booking takes the new state, and the change is written to the booking's history and the
 * salon's audit trail. Confirming, by staff or by payment, also writes {@link BookingConfirmed} to the outbox in the
 * same transaction (CR-021).
 */
@Component
class BookingChanges {

    private final BookingRepository bookings;
    private final BookingStateChangeRepository history;
    private final BookingAudit audit;
    private final Outbox outbox;

    BookingChanges(
            BookingRepository bookings, BookingStateChangeRepository history, BookingAudit audit, Outbox outbox) {
        this.bookings = bookings;
        this.history = history;
        this.audit = audit;
        this.outbox = outbox;
    }

    /** What a cancellation records; both null for every other change. */
    record Why(CancellationReason reason, String note) {

        static final Why NONE = new Why(null, null);
    }

    /** Records a hold just placed: its first history entry and the audit entry. */
    void placed(Booking booking, UUID staffId, Instant at) {
        history.persist(BookingStateChange.of(booking, 1, null, BookingState.HELD, staffId, null, null, at));
        audit.placed(booking, staffId, at);
    }

    /**
     * Applies the action when the state machine allows it now, or refuses: 409 {@code invalid_state} or
     * {@code hold_expired}, with a detail naming the rule, and nothing changed.
     *
     * @param staffId who acts, or null for the system
     */
    BookingState take(Booking booking, BookingAction action, ZoneId zone, UUID staffId, Why why, Instant now) {
        Decision decision = BookingStateMachine.decide(action, booking.situation(zone), now);
        if (decision instanceof Refused refused) {
            throw refusal(refused);
        }
        BookingState to = ((Allowed) decision).to();
        BookingState from = booking.state();
        booking.moveTo(to, now);
        int position = Math.toIntExact(history.countOf(booking.getId()) + 1);
        history.persist(BookingStateChange.of(booking, position, from, to, staffId, why.reason(), why.note(), now));
        audit.changed(booking, action, from, to, staffId, why.reason(), now);
        if (to == BookingState.CONFIRMED
                && (action == BookingAction.CONFIRM || action == BookingAction.SETTLE_PAYMENT)) {
            outbox.publish(new BookingConfirmed(booking.getId(), booking.getMemberId(), booking.getServiceId()));
        }
        return to;
    }

    /**
     * Ends the member's holds over this time whose expiry has passed, as the system, and writes the change to the
     * database at once: the exclusion constraint compares rows as stored, and this transaction is about to need the
     * time (AC-3).
     */
    void endRunOutHolds(UUID memberId, Instant from, Instant until, ZoneId zone, Instant now) {
        for (Booking hold : bookings.lockRunOutHolds(memberId, from, until, now)) {
            take(hold, BookingAction.EXPIRE, zone, null, Why.NONE, now);
        }
        bookings.flush();
    }

    static ApplicationProblemException refusal(Refused refused) {
        ErrorCode code = refused.reason() == BookingStateMachine.Reason.HOLD_EXPIRED
                ? ErrorCode.HOLD_EXPIRED
                : ErrorCode.INVALID_STATE;
        return new ApplicationProblemException(409, code).withDetail(refused.detail());
    }
}
