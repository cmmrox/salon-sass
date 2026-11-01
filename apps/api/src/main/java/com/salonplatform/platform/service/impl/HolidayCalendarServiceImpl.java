package com.salonplatform.platform.service.impl;

import com.salonplatform.platform.HolidayCalendar;
import com.salonplatform.platform.domain.HolidayDays;
import com.salonplatform.platform.domain.HolidayDays.Day;
import com.salonplatform.platform.domain.OperatorPermission;
import com.salonplatform.platform.dto.HolidayDayView;
import com.salonplatform.platform.dto.HolidayDraftDayView;
import com.salonplatform.platform.dto.HolidayRevisionCounts;
import com.salonplatform.platform.dto.HolidayYearDetail;
import com.salonplatform.platform.dto.HolidayYearList;
import com.salonplatform.platform.dto.HolidayYearSummary;
import com.salonplatform.platform.repository.HolidayCalendarStore;
import com.salonplatform.platform.repository.HolidayCalendarStore.PublishedDay;
import com.salonplatform.platform.repository.HolidayCalendarStore.Year;
import com.salonplatform.platform.repository.PlatformAudit;
import com.salonplatform.platform.service.HolidayCalendarService;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import com.salonplatform.shared.web.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Each change takes the year's row lock first, so that imports, edits and publications of one year happen one
 * at a time, and two Admins publishing the same version see one publication and one {@code conflict}.
 */
@Service
class HolidayCalendarServiceImpl implements HolidayCalendarService {

    private static final String SUBJECT = "holiday_year";

    private final HolidayCalendarStore store;
    private final OperatorWork work;
    private final OperatorAccess access;
    private final PlatformAudit audit;
    private final Clock clock;

    HolidayCalendarServiceImpl(
            HolidayCalendarStore store, OperatorWork work, OperatorAccess access, PlatformAudit audit, Clock clock) {
        this.store = store;
        this.work = work;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    public HolidayYearList years() {
        return work.run(() -> {
            access.require(OperatorPermission.HOLIDAY_READ);
            return new HolidayYearList(store.years().stream()
                    .map(year -> {
                        Revision revision = revision(store.draftDays(year.year()), store.publishedDays(year.year()));
                        return new HolidayYearSummary(
                                year.year(),
                                state(year, revision),
                                year.revision(),
                                year.draftDays(),
                                year.publishedAt());
                    })
                    .toList());
        });
    }

    @Override
    public HolidayYearDetail year(int year) {
        return work.run(() -> {
            access.require(OperatorPermission.HOLIDAY_READ);
            return detail(store.findYear(known(year)).orElseThrow(NotFoundException::new));
        });
    }

    @Override
    public HolidayYearDetail importFile(int year, byte[] file) {
        return work.run(() -> {
            var operator = access.require(OperatorPermission.HOLIDAY_MANAGE);
            known(year);
            List<Day> days = switch (HolidayDays.read(file, year)) {
                case HolidayDays.Read read -> read.days();
                case HolidayDays.Refused refused ->
                    throw new ApplicationProblemException(
                            400,
                            ErrorCode.BAD_REQUEST,
                            refused.lines().stream()
                                    .map(line -> new FieldProblem("file", line.reason(), line.line()))
                                    .toList());
            };
            Instant now = clock.instant();
            Year locked = startAndLock(year, now);
            store.replaceDraft(year, days, now);
            store.draftChanged(year, now);
            audit.record(
                    PlatformAudit.OPERATOR,
                    operator.id().toString(),
                    "holiday_calendar.imported",
                    SUBJECT,
                    String.valueOf(year),
                    Map.of("days", days.size(), "version", locked.version() + 1),
                    now);
            return detail(store.findYear(year).orElseThrow());
        });
    }

    @Override
    public HolidayYearDetail addDay(int year, DayInput input) {
        return work.run(() -> {
            var operator = access.require(OperatorPermission.HOLIDAY_MANAGE);
            known(year);
            Day day = valid(year, input.date(), input);
            Instant now = clock.instant();
            startAndLock(year, now);
            if (!store.addDraftDay(year, day, now)) {
                throw new ApplicationProblemException(409, ErrorCode.CONFLICT);
            }
            return changed(operator.id(), year, "holiday_calendar.day_added", day.date(), now);
        });
    }

    @Override
    public HolidayYearDetail changeDay(int year, String date, DayInput input) {
        return work.run(() -> {
            var operator = access.require(OperatorPermission.HOLIDAY_MANAGE);
            known(year);
            LocalDate on = pathDate(year, date);
            Day day = valid(year, on.toString(), input);
            Instant now = clock.instant();
            store.lockYear(year).orElseThrow(NotFoundException::new);
            if (!store.changeDraftDay(year, day, now)) {
                throw new NotFoundException();
            }
            return changed(operator.id(), year, "holiday_calendar.day_changed", on, now);
        });
    }

    @Override
    public HolidayYearDetail removeDay(int year, String date) {
        return work.run(() -> {
            var operator = access.require(OperatorPermission.HOLIDAY_MANAGE);
            known(year);
            LocalDate on = pathDate(year, date);
            Instant now = clock.instant();
            store.lockYear(year).orElseThrow(NotFoundException::new);
            if (!store.removeDraftDay(year, on)) {
                throw new NotFoundException();
            }
            return changed(operator.id(), year, "holiday_calendar.day_removed", on, now);
        });
    }

    @Override
    public HolidayYearDetail publish(int year, long version) {
        return work.run(() -> {
            var operator = access.require(OperatorPermission.HOLIDAY_MANAGE);
            known(year);
            Year locked = store.lockYear(year).orElseThrow(NotFoundException::new);
            if (locked.version() != version) {
                throw new ApplicationProblemException(409, ErrorCode.CONFLICT);
            }
            Revision revision = revision(store.draftDays(year), store.publishedDays(year));
            if (revision.isEmpty()) {
                throw new ApplicationProblemException(409, ErrorCode.INVALID_STATE);
            }
            Instant now = clock.instant();
            int next = locked.revision() + 1;
            store.publish(
                    year,
                    next,
                    operator.id(),
                    revision.added(),
                    revision.changed(),
                    revision.withdrawn().stream().map(PublishedDay::date).toList(),
                    now);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("revision", next);
            details.put("added", revision.added().size());
            details.put("changed", revision.changed().size());
            details.put("withdrawn", revision.withdrawn().size());
            audit.record(
                    PlatformAudit.OPERATOR,
                    operator.id().toString(),
                    "holiday_calendar.published",
                    SUBJECT,
                    String.valueOf(year),
                    details,
                    now);
            return detail(store.findYear(year).orElseThrow());
        });
    }

    // --- helpers ---------------------------------------------------------------------------------------------

    private HolidayYearDetail changed(java.util.UUID operator, int year, String action, LocalDate date, Instant now) {
        store.draftChanged(year, now);
        audit.record(
                PlatformAudit.OPERATOR,
                operator.toString(),
                action,
                SUBJECT,
                String.valueOf(year),
                Map.of("date", date.toString()),
                now);
        return detail(store.findYear(year).orElseThrow());
    }

    private Year startAndLock(int year, Instant now) {
        store.startYear(year, now);
        return store.lockYear(year).orElseThrow();
    }

    private static int known(int year) {
        if (year < HolidayCalendar.FIRST_YEAR || year > HolidayCalendar.LAST_YEAR) {
            throw new NotFoundException();
        }
        return year;
    }

    /** A date in the path: a date of the year, or the day is not found. */
    private static LocalDate pathDate(int year, String date) {
        LocalDate on = HolidayDays.date(date);
        if (on == null || on.getYear() != year) {
            throw new NotFoundException();
        }
        return on;
    }

    private static Day valid(int year, String date, DayInput input) {
        List<FieldProblem> errors = new ArrayList<>();
        LocalDate on = HolidayDays.date(date);
        if (on == null) {
            errors.add(new FieldProblem("date", "must be a date written YYYY-MM-DD"));
        } else if (on.getYear() != year) {
            errors.add(new FieldProblem("date", "must be in " + year));
        }
        String name = HolidayDays.name(input.name());
        if (name == null) {
            errors.add(new FieldProblem("name", "must be 1 to " + HolidayDays.NAME_MAX + " characters"));
        }
        String kind = HolidayDays.kind(input.kind());
        if (kind == null) {
            errors.add(new FieldProblem("kind", "must be one of poya, public, bank or mercantile"));
        }
        if (!errors.isEmpty()) {
            throw new ApplicationProblemException(400, ErrorCode.BAD_REQUEST, errors);
        }
        return new Day(on, name, kind);
    }

    /** What publishing the draft would do to the published days. */
    private record Revision(List<Day> added, List<Day> changed, List<Day> unchanged, List<PublishedDay> withdrawn) {
        boolean isEmpty() {
            return added.isEmpty() && changed.isEmpty() && withdrawn.isEmpty();
        }
    }

    private static Revision revision(List<Day> draft, List<PublishedDay> published) {
        Map<LocalDate, PublishedDay> byDate =
                published.stream().collect(Collectors.toMap(PublishedDay::date, Function.identity()));
        List<Day> added = new ArrayList<>();
        List<Day> changed = new ArrayList<>();
        List<Day> unchanged = new ArrayList<>();
        for (Day day : draft) {
            PublishedDay was = byDate.remove(day.date());
            if (was == null) {
                added.add(day);
            } else if (!was.name().equals(day.name()) || !was.kind().equals(day.kind())) {
                changed.add(day);
            } else {
                unchanged.add(day);
            }
        }
        List<PublishedDay> withdrawn = byDate.values().stream()
                .sorted(java.util.Comparator.comparing(PublishedDay::date))
                .toList();
        return new Revision(added, changed, unchanged, withdrawn);
    }

    private static String state(Year year, Revision revision) {
        if (year.revision() == 0) {
            return "draft";
        }
        return revision.isEmpty() ? "published" : "changed";
    }

    private HolidayYearDetail detail(Year year) {
        List<Day> draft = store.draftDays(year.year());
        Revision revision = revision(draft, store.publishedDays(year.year()));
        List<HolidayDraftDayView> days = draft.stream()
                .map(day -> new HolidayDraftDayView(
                        day.date(),
                        day.name(),
                        day.kind(),
                        revision.added().contains(day)
                                ? "added"
                                : revision.changed().contains(day) ? "changed" : "unchanged"))
                .toList();
        return new HolidayYearDetail(
                year.year(),
                state(year, revision),
                year.revision(),
                year.version(),
                year.publishedAt(),
                days,
                revision.withdrawn().stream()
                        .map(day -> new HolidayDayView(day.date(), day.name(), day.kind()))
                        .toList(),
                new HolidayRevisionCounts(
                        revision.added().size(),
                        revision.changed().size(),
                        revision.withdrawn().size()));
    }
}
