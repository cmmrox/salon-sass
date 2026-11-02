package com.salonplatform.staff.domain;

import com.salonplatform.staff.MemberWorkingDay;
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
 * How a person's working time is worked out (STF-02, decision 102). Pure. F07 trusts these windows, so a stretch of time
 * taken out is handled at every edge: touching the start or the finish, touching another, and, for F06-S03's leave and
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
     * The answer for one date. A change replaces the weekly hours on that date, both the times and the breaks.
     *
     * @param weekly the person's hours for the date's weekday
     * @param change the hours of the date's change, or null when it has none
     */
    public static MemberWorkingDay decide(LocalDate date, WorkDay weekly, WorkDay change) {
        WorkDay day = change != null ? change : weekly;
        return new MemberWorkingDay(date, day.working(), windows(day), change != null);
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
