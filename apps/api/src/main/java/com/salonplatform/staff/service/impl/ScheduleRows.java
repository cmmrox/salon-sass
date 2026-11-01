package com.salonplatform.staff.service.impl;

import com.salonplatform.staff.TimeWindow;
import com.salonplatform.staff.domain.ScheduleRules;
import com.salonplatform.staff.domain.WorkDay;
import com.salonplatform.staff.entity.MemberDateChange;
import com.salonplatform.staff.entity.MemberDateChangeBreak;
import com.salonplatform.staff.entity.MemberSchedule;
import com.salonplatform.staff.entity.MemberWeekday;
import com.salonplatform.staff.entity.MemberWeekdayBreak;
import com.salonplatform.staff.repository.MemberDateChangeBreakRepository;
import com.salonplatform.staff.repository.MemberScheduleRepository;
import com.salonplatform.staff.repository.MemberWeekdayBreakRepository;
import com.salonplatform.staff.repository.MemberWeekdayRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The rows of members' weeks and of their date changes' breaks, read in a few queries however many members there are.
 * Joins the caller's transaction.
 */
@Component
class ScheduleRows {

    private final MemberScheduleRepository schedules;
    private final MemberWeekdayRepository weekdays;
    private final MemberWeekdayBreakRepository weekdayBreaks;
    private final MemberDateChangeBreakRepository changeBreaks;

    ScheduleRows(
            MemberScheduleRepository schedules,
            MemberWeekdayRepository weekdays,
            MemberWeekdayBreakRepository weekdayBreaks,
            MemberDateChangeBreakRepository changeBreaks) {
        this.schedules = schedules;
        this.weekdays = weekdays;
        this.weekdayBreaks = weekdayBreaks;
        this.changeBreaks = changeBreaks;
    }

    /** Each member's seven weekdays, Monday first. A member with no rows has no working days (AC-1). */
    Map<UUID, List<WorkDay>> weeks(Collection<UUID> memberIds) {
        if (memberIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<MemberWeekday>> days =
                weekdays.ofMembers(memberIds).stream().collect(Collectors.groupingBy(MemberWeekday::getMemberId));
        Map<UUID, List<MemberWeekdayBreak>> breaks = new HashMap<>();
        if (!days.isEmpty()) {
            breaks = weekdayBreaks.ofMembers(days.keySet()).stream()
                    .collect(Collectors.groupingBy(MemberWeekdayBreak::getWeekdayId));
        }
        Map<UUID, List<WorkDay>> weeks = new HashMap<>();
        for (UUID member : memberIds) {
            List<WorkDay> week = new ArrayList<>(ScheduleRules.noWorkingDays());
            for (MemberWeekday day : days.getOrDefault(member, List.of())) {
                week.set(
                        day.day().getValue() - 1,
                        new WorkDay(
                                day.day(),
                                day.getStarts(),
                                day.getFinishes(),
                                ordered(
                                        breaks.getOrDefault(day.getId(), List.of()),
                                        row -> new TimeWindow(row.getStarts(), row.getFinishes()))));
            }
            weeks.put(member, List.copyOf(week));
        }
        return weeks;
    }

    /** One member's week. */
    List<WorkDay> week(UUID memberId) {
        return weeks(List.of(memberId)).get(memberId);
    }

    /** The version of a member's week; 0 when it has never been saved. */
    long version(UUID memberId) {
        return schedules.findOf(memberId).map(MemberSchedule::getVersion).orElse(0L);
    }

    /** Writes a member's first week or replaces it, moving the version on, and returns the new version. */
    long write(UUID memberId, UUID branchId, List<WorkDay> week, Instant at) {
        MemberSchedule schedule = schedules
                .findOf(memberId)
                .map(existing -> {
                    existing.saved(at);
                    return existing;
                })
                .orElseGet(() -> schedules.persist(MemberSchedule.first(branchId, memberId, at)));
        weekdayBreaks.removeOf(memberId);
        weekdays.removeOf(memberId);
        for (WorkDay day : week) {
            MemberWeekday row =
                    weekdays.persist(MemberWeekday.of(branchId, memberId, day.day(), day.starts(), day.finishes(), at));
            // The break's key is on its weekday's row, which has to be written first.
            weekdays.flush();
            for (TimeWindow each : day.breaks()) {
                weekdayBreaks.persist(
                        MemberWeekdayBreak.of(branchId, memberId, row.getId(), each.starts(), each.finishes(), at));
            }
        }
        schedules.flush();
        return schedule.getVersion();
    }

    /** The breaks of each of these changes, in time order. */
    Map<UUID, List<TimeWindow>> changeBreaks(Collection<MemberDateChange> changes) {
        if (changes.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<MemberDateChangeBreak>> found =
                changeBreaks
                        .ofChanges(changes.stream().map(MemberDateChange::getId).toList())
                        .stream()
                        .collect(Collectors.groupingBy(MemberDateChangeBreak::getChangeId));
        Map<UUID, List<TimeWindow>> breaks = new HashMap<>();
        found.forEach((change, rows) ->
                breaks.put(change, ordered(rows, row -> new TimeWindow(row.getStarts(), row.getFinishes()))));
        return breaks;
    }

    private static <T> List<TimeWindow> ordered(List<T> rows, Function<T, TimeWindow> window) {
        return rows.stream()
                .map(window)
                .sorted(Comparator.comparing(TimeWindow::starts))
                .toList();
    }
}
