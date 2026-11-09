package com.salonplatform.booking.service.impl;

import com.salonplatform.booking.config.BookingSettings;
import com.salonplatform.booking.domain.BookingAction;
import com.salonplatform.booking.domain.BookingReference;
import com.salonplatform.booking.domain.BookingState;
import com.salonplatform.booking.domain.BookingStateMachine;
import com.salonplatform.booking.domain.CancellationReason;
import com.salonplatform.booking.domain.CustomerMobile;
import com.salonplatform.booking.entity.Booking;
import com.salonplatform.booking.entity.BookingStateChange;
import com.salonplatform.booking.repository.BookingRepository;
import com.salonplatform.booking.repository.BookingStateChangeRepository;
import com.salonplatform.booking.service.BookingReadService;
import com.salonplatform.booking.service.impl.BookingCallers.Caller;
import com.salonplatform.identity.StaffLabels;
import com.salonplatform.shared.time.SalonTime;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import com.salonplatform.shared.web.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The booking reads (AC-11). A list covers local dates {@code from} to {@code to}, both included, at most 31 of them,
 * as instants from the salon's midnight at the start of {@code from} to its midnight after {@code to}. A search matches
 * part of the customer's name regardless of case, the mobile number in any form the region knows, or the reference
 * with or without its hyphen and regardless of case. Authors are named through identity's {@link StaffLabels} after
 * the transaction ends.
 */
@Service
class BookingReadServiceImpl implements BookingReadService {

    static final int MAX_DAYS = 31;
    static final int DEFAULT_SIZE = 50;
    static final int MAX_SIZE = 100;
    static final int MAX_SEARCH = 60;

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    private final BookingCallers callers;
    private final BookingRepository bookings;
    private final BookingStateChangeRepository history;
    private final StaffLabels labels;
    private final BookingSettings settings;
    private final Clock clock;
    private final TransactionTemplate reading;

    BookingReadServiceImpl(
            BookingCallers callers,
            BookingRepository bookings,
            BookingStateChangeRepository history,
            StaffLabels labels,
            BookingSettings settings,
            Clock clock,
            PlatformTransactionManager transactions) {
        this.callers = callers;
        this.bookings = bookings;
        this.history = history;
        this.labels = labels;
        this.settings = settings;
        this.clock = clock;
        this.reading = new TransactionTemplate(transactions);
        this.reading.setReadOnly(true);
    }

    @Override
    public BookingPage page(ListQuery query) {
        Caller at = callers.of(BookingCallers.READ);
        List<FieldProblem> errors = new ArrayList<>();
        LocalDate from = date(query.from(), "from", errors);
        LocalDate to = date(query.to(), "to", errors);
        if (from != null && to != null && (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_DAYS)) {
            errors.add(new FieldProblem("to", "must be from `from` to 30 days after it"));
        }
        Set<String> states = states(query.states(), errors);
        UUID member = memberOf(query.memberId(), errors);
        String q = query.q() == null ? "" : query.q().strip();
        if (q.length() > MAX_SEARCH) {
            errors.add(new FieldProblem("q", "must be at most 60 characters"));
        }
        int page = number(query.page(), "page", 0, 0, Integer.MAX_VALUE, errors);
        int size = number(query.size(), "size", DEFAULT_SIZE, 1, MAX_SIZE, errors);
        if (!errors.isEmpty()) {
            throw new ApplicationProblemException(400, ErrorCode.BAD_REQUEST, errors);
        }
        if (at.readsOwnOnly()) {
            if (at.ownMember().isEmpty()
                    || (member != null && !member.equals(at.ownMember().get()))) {
                return new BookingPage(List.of(), page, size, 0);
            }
            member = at.ownMember().get();
        }
        ZoneId zone = at.salon().timeZone();
        Instant start = SalonTime.toInstant(Objects.requireNonNull(from).atStartOfDay(), zone);
        Instant until =
                SalonTime.toInstant(Objects.requireNonNull(to).plusDays(1).atStartOfDay(), zone);
        boolean searching = !q.isEmpty();
        String namePattern = "%" + escaped(q.toLowerCase(Locale.ROOT)) + "%";
        String mobile =
                searching ? CustomerMobile.parse(q, settings.phoneRegion()).orElse(null) : null;
        String reference = searching ? BookingReference.searched(q).orElse(null) : null;
        UUID memberId = member;
        Page<Booking> found = reading.execute(status -> bookings.page(
                start,
                until,
                clock.instant(),
                states,
                memberId == null,
                memberId == null ? new UUID(0, 0) : memberId,
                searching,
                namePattern,
                mobile,
                reference,
                PageRequest.of(page, size)));
        return new BookingPage(
                Objects.requireNonNull(found).stream()
                        .map(booking -> BookingViews.of(booking, zone))
                        .toList(),
                page,
                size,
                found.getTotalElements());
    }

    @Override
    public BookingDetail detail(UUID id) {
        Caller at = callers.of(BookingCallers.READ);
        ZoneId zone = at.salon().timeZone();
        Instant now = clock.instant();
        record Read(Booking booking, List<BookingStateChange> changes) {}
        Read read = reading.execute(status -> {
            Booking booking = bookings.findBooking(id)
                    .filter(at::reads)
                    .filter(found -> BookingStateMachine.shown(found.situation(zone), now))
                    .orElseThrow(NotFoundException::new);
            return new Read(booking, history.of(id));
        });
        Booking booking = Objects.requireNonNull(read).booking();
        Set<UUID> authors = new LinkedHashSet<>();
        read.changes().stream()
                .map(BookingStateChange::getActorId)
                .filter(Objects::nonNull)
                .forEach(authors::add);
        Map<UUID, String> named = authors.isEmpty() ? Map.of() : labels.labels(authors);
        List<HistoryView> entries = read.changes().stream()
                .map(change -> new HistoryView(
                        change.getChangedAt(),
                        change.from().orElse(null),
                        change.to(),
                        change.getActorId(),
                        change.getActorId() == null ? null : named.get(change.getActorId()),
                        change.getReason() == null
                                ? null
                                : CancellationReason.parse(change.getReason()).orElseThrow(),
                        change.getNote()))
                .toList();
        List<BookingAction> allowed = BookingAction.STAFF.stream()
                .filter(action -> at.reaches(action, booking) && at.mayTake(action, booking))
                .filter(action -> BookingStateMachine.decide(action, booking.situation(zone), now)
                        instanceof BookingStateMachine.Allowed)
                .toList();
        return new BookingDetail(BookingViews.of(booking, zone), entries, allowed);
    }

    private static LocalDate date(String text, String field, List<FieldProblem> errors) {
        if (text != null) {
            try {
                return LocalDate.parse(text, DATE);
            } catch (DateTimeParseException ex) {
                // Named below.
            }
        }
        errors.add(new FieldProblem(field, "must be a date, YYYY-MM-DD"));
        return null;
    }

    private static Set<String> states(List<String> asked, List<FieldProblem> errors) {
        Set<String> states = new LinkedHashSet<>();
        if (asked == null || asked.isEmpty()) {
            BookingState.SHOWN.forEach(state -> states.add(state.value()));
            return states;
        }
        for (String each : asked) {
            Optional<BookingState> state = BookingState.parseShown(each);
            if (state.isEmpty()) {
                errors.add(new FieldProblem("state", "must be one of the booking states"));
                return states;
            }
            states.add(state.get().value());
        }
        return states;
    }

    private static UUID memberOf(String text, List<FieldProblem> errors) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            UUID id = UUID.fromString(text);
            if (id.toString().equalsIgnoreCase(text)) {
                return id;
            }
        } catch (IllegalArgumentException ex) {
            // Named below.
        }
        errors.add(new FieldProblem("memberId", "must be a UUID"));
        return null;
    }

    private static int number(String text, String field, int otherwise, int min, int max, List<FieldProblem> errors) {
        if (text == null || text.isEmpty()) {
            return otherwise;
        }
        try {
            int value = Integer.parseInt(text);
            if (value >= min && value <= max) {
                return value;
            }
        } catch (NumberFormatException ex) {
            // Named below.
        }
        errors.add(new FieldProblem(field, "must be a whole number from " + min + " to " + max));
        return otherwise;
    }

    /** A {@code like} pattern's own characters, taken literally. */
    private static String escaped(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
