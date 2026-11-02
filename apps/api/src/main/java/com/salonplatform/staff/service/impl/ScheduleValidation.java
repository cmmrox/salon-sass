package com.salonplatform.staff.service.impl;

import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import com.salonplatform.staff.TimeWindow;
import com.salonplatform.staff.domain.LocalTimes;
import com.salonplatform.staff.domain.ScheduleRules;
import com.salonplatform.staff.domain.WorkDay;
import com.salonplatform.staff.service.MemberScheduleService.BreakInput;
import com.salonplatform.staff.service.MemberScheduleService.ChangeInput;
import com.salonplatform.staff.service.MemberScheduleService.DayInput;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * A week or a date change as a request sends it, checked against F06-S02's AC-2, AC-3 and AC-7: every broken rule is a
 * field error, and nothing is saved. The fields a broken rule names:
 *
 * <ul>
 *   <li>{@code <day>.starts} or {@code .finishes}: not a time on a quarter hour, or a finish not after the start;
 *   <li>{@code <day>.breaks[n].finishes}: a break that does not end after it starts, or not on a quarter hour;
 *   <li>{@code <day>.breaks[n].starts}: not on a quarter hour, or a break overlapping an earlier one;
 *   <li>{@code <day>.breaks[n]}: a break not inside the working hours;
 *   <li>{@code <day>.breaks}: more than five, or breaks that leave no time in the day.
 * </ul>
 */
final class ScheduleValidation {

    /** A working day has up to five breaks (AC-3). */
    static final int MAX_BREAKS = 5;

    /** A date change's note is up to 60 characters (AC-7). */
    static final int NOTE_MAX = 60;

    private static final String ON_A_QUARTER_HOUR = "must be a time on a quarter hour, HH:MM";

    /**
     * The checked change.
     *
     * @param hours the hours on the date, with the date's weekday
     * @param note the note, or null for none
     */
    record ValidChange(LocalDate date, WorkDay hours, String note) {}

    private ScheduleValidation() {}

    /** The seven weekdays, Monday first, each with its breaks in time order, or 400 naming each bad field. */
    static List<WorkDay> week(List<DayInput> weekly) {
        if (weekly == null || weekly.size() != 7) {
            throw new ApplicationProblemException(
                    400,
                    ErrorCode.BAD_REQUEST,
                    List.of(new FieldProblem("weekly", "must give each of the seven weekdays once")));
        }
        List<FieldProblem> errors = new ArrayList<>();
        Map<DayOfWeek, WorkDay> byDay = new EnumMap<>(DayOfWeek.class);
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
            WorkDay hours = hours(
                    at + ".",
                    day == null ? DayOfWeek.MONDAY : day,
                    input.working(),
                    input.starts(),
                    input.finishes(),
                    input.breaks(),
                    errors);
            if (day != null && !byDay.containsKey(day)) {
                byDay.put(day, hours);
            }
        }
        if (!errors.isEmpty()) {
            throw new ApplicationProblemException(400, ErrorCode.BAD_REQUEST, errors);
        }
        return new ArrayList<>(byDay.values());
    }

    /** The change, or 400 naming each bad field. {@code today} is the salon's. */
    static ValidChange change(ChangeInput input, LocalDate today) {
        List<FieldProblem> errors = new ArrayList<>();
        LocalDate date = null;
        try {
            date = ClosureServiceImpl.closable(input.date(), today);
        } catch (ApplicationProblemException ex) {
            errors.addAll(ex.errors());
        }
        WorkDay hours = hours(
                "",
                date == null ? DayOfWeek.MONDAY : date.getDayOfWeek(),
                input.working(),
                input.starts(),
                input.finishes(),
                input.breaks(),
                errors);
        String note = input.note() == null ? null : input.note().strip();
        if (note != null && note.isEmpty()) {
            note = null;
        }
        if (note != null
                && (note.codePointCount(0, note.length()) > NOTE_MAX
                        || note.chars().anyMatch(Character::isISOControl))) {
            errors.add(new FieldProblem("note", "must be at most " + NOTE_MAX + " characters of plain text"));
        }
        if (!errors.isEmpty()) {
            throw new ApplicationProblemException(400, ErrorCode.BAD_REQUEST, errors);
        }
        return new ValidChange(date, hours, note);
    }

    /** One day's hours. A day not worked keeps no breaks, whatever was sent (AC-3), and no times may be sent for it. */
    private static WorkDay hours(
            String at,
            DayOfWeek day,
            Boolean working,
            String startsText,
            String finishesText,
            List<BreakInput> breaks,
            List<FieldProblem> errors) {
        if (working == null) {
            errors.add(new FieldProblem(at + "working", "is required: true or false"));
            return WorkDay.off(day);
        }
        if (!working) {
            if (startsText != null) {
                errors.add(new FieldProblem(at + "starts", "must be empty when not working"));
            }
            if (finishesText != null) {
                errors.add(new FieldProblem(at + "finishes", "must be empty when not working"));
            }
            return WorkDay.off(day);
        }
        LocalTime starts = LocalTimes.quarterHour(startsText);
        LocalTime finishes = LocalTimes.quarterHour(finishesText);
        if (starts == null) {
            errors.add(new FieldProblem(at + "starts", ON_A_QUARTER_HOUR));
        }
        if (finishes == null) {
            errors.add(new FieldProblem(at + "finishes", ON_A_QUARTER_HOUR));
        } else if (starts != null && !finishes.isAfter(starts)) {
            errors.add(new FieldProblem(at + "finishes", "must be after the start"));
        }
        boolean hoursValid = starts != null && finishes != null && finishes.isAfter(starts);
        List<TimeWindow> valid = breaks(at, breaks, hoursValid ? starts : null, hoursValid ? finishes : null, errors);
        if (hoursValid
                && !valid.isEmpty()
                && ScheduleRules.windows(starts, finishes, valid).isEmpty()) {
            errors.add(new FieldProblem(at + "breaks", "must leave some of the day to work"));
        }
        return new WorkDay(day, starts, finishes, valid);
    }

    /** The breaks that are well formed, in time order; with both hours given, also inside them. */
    private static List<TimeWindow> breaks(
            String at, List<BreakInput> breaks, LocalTime starts, LocalTime finishes, List<FieldProblem> errors) {
        if (breaks == null) {
            return List.of();
        }
        if (breaks.size() > MAX_BREAKS) {
            errors.add(new FieldProblem(at + "breaks", "must be at most " + MAX_BREAKS));
            return List.of();
        }
        record Indexed(int index, TimeWindow window) {}
        List<Indexed> valid = new ArrayList<>();
        for (int j = 0; j < breaks.size(); j++) {
            String here = at + "breaks[" + j + "]";
            BreakInput input = breaks.get(j);
            if (input == null) {
                errors.add(new FieldProblem(here, "is required"));
                continue;
            }
            LocalTime breakStarts = LocalTimes.quarterHour(input.starts());
            LocalTime breakFinishes = LocalTimes.quarterHour(input.finishes());
            if (breakStarts == null) {
                errors.add(new FieldProblem(here + ".starts", ON_A_QUARTER_HOUR));
            }
            if (breakFinishes == null) {
                errors.add(new FieldProblem(here + ".finishes", ON_A_QUARTER_HOUR));
            }
            if (breakStarts == null || breakFinishes == null) {
                continue;
            }
            if (!breakFinishes.isAfter(breakStarts)) {
                errors.add(new FieldProblem(here + ".finishes", "must be after the break starts"));
                continue;
            }
            if (starts != null && (breakStarts.isBefore(starts) || breakFinishes.isAfter(finishes))) {
                errors.add(new FieldProblem(here, "must be inside the working hours"));
                continue;
            }
            valid.add(new Indexed(j, new TimeWindow(breakStarts, breakFinishes)));
        }
        valid.sort(Comparator.comparing(indexed -> indexed.window().starts()));
        for (int k = 1; k < valid.size(); k++) {
            if (valid.get(k)
                    .window()
                    .starts()
                    .isBefore(valid.get(k - 1).window().finishes())) {
                errors.add(new FieldProblem(
                        at + "breaks[" + valid.get(k).index() + "].starts", "must not overlap another break"));
            }
        }
        return valid.stream().map(Indexed::window).toList();
    }
}
