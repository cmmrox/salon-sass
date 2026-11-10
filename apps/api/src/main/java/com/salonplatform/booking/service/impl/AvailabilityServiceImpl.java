package com.salonplatform.booking.service.impl;

import com.salonplatform.booking.Availability;
import com.salonplatform.booking.Availability.Answer;
import com.salonplatform.booking.Availability.Audience;
import com.salonplatform.booking.service.AvailabilityService;
import com.salonplatform.booking.service.impl.BookingCallers.Caller;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Checks the caller and the query, then asks {@link Availability} for staff. Dates are the salon's: "today" is the
 * salon's date by the application's clock.
 */
@Service
class AvailabilityServiceImpl implements AvailabilityService {

    /** The most days the open-days read answers at once. */
    static final int MAX_OPEN_DAYS = 400;

    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    private final BookingCallers callers;
    private final Availability availability;
    private final Clock clock;

    AvailabilityServiceImpl(BookingCallers callers, Availability availability, Clock clock) {
        this.callers = callers;
        this.availability = availability;
        this.clock = clock;
    }

    @Override
    public Times freeTimes(String serviceId, String variantId, String date, String memberId) {
        Caller at = callers.of(BookingCallers.MANAGE);
        LocalDate today = clock.instant().atZone(at.salon().timeZone()).toLocalDate();
        List<FieldProblem> errors = new ArrayList<>();
        UUID service = id(serviceId, "serviceId", true, errors);
        UUID variant = id(variantId, "variantId", false, errors);
        UUID member = id(memberId, "memberId", false, errors);
        LocalDate day = date(date, "date", errors);
        if (day != null && (day.isBefore(today) || day.isAfter(today.plusYears(1)))) {
            errors.add(new FieldProblem("date", "must be from today to one year ahead"));
        }
        refuse(errors);
        Answer answer = availability.freeTimes(new Availability.Query(service, variant, day, member, Audience.STAFF));
        return new Times(answer, at.salon().timeZone());
    }

    @Override
    public List<LocalDate> openDays(String from, String to) {
        Caller at = callers.of(BookingCallers.MANAGE);
        LocalDate today = clock.instant().atZone(at.salon().timeZone()).toLocalDate();
        List<FieldProblem> errors = new ArrayList<>();
        LocalDate first = date(from, "from", errors);
        LocalDate last = date(to, "to", errors);
        if (first != null && first.isBefore(today)) {
            errors.add(new FieldProblem("from", "must be today or later"));
        }
        if (first != null && last != null) {
            if (last.isBefore(first)) {
                errors.add(new FieldProblem("to", "must not be before from"));
            } else if (ChronoUnit.DAYS.between(first, last) + 1 > MAX_OPEN_DAYS) {
                errors.add(
                        new FieldProblem("to", "must be at most " + MAX_OPEN_DAYS + " days from from, both included"));
            }
        }
        refuse(errors);
        return availability.openDays(first, last);
    }

    private static void refuse(List<FieldProblem> errors) {
        if (!errors.isEmpty()) {
            throw new ApplicationProblemException(400, ErrorCode.BAD_REQUEST, errors);
        }
    }

    private static UUID id(String text, String field, boolean required, List<FieldProblem> errors) {
        if (text == null || text.isEmpty()) {
            if (required) {
                errors.add(new FieldProblem(field, "is required"));
            }
            return null;
        }
        if (!UUID_TEXT.matcher(text).matches()) {
            errors.add(new FieldProblem(field, "must be a UUID"));
            return null;
        }
        return UUID.fromString(text);
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
}
