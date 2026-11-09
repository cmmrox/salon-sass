package com.salonplatform.booking.service.impl;

import com.salonplatform.booking.BookingPolicies;
import com.salonplatform.booking.BookingRules;
import com.salonplatform.booking.domain.BookingReference;
import com.salonplatform.booking.domain.BookingStateMachine;
import com.salonplatform.booking.domain.Occupation;
import com.salonplatform.booking.entity.Booking;
import com.salonplatform.booking.repository.BookingRepository;
import com.salonplatform.booking.service.BookingReadService.BookingView;
import com.salonplatform.booking.service.HoldService;
import com.salonplatform.booking.service.impl.BookingCallers.Caller;
import com.salonplatform.booking.service.impl.HoldRequests.Checked;
import com.salonplatform.catalogue.CatalogueListings;
import com.salonplatform.catalogue.ServiceTerms;
import com.salonplatform.catalogue.ServiceTermsVersion;
import com.salonplatform.catalogue.ServiceVariant;
import com.salonplatform.catalogue.UpcomingServiceBookings;
import com.salonplatform.shared.persistence.TransactionLocks;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.NotFoundException;
import com.salonplatform.staff.TeamMember;
import com.salonplatform.staff.TeamMembers;
import com.salonplatform.staff.UpcomingMemberBookings;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.random.RandomGenerator;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Places a hold (F07-S01 AC-1 to AC-4, AC-10, AC-13). Everything other modules answer (the booking rules, the
 * professional, the service's terms, the opening hours and the professional's working day) is read before the placing
 * transaction opens, as their contracts ask, and decides the clear refusals: 404 for what the salon does not have, 409
 * {@code slot_unavailable} for a time that cannot be booked.
 *
 * <p>The placing transaction then holds the guard locks on the professional and the service ({@link
 * UpcomingMemberBookings#GUARD_LOCK}, {@link UpcomingServiceBookings#GUARD_LOCK}, in that order everywhere) and, under
 * them, checks again that the service is still listed and the professional still performs it, so a removal or delete
 * racing this hold never also succeeds. It ends the professional's holds over this time that have run out, then inserts
 * the hold. The exclusion constraint is what refuses an overlap: its violation, and only it, is {@code slot_taken}. A
 * reference already taken is drawn again; a collision the check before the insert missed retries the transaction.
 */
@Service
class HoldServiceImpl implements HoldService {

    /** How many times a placement is tried when a newly drawn reference turns out to be taken. */
    static final int REFERENCE_ATTEMPTS = 5;

    private final BookingCallers callers;
    private final HoldRequests requests;
    private final BookingPolicies policies;
    private final TeamMembers team;
    private final ServiceTerms terms;
    private final CatalogueListings listings;
    private final HoldTimes times;
    private final BookingRepository bookings;
    private final BookingChanges changes;
    private final TransactionLocks locks;
    private final RandomGenerator random;
    private final Clock clock;
    private final TransactionTemplate writing;
    private final TransactionTemplate reading;

    HoldServiceImpl(
            BookingCallers callers,
            HoldRequests requests,
            BookingPolicies policies,
            TeamMembers team,
            ServiceTerms terms,
            CatalogueListings listings,
            HoldTimes times,
            BookingRepository bookings,
            BookingChanges changes,
            TransactionLocks locks,
            RandomGenerator random,
            Clock clock,
            PlatformTransactionManager transactions) {
        this.callers = callers;
        this.requests = requests;
        this.policies = policies;
        this.team = team;
        this.terms = terms;
        this.listings = listings;
        this.times = times;
        this.bookings = bookings;
        this.changes = changes;
        this.locks = locks;
        this.random = random;
        this.clock = clock;
        this.writing = new TransactionTemplate(transactions);
        this.reading = new TransactionTemplate(transactions);
        this.reading.setReadOnly(true);
    }

    /** What the hold is placed with, once everything read before the transaction has been checked. */
    private record Prepared(Caller at, Checked request, Booking.Terms terms, Instant startsAt, Occupation occupation) {}

    @Override
    public BookingView place(HoldInput input, String idempotencyKey) {
        Caller at = callers.of(BookingCallers.MANAGE);
        Checked request = requests.check(input, idempotencyKey);
        Optional<BookingView> earlier = replayed(at, request);
        if (earlier.isPresent()) {
            return earlier.get();
        }
        Prepared prepared = prepare(at, request);
        for (int attempt = 1; ; attempt++) {
            try {
                return BookingViews.of(
                        writing.execute(status -> placeHold(prepared)),
                        at.salon().timeZone());
            } catch (Replay replay) {
                return replayed(at, request).orElseThrow(NotFoundException::new);
            } catch (DataIntegrityViolationException violation) {
                Optional<BookingConstraints> broken = BookingConstraints.of(violation);
                if (broken.isEmpty()) {
                    throw violation;
                }
                switch (broken.get()) {
                    case NO_OVERLAP -> throw new ApplicationProblemException(409, ErrorCode.SLOT_TAKEN);
                    case IDEMPOTENCY -> {
                        return replayed(at, request).orElseThrow(NotFoundException::new);
                    }
                    case REFERENCE -> {
                        if (attempt >= REFERENCE_ATTEMPTS) {
                            throw violation;
                        }
                    }
                }
            }
        }
    }

    /**
     * The booking an earlier request with this key placed, when there is one: the same booking for the same request,
     * not found once it is no longer shown, and 422 for another request.
     */
    private Optional<BookingView> replayed(Caller at, Checked request) {
        Optional<Booking> earlier = reading.execute(status -> bookings.findByKey(request.key()));
        if (earlier.isEmpty()) {
            return Optional.empty();
        }
        Booking booking = earlier.get();
        if (!booking.getRequestFingerprint().equals(request.fingerprint())) {
            throw new ApplicationProblemException(422, ErrorCode.REQUEST_REJECTED)
                    .withDetail("This Idempotency-Key was already used for a different request.");
        }
        if (!BookingStateMachine.shown(booking.situation(at.salon().timeZone()), clock.instant())) {
            throw new NotFoundException();
        }
        return Optional.of(BookingViews.of(booking, at.salon().timeZone()));
    }

    private Prepared prepare(Caller at, Checked request) {
        BookingRules rules =
                policies.current().orElseThrow(() -> new ApplicationProblemException(409, ErrorCode.RULES_NOT_SET));
        TeamMember member = team.member(request.memberId())
                .filter(found -> !found.removed())
                .orElseThrow(NotFoundException::new);
        ServiceTermsVersion service = terms.current(request.serviceId()).orElseThrow(NotFoundException::new);
        Optional<ServiceVariant> variant = requests.variantOf(service, request.variantId());
        if (!team.servicesOf(member.id()).contains(service.serviceId())) {
            throw HoldTimes.unavailable("The professional does not perform this service.");
        }
        Booking.Terms booked = new Booking.Terms(
                member.id(),
                member.name(),
                service.serviceId(),
                service.versionId(),
                service.name(),
                variant.map(ServiceVariant::variantId).orElse(null),
                variant.map(ServiceVariant::name).orElse(null),
                rules.versionId(),
                variant.map(ServiceVariant::segments).orElse(service.segments()),
                variant.map(ServiceVariant::priceMinor).orElse(service.priceMinor()),
                service.currency());
        Instant startsAt = times.startOf(at, request.date(), request.time(), clock.instant());
        Occupation occupation = Occupation.of(startsAt, booked.segments());
        times.requireOpenAndWorking(at, member.id(), request.date(), occupation);
        return new Prepared(at, request, booked, startsAt, occupation);
    }

    private Booking placeHold(Prepared prepared) {
        Booking.Terms booked = prepared.terms();
        locks.hold(UpcomingMemberBookings.GUARD_LOCK, booked.memberId());
        locks.hold(UpcomingServiceBookings.GUARD_LOCK, booked.serviceId());
        if (bookings.findByKey(prepared.request().key()).isPresent()) {
            throw new Replay();
        }
        // Under the guard locks, a removal or delete that won the race has committed, and is seen here.
        if (listings.current().services().stream()
                        .noneMatch(listed -> listed.id().equals(booked.serviceId()))
                || team.member(booked.memberId())
                        .filter(found -> !found.removed())
                        .isEmpty()) {
            throw new NotFoundException();
        }
        if (!team.servicesOf(booked.memberId()).contains(booked.serviceId())) {
            throw HoldTimes.unavailable("The professional does not perform this service.");
        }
        Instant now = clock.instant();
        Caller at = prepared.at();
        changes.endRunOutHolds(
                booked.memberId(),
                prepared.occupation().startsAt(),
                prepared.occupation().endsAt(),
                at.salon().timeZone(),
                now);
        Checked request = prepared.request();
        Booking booking = Booking.hold(
                at.branch(),
                freeReference(),
                booked,
                new Booking.Request(
                        request.customerName(),
                        request.customerMobile(),
                        request.channel(),
                        request.key(),
                        request.fingerprint(),
                        at.staffId()),
                prepared.startsAt(),
                now);
        bookings.saveAndFlush(booking);
        changes.placed(booking, at.staffId(), now);
        return booking;
    }

    /** A reference this salon does not use yet. The unique constraint still decides between two drawn at once. */
    private String freeReference() {
        String reference = BookingReference.draw(random);
        while (bookings.referenceTaken(reference)) {
            reference = BookingReference.draw(random);
        }
        return reference;
    }

    /** A request with the same key committed while this one waited for the locks. */
    private static final class Replay extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        Replay() {
            super(null, null, false, false);
        }
    }
}
