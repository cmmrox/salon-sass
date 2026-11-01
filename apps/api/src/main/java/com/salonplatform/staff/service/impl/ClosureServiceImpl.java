package com.salonplatform.staff.service.impl;

import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import com.salonplatform.shared.web.NotFoundException;
import com.salonplatform.staff.BookingsOnDate;
import com.salonplatform.staff.entity.Closure;
import com.salonplatform.staff.repository.ClosureRepository;
import com.salonplatform.staff.service.ClosureService;
import com.salonplatform.staff.service.impl.StaffScope.Scope;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A closure is one row per branch and date: the unique key keeps exactly one when two arrive at once, and the
 * second is a {@code conflict}. "Today" is the salon's, from the application's clock in its time zone. The
 * bookings on a date are asked outside the transaction, as {@link BookingsOnDate} requires. The reason is
 * kept for the team and never written to the audit trail or a log.
 */
@Service
class ClosureServiceImpl implements ClosureService {

    static final int REASON_MAX = 60;

    private static final String SUBJECT = "salon_closure";

    private final StaffScope scope;
    private final ClosureRepository closures;
    private final BookingsOnDate bookings;
    private final StaffAudit audit;
    private final Clock clock;
    private final TransactionTemplate writing;
    private final TransactionTemplate reading;

    ClosureServiceImpl(
            StaffScope scope,
            ClosureRepository closures,
            BookingsOnDate bookings,
            StaffAudit audit,
            Clock clock,
            PlatformTransactionManager transactions) {
        this.scope = scope;
        this.closures = closures;
        this.bookings = bookings;
        this.audit = audit;
        this.clock = clock;
        this.writing = new TransactionTemplate(transactions);
        this.reading = new TransactionTemplate(transactions);
        this.reading.setReadOnly(true);
    }

    @Override
    public List<ClosureView> upcoming() {
        Scope at = scope.of(StaffScope.READ);
        List<Closure> found = reading.execute(status ->
                closures.findBetween(at.branch(), at.today(), at.today().plusYears(2)));
        return found.stream().map(closure -> view(at, closure)).toList();
    }

    @Override
    public Impact impact(String date) {
        Scope at = scope.of(StaffScope.READ);
        LocalDate on = closable(date, at.today());
        return new Impact(on, bookings.on(at.branch(), on));
    }

    @Override
    public ClosureView close(String date, String reason) {
        Scope at = scope.of(StaffScope.MANAGE);
        LocalDate on;
        String note;
        List<FieldProblem> errors = new java.util.ArrayList<>();
        try {
            on = closable(date, at.today());
        } catch (ApplicationProblemException ex) {
            errors.addAll(ex.errors());
            on = null;
        }
        note = reason == null ? null : reason.strip();
        if (note != null && note.isEmpty()) {
            note = null;
        }
        if (note != null && (note.length() > REASON_MAX || note.chars().anyMatch(Character::isISOControl))) {
            errors.add(new FieldProblem("reason", "must be at most " + REASON_MAX + " characters of plain text"));
        }
        if (!errors.isEmpty()) {
            throw new ApplicationProblemException(400, ErrorCode.BAD_REQUEST, errors);
        }
        LocalDate day = on;
        String kept = note;
        Closure saved = writing.execute(status -> {
            Instant now = clock.instant();
            Closure closure = Closure.of(at.branch(), day, kept, at.actor().staffId(), now);
            try {
                closures.persist(closure);
                closures.flush();
            } catch (DataIntegrityViolationException ex) {
                throw new ApplicationProblemException(409, ErrorCode.CONFLICT);
            }
            audit.record(at.salon(), at.actor(), "staff.closure_added", SUBJECT, Map.of("date", day.toString()), now);
            return closure;
        });
        return view(at, saved);
    }

    @Override
    public void reopen(String id) {
        Scope at = scope.of(StaffScope.MANAGE);
        UUID closureId = uuid(id);
        writing.executeWithoutResult(status -> {
            Closure closure = closures.findById(closureId).orElseThrow(NotFoundException::new);
            if (closure.getClosureDate().isBefore(at.today())) {
                throw new ApplicationProblemException(409, ErrorCode.INVALID_STATE);
            }
            Instant now = clock.instant();
            closures.delete(closure);
            audit.record(
                    at.salon(),
                    at.actor(),
                    "staff.closure_reopened",
                    SUBJECT,
                    Map.of("date", closure.getClosureDate().toString()),
                    now);
        });
    }

    private ClosureView view(Scope at, Closure closure) {
        return new ClosureView(
                closure.getId(),
                closure.getClosureDate(),
                closure.getReason(),
                bookings.on(at.branch(), closure.getClosureDate()).size());
    }

    /** A date a closure may have: today to a year ahead, in the salon's time zone; else 400 on {@code date}. */
    static LocalDate closable(String date, LocalDate today) {
        LocalDate on = null;
        if (date != null && date.matches("\\d{4}-\\d{2}-\\d{2}")) {
            try {
                on = LocalDate.parse(date);
            } catch (DateTimeException ex) {
                on = null;
            }
        }
        String problem = on == null
                ? "must be a date written YYYY-MM-DD"
                : on.isBefore(today)
                        ? "must be today or later"
                        : on.isAfter(today.plusYears(1)) ? "must be at most a year ahead" : null;
        if (problem != null) {
            throw new ApplicationProblemException(
                    400, ErrorCode.BAD_REQUEST, List.of(new FieldProblem("date", problem)));
        }
        return on;
    }

    private static UUID uuid(String id) {
        try {
            if (id != null && id.length() == 36) {
                return UUID.fromString(id);
            }
        } catch (IllegalArgumentException ex) {
            // Not found below, as an id that names nothing.
        }
        throw new NotFoundException();
    }
}
