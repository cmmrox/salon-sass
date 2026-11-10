package com.salonplatform.staff.service.impl;

import com.salonplatform.shared.persistence.ReadOnlyWork;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.shared.web.NotFoundException;
import com.salonplatform.staff.MemberWorkingDay;
import com.salonplatform.staff.TeamSchedule;
import com.salonplatform.staff.TimeWindow;
import com.salonplatform.staff.domain.ScheduleRules;
import com.salonplatform.staff.domain.WorkDay;
import com.salonplatform.staff.entity.Member;
import com.salonplatform.staff.entity.MemberBlockedTime;
import com.salonplatform.staff.entity.MemberDateChange;
import com.salonplatform.staff.entity.MemberLeave;
import com.salonplatform.staff.repository.MemberBlockedTimeRepository;
import com.salonplatform.staff.repository.MemberDateChangeRepository;
import com.salonplatform.staff.repository.MemberLeaveRepository;
import com.salonplatform.staff.repository.MemberRepository;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Reads the member, their week and the range's date changes, leave and blocks in one read-only tenant transaction of its own,
 * in a few queries for the whole range. That transaction reads one snapshot: a weekly save replaces every row of the week, so
 * under the default isolation a save committing between two of these queries would answer the old hours with their breaks gone.
 * It takes no lock and never waits for a save.
 */
@Service
class TeamScheduleImpl implements TeamSchedule {

    private final MemberRepository members;
    private final MemberDateChangeRepository changes;
    private final MemberLeaveRepository leaves;
    private final MemberBlockedTimeRepository blocks;
    private final ScheduleRows rows;
    private final ReadOnlyWork reading;

    TeamScheduleImpl(
            MemberRepository members,
            MemberDateChangeRepository changes,
            MemberLeaveRepository leaves,
            MemberBlockedTimeRepository blocks,
            ScheduleRows rows,
            ReadOnlyWork reading) {
        this.members = members;
        this.changes = changes;
        this.leaves = leaves;
        this.blocks = blocks;
        this.rows = rows;
        this.reading = reading;
    }

    @Override
    public MemberWorkingDay on(UUID memberId, LocalDate date) {
        return between(memberId, date, date).getFirst();
    }

    @Override
    public List<MemberWorkingDay> between(UUID memberId, LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            throw new IllegalArgumentException("a range of 1 to " + MAX_DAYS + " days, the last not before the first");
        }
        TenantContext.require();
        return reading.readOneSnapshot(() -> {
            Member member = members.findAny(memberId).orElseThrow(NotFoundException::new);
            boolean removed = member.isRemoved();
            List<WorkDay> week = removed ? ScheduleRules.noWorkingDays() : rows.week(memberId);
            Map<LocalDate, WorkDay> changed = removed ? Map.of() : changedBetween(memberId, from, to);
            List<MemberLeave> away = removed ? List.of() : leaves.overlapping(memberId, from, to);
            Map<LocalDate, List<TimeWindow>> blocked = removed ? Map.of() : blockedBetween(memberId, from, to);
            List<MemberWorkingDay> days = new ArrayList<>();
            for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
                days.add(ScheduleRules.decide(
                        date,
                        week.get(date.getDayOfWeek().getValue() - 1),
                        changed.get(date),
                        within(away, date),
                        blocked.getOrDefault(date, List.of())));
            }
            return days;
        });
    }

    private static boolean within(List<MemberLeave> away, LocalDate date) {
        return away.stream()
                .anyMatch(leave -> !date.isBefore(leave.getFirstDay()) && !date.isAfter(leave.getLastDay()));
    }

    private Map<LocalDate, List<TimeWindow>> blockedBetween(UUID memberId, LocalDate from, LocalDate to) {
        return blocks.between(memberId, from, to).stream()
                .collect(Collectors.groupingBy(
                        MemberBlockedTime::getBlockDate,
                        Collectors.mapping(
                                block -> new TimeWindow(block.getStarts(), block.getFinishes()), Collectors.toList())));
    }

    private Map<LocalDate, WorkDay> changedBetween(UUID memberId, LocalDate from, LocalDate to) {
        List<MemberDateChange> found = changes.between(memberId, from, to);
        Map<UUID, List<TimeWindow>> breaks = rows.changeBreaks(found);
        return found.stream()
                .collect(Collectors.toMap(
                        MemberDateChange::getChangeDate,
                        change -> new WorkDay(
                                change.getChangeDate().getDayOfWeek(),
                                change.getStarts(),
                                change.getFinishes(),
                                breaks.getOrDefault(change.getId(), List.of()))));
    }
}
