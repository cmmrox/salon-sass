package com.salonplatform.staff.domain;

import com.salonplatform.staff.MemberWorkingDay;
import com.salonplatform.staff.MemberWorkingDay.DecidedBy;
import com.salonplatform.staff.TimeWindow;
import com.salonplatform.staff.WeekdayHours;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * How a person's working time is worked out (STF-02, STF-03, STF-05, decisions 102 and 104). Pure. F07 trusts these windows,
 * so a stretch of time taken out is handled at every edge: touching the start or the finish, touching another, and, for
 * blocked time, overlapping another or lying outside the hours.
 */
public final class ScheduleRules {

    private ScheduleRules() {}

    /**
     * The hours with the taken stretches removed, in time order. Empty for a day not worked, and when nothing is left.
     *
     * @param starts when the hours start, or null for none
     * @param finishes when the hours finish, or null for none
     * @param taken the stretches to take out, in any order
     */
    public static List<TimeWindow> windows(LocalTime starts, LocalTime finishes, List<TimeWindow> taken) {
        if (starts == null || finishes == null) {
            return List.of();
        }
        List<TimeWindow> ordered = new ArrayList<>(taken);
        ordered.sort(Comparator.comparing(TimeWindow::starts));
        List<TimeWindow> windows = new ArrayList<>();
        LocalTime from = starts;
        for (TimeWindow stretch : ordered) {
            if (!stretch.starts().isBefore(finishes)) {
                break;
            }
            if (stretch.starts().isAfter(from)) {
                windows.add(new TimeWindow(from, stretch.starts()));
            }
            if (stretch.finishes().isAfter(from)) {
                from = stretch.finishes();
            }
        }
        if (from.isBefore(finishes)) {
            windows.add(new TimeWindow(from, finishes));
        }
        return List.copyOf(windows);
    }

    /** A day's windows: its hours with its breaks taken out. */
    public static List<TimeWindow> windows(WorkDay day) {
        return windows(day.starts(), day.finishes(), day.breaks());
    }

    /** The windows of each of the seven weekdays. */
    public static Map<DayOfWeek, List<TimeWindow>> windowsByDay(List<WorkDay> week) {
        Map<DayOfWeek, List<TimeWindow>> byDay = new EnumMap<>(DayOfWeek.class);
        for (WorkDay day : week) {
            byDay.put(day.day(), windows(day));
        }
        return byDay;
    }

    /**
     * The answer for one date. Leave beats everything. Otherwise a change replaces the weekly hours on that date, both the
     * times and the breaks, and blocked time is taken out of what is left; a day with no time left is not worked, so that
     * working always has a window (decision 104(d)).
     *
     * @param weekly the person's hours for the date's weekday
     * @param change the hours of the date's change, or null when it has none
     * @param onLeave whether the date is within their leave
     * @param blocks their blocked time on the date, in any order
     */
    public static MemberWorkingDay decide(
            LocalDate date, WorkDay weekly, WorkDay change, boolean onLeave, List<TimeWindow> blocks) {
        if (onLeave) {
            return new MemberWorkingDay(date, false, List.of(), DecidedBy.LEAVE);
        }
        WorkDay day = change != null ? change : weekly;
        List<TimeWindow> taken = new ArrayList<>(day.breaks());
        taken.addAll(blocks);
        List<TimeWindow> left = windows(day.starts(), day.finishes(), taken);
        DecidedBy decidedBy = !left.equals(windows(day))
                ? DecidedBy.BLOCKED_TIME
                : change != null ? DecidedBy.DATE_CHANGE : DecidedBy.WEEK;
        return new MemberWorkingDay(date, !left.isEmpty(), left, decidedBy);
    }

    /** A week of no working days, Monday first. */
    public static List<WorkDay> noWorkingDays() {
        List<WorkDay> week = new ArrayList<>(7);
        for (DayOfWeek day : DayOfWeek.values()) {
            week.add(WorkDay.off(day));
        }
        return week;
    }

    /**
     * A new person's week, copied from the salon's weekly opening hours: each open weekday a working day with those times
     * and no break, each closed weekday not working (decision 102).
     *
     * @param opening the seven weekdays, or null when the salon has never saved any: no working days
     */
    public static List<WorkDay> copiedFrom(List<WeekdayHours> opening) {
        if (opening == null) {
            return noWorkingDays();
        }
        return opening.stream()
                .map(day -> new WorkDay(day.day(), day.opens(), day.closes(), List.of()))
                .toList();
    }

    /** The weekdays whose hours or breaks differ, Monday first. Both weeks hold the seven weekdays, Monday first. */
    public static List<DayOfWeek> changedDays(List<WorkDay> before, List<WorkDay> after) {
        List<DayOfWeek> changed = new ArrayList<>();
        for (int i = 0; i < after.size(); i++) {
            if (!after.get(i).equals(before.get(i))) {
                changed.add(after.get(i).day());
            }
        }
        return changed;
    }
}
