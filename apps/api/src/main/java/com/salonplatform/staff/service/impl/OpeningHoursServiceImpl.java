package com.salonplatform.staff.service.impl;

import com.salonplatform.platform.PublishedHoliday;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import com.salonplatform.staff.AffectedBooking;
import com.salonplatform.staff.BookingsOnDate;
import com.salonplatform.staff.OpeningDay;
import com.salonplatform.staff.OpeningReason;
import com.salonplatform.staff.WeekdayHours;
import com.salonplatform.staff.domain.LocalTimes;
import com.salonplatform.staff.entity.OpeningHours;
import com.salonplatform.staff.repository.OpeningHoursRepository;
import com.salonplatform.staff.service.OpeningHoursService;
import com.salonplatform.staff.service.impl.StaffScope.Scope;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A save takes the branch's row lock first and checks the version it was sent; a branch's first save inserts
 * the row, and the unique branch key turns a second simultaneous first save into a {@code conflict}. The
 * bookings the hours leave outside are asked after the transaction, as {@link BookingsOnDate} requires.
 */
@Service
class OpeningHoursServiceImpl implements OpeningHoursService {

    /** How far ahead the public exceptions reach, from the salon's today. */
    static final int EXCEPTION_DAYS = 180;

    private static final String SUBJECT = "salon_opening_hours";

    private final StaffScope scope;
    private final OpeningHoursRepository hours;
    private final OpeningCalendar calendar;
    private final BookingsOnDate bookings;
    private final StaffAudit audit;
    private final Clock clock;
    private final TransactionTemplate writing;
    private final TransactionTemplate reading;

    OpeningHoursServiceImpl(
            StaffScope scope,
            OpeningHoursRepository hours,
            OpeningCalendar calendar,
            BookingsOnDate bookings,
            StaffAudit audit,
            Clock clock,
            PlatformTransactionManager transactions) {
        this.scope = scope;
        this.hours = hours;
        this.calendar = calendar;
        this.bookings = bookings;
        this.audit = audit;
        this.clock = clock;
        this.writing = new TransactionTemplate(transactions);
        this.reading = new TransactionTemplate(transactions);
        this.reading.setReadOnly(true);
    }

    @Override
    public PublicHours publicHours() {
        Scope at = scope.publicly();
        LocalDate last = at.today().plusDays(EXCEPTION_DAYS);
        Map<LocalDate, PublishedHoliday> holidays = scope.holidays(at.today(), last);
        return reading.execute(status -> {
            List<WeekdayHours> week =
                    hours.findByBranch(at.branch()).map(OpeningHours::week).orElse(null);
            List<OpeningDay> exceptions = calendar.between(at.branch(), at.today(), last, holidays).stream()
                    .filter(day -> day.reason() == OpeningReason.CLOSURE || day.reason() == OpeningReason.HOLIDAY)
                    .toList();
            return new PublicHours(week, exceptions);
        });
    }

    @Override
    public HoursSettings settings() {
        Scope at = scope.of(StaffScope.READ);
        Optional<OpeningHours> saved = reading.execute(status -> hours.findByBranch(at.branch()));
        return answer(
                at,
                saved.map(OpeningHours::getVersion).orElse(0L),
                saved.map(OpeningHours::week).orElse(null));
    }

    @Override
    public HoursSettings save(Long version, List<DayInput> weekly) {
        Scope at = scope.of(StaffScope.MANAGE);
        List<WeekdayHours> week = valid(weekly);
        if (version == null || version < 0) {
            throw new ApplicationProblemException(
                    400, ErrorCode.BAD_REQUEST, List.of(new FieldProblem("version", "must be zero or more")));
        }
        long saved = writing.execute(status -> {
            Instant now = clock.instant();
            Optional<OpeningHours> existing = hours.lockByBranch(at.branch());
            List<String> changed;
            OpeningHours row;
            if (existing.isEmpty()) {
                if (version != 0) {
                    throw conflict();
                }
                row = OpeningHours.first(at.branch(), week, now);
                try {
                    hours.persist(row);
                    hours.flush();
                } catch (DataIntegrityViolationException ex) {
                    throw conflict();
                }
                changed = week.stream().map(day -> LocalTimes.name(day.day())).toList();
            } else {
                row = existing.get();
                if (row.getVersion() != version) {
                    throw conflict();
                }
                changed = changedDays(row.week(), week);
                row.replace(week, now);
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("changedDays", changed);
            details.put("version", row.getVersion());
            audit.record(at.salon(), at.actor(), "staff.opening_hours_saved", SUBJECT, details, now);
            return row.getVersion();
        });
        return answer(at, saved, week);
    }

    private HoursSettings answer(Scope at, long version, List<WeekdayHours> week) {
        List<AffectedBooking> outside = week == null ? List.of() : bookings.outsideHours(at.branch(), week, at.today());
        return new HoursSettings(version, week, outside);
    }

    private static ApplicationProblemException conflict() {
        return new ApplicationProblemException(409, ErrorCode.CONFLICT);
    }

    private static List<String> changedDays(List<WeekdayHours> before, List<WeekdayHours> after) {
        List<String> changed = new ArrayList<>();
        for (int i = 0; i < after.size(); i++) {
            if (!after.get(i).equals(before.get(i))) {
                changed.add(LocalTimes.name(after.get(i).day()));
            }
        }
        return changed;
    }

    /** The week, Monday first, or 400 naming each bad field. */
    static List<WeekdayHours> valid(List<DayInput> weekly) {
        List<FieldProblem> errors = new ArrayList<>();
        if (weekly == null || weekly.size() != 7) {
            throw new ApplicationProblemException(
                    400,
                    ErrorCode.BAD_REQUEST,
                    List.of(new FieldProblem("weekly", "must give each of the seven weekdays once")));
        }
        Map<DayOfWeek, WeekdayHours> byDay = new EnumMap<>(DayOfWeek.class);
        for (int i = 0; i < weekly.size(); i++) {
            DayInput input = weekly.get(i);
            String at = "weekly[" + i + "]";
            if (input == null) {
                errors.add(new FieldProblem(at, "is required"));
                continue;
            }
            DayOfWeek day = LocalTimes.weekday(input.day());
            if (day == null) {
                errors.add(new FieldProblem(at + ".day", "must be a weekday, monday to sunday"));
            } else if (byDay.containsKey(day)) {
                errors.add(new FieldProblem(at + ".day", "is given twice"));
            }
            if (input.open() == null) {
                errors.add(new FieldProblem(at + ".open", "is required: true or false"));
                continue;
            }
            LocalTime opens = null;
            LocalTime closes = null;
            if (input.open()) {
                opens = LocalTimes.quarterHour(input.opens());
                closes = LocalTimes.quarterHour(input.closes());
                if (opens == null) {
                    errors.add(new FieldProblem(at + ".opens", "must be a time on a quarter hour, HH:MM"));
                }
                if (closes == null) {
                    errors.add(new FieldProblem(at + ".closes", "must be a time on a quarter hour, HH:MM"));
                } else if (opens != null && !closes.isAfter(opens)) {
                    errors.add(new FieldProblem(at + ".closes", "must be after the opening time"));
                }
            } else {
                if (input.opens() != null) {
                    errors.add(new FieldProblem(at + ".opens", "must be empty when the day is closed"));
                }
                if (input.closes() != null) {
                    errors.add(new FieldProblem(at + ".closes", "must be empty when the day is closed"));
                }
            }
            if (day != null && !byDay.containsKey(day)) {
                byDay.put(day, new WeekdayHours(day, opens, closes));
            }
        }
        if (!errors.isEmpty()) {
            throw new ApplicationProblemException(400, ErrorCode.BAD_REQUEST, errors);
        }
        return new ArrayList<>(byDay.values());
    }
}
