package com.salonplatform.staff.service.impl;

import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.shared.web.NotFoundException;
import com.salonplatform.staff.MemberWorkingDay;
import com.salonplatform.staff.TeamSchedule;
import com.salonplatform.staff.TimeWindow;
import com.salonplatform.staff.domain.ScheduleRules;
import com.salonplatform.staff.domain.WorkDay;
import com.salonplatform.staff.entity.Member;
import com.salonplatform.staff.entity.MemberDateChange;
import com.salonplatform.staff.repository.MemberDateChangeRepository;
import com.salonplatform.staff.repository.MemberRepository;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reads the member, their week and the range's date changes in one read-only tenant transaction of its own, in a few queries
 * for the whole range. F06-S03 subtracts leave and blocked time here, with no change for F07.
 */
@Service
class TeamScheduleImpl implements TeamSchedule {

    private final MemberRepository members;
    private final MemberDateChangeRepository changes;
    private final ScheduleRows rows;
    private final TransactionTemplate reading;

    TeamScheduleImpl(
            MemberRepository members,
            MemberDateChangeRepository changes,
            ScheduleRows rows,
            PlatformTransactionManager transactions) {
        this.members = members;
        this.changes = changes;
        this.rows = rows;
        this.reading = new TransactionTemplate(transactions);
        this.reading.setReadOnly(true);
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
        return reading.execute(status -> {
            Member member = members.findAny(memberId).orElseThrow(NotFoundException::new);
            List<WorkDay> week = member.isRemoved() ? ScheduleRules.noWorkingDays() : rows.week(memberId);
            Map<LocalDate, WorkDay> changed = member.isRemoved() ? Map.of() : changedBetween(memberId, from, to);
            List<MemberWorkingDay> days = new ArrayList<>();
            for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
                days.add(ScheduleRules.decide(date, week.get(date.getDayOfWeek().getValue() - 1), changed.get(date)));
            }
            return days;
        });
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
