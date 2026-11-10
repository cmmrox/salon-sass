package com.salonplatform.booking.service.impl;

import com.salonplatform.booking.domain.BookingAction;
import com.salonplatform.booking.domain.BookingStateMachine;
import com.salonplatform.booking.domain.CancellationReason;
import com.salonplatform.booking.entity.Booking;
import com.salonplatform.booking.repository.BookingRepository;
import com.salonplatform.booking.service.BookingChangeService;
import com.salonplatform.booking.service.BookingReadService.BookingView;
import com.salonplatform.booking.service.impl.BookingCallers.Caller;
import com.salonplatform.booking.service.impl.BookingChanges.Why;
import com.salonplatform.catalogue.UpcomingServiceBookings;
import com.salonplatform.shared.persistence.TransactionLocks;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import com.salonplatform.shared.web.NotFoundException;
import com.salonplatform.staff.TeamMembers;
import com.salonplatform.staff.UpcomingMemberBookings;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Staff's changes of state (AC-5, AC-7). Each locks the booking's row first, so a second change at the same moment
 * waits and is then judged against the first one's result. A booking the caller cannot reach, and one no longer shown
 * (an ended hold, or one that has run out), is not found, except that confirming a hold that has run out says so:
 * {@code hold_expired}. Undoing a no-show makes the booking live again, so its professional's run-out holds over that
 * time are ended first and the exclusion constraint decides: {@code slot_taken} when the time was booked meanwhile, and
 * the booking stays a no-show. It also holds the guard locks and checks the professional still performs the service, as
 * a placement does, so a removal never strands it.
 */
@Service
class BookingChangeServiceImpl implements BookingChangeService {

    private final BookingCallers callers;
    private final BookingRepository bookings;
    private final BookingChanges changes;
    private final TeamMembers team;
    private final TransactionLocks locks;
    private final Clock clock;
    private final TransactionTemplate writing;

    BookingChangeServiceImpl(
            BookingCallers callers,
            BookingRepository bookings,
            BookingChanges changes,
            TeamMembers team,
            TransactionLocks locks,
            Clock clock,
            PlatformTransactionManager transactions) {
        this.callers = callers;
        this.bookings = bookings;
        this.changes = changes;
        this.team = team;
        this.locks = locks;
        this.clock = clock;
        this.writing = new TransactionTemplate(transactions);
    }

    @Override
    public BookingView change(UUID id, BookingAction action) {
        if (action == BookingAction.CANCEL
                || action == BookingAction.RELEASE
                || !BookingAction.STAFF.contains(action)) {
            throw new IllegalArgumentException("Not a plain staff action: " + action);
        }
        return take(id, action, Why.NONE);
    }

    @Override
    public BookingView cancel(UUID id, CancelInput input) {
        Caller at = callers.forAction(BookingAction.CANCEL);
        List<FieldProblem> errors = new ArrayList<>();
        Optional<CancellationReason> reason =
                input.reason() == null ? Optional.empty() : CancellationReason.parse(input.reason());
        if (reason.isEmpty()) {
            errors.add(new FieldProblem("reason", "must be customer_asked, salon_unable, booked_by_mistake or other"));
        }
        String note = input.note() == null || input.note().isBlank()
                ? null
                : input.note().strip();
        if (note != null && note.codePointCount(0, note.length()) > CancellationReason.NOTE_MAX) {
            errors.add(new FieldProblem("note", "must be at most 200 characters"));
        }
        if (!errors.isEmpty()) {
            throw new ApplicationProblemException(400, ErrorCode.BAD_REQUEST, errors);
        }
        return take(at, id, BookingAction.CANCEL, new Why(reason.orElseThrow(), note));
    }

    @Override
    public void release(UUID id) {
        take(id, BookingAction.RELEASE, Why.NONE);
    }

    private BookingView take(UUID id, BookingAction action, Why why) {
        return take(callers.forAction(action), id, action, why);
    }

    private BookingView take(Caller at, UUID id, BookingAction action, Why why) {
        try {
            Booking changed = writing.execute(status -> {
                Booking booking = bookings.lockBooking(id).orElseThrow(NotFoundException::new);
                Instant now = clock.instant();
                if (!at.reaches(action, booking)) {
                    throw new NotFoundException();
                }
                boolean shown =
                        BookingStateMachine.shown(booking.situation(at.salon().timeZone()), now);
                if (!shown
                        && !(action == BookingAction.CONFIRM && BookingStateMachine.heldOrExpired(booking.state()))) {
                    throw new NotFoundException();
                }
                if (action == BookingAction.UNDO_NO_SHOW) {
                    prepareToLiveAgain(booking, at, now);
                }
                changes.take(booking, action, at.salon().timeZone(), at.staffId(), why, now);
                bookings.flush();
                return booking;
            });
            return BookingViews.of(changed, at.salon().timeZone());
        } catch (DataIntegrityViolationException violation) {
            if (BookingConstraints.of(violation)
                    .filter(BookingConstraints.NO_OVERLAP::equals)
                    .isPresent()) {
                throw new ApplicationProblemException(409, ErrorCode.SLOT_TAKEN)
                        .withDetail("The time has been booked since, so the booking stays a no-show.");
            }
            throw violation;
        }
    }

    /**
     * Before a no-show becomes a booking again: the guard locks, a check that the professional still performs the
     * service, and the end of their run-out holds over its time.
     */
    private void prepareToLiveAgain(Booking booking, Caller at, Instant now) {
        if (BookingStateMachine.decide(
                        BookingAction.UNDO_NO_SHOW, booking.situation(at.salon().timeZone()), now)
                instanceof BookingStateMachine.Refused refused) {
            throw BookingChanges.refusal(refused);
        }
        locks.hold(UpcomingMemberBookings.GUARD_LOCK, booking.getMemberId());
        locks.hold(UpcomingServiceBookings.GUARD_LOCK, booking.getServiceId());
        if (!team.servicesOf(booking.getMemberId()).contains(booking.getServiceId())) {
            throw new ApplicationProblemException(409, ErrorCode.INVALID_STATE)
                    .withDetail("The professional no longer performs this service, so the no-show cannot be undone.");
        }
        changes.endRunOutHolds(
                booking.getMemberId(),
                booking.getStartsAt(),
                booking.getEndsAt(),
                at.salon().timeZone(),
                now);
    }
}
