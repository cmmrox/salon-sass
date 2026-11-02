package com.salonplatform.staff.service.impl;

import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import com.salonplatform.shared.web.NotFoundException;
import com.salonplatform.staff.AffectedBooking;
import com.salonplatform.staff.MemberBookingsAffected;
import com.salonplatform.staff.MemberPeriod;
import com.salonplatform.staff.TimeWindow;
import com.salonplatform.staff.domain.LocalTimes;
import com.salonplatform.staff.domain.ScheduleRules;
import com.salonplatform.staff.domain.WorkDay;
import com.salonplatform.staff.entity.MemberBlockedTime;
import com.salonplatform.staff.entity.MemberDateChange;
import com.salonplatform.staff.entity.MemberDateChangeBreak;
import com.salonplatform.staff.entity.MemberLeave;
import com.salonplatform.staff.repository.MemberBlockedTimeRepository;
import com.salonplatform.staff.repository.MemberDateChangeBreakRepository;
import com.salonplatform.staff.repository.MemberDateChangeRepository;
import com.salonplatform.staff.repository.MemberLeaveRepository;
import com.salonplatform.staff.repository.MemberRepository;
import com.salonplatform.staff.service.MemberScheduleService;
import com.salonplatform.staff.service.impl.ScheduleValidation.ValidChange;
import com.salonplatform.staff.service.impl.StaffScope.Scope;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every change holds the member's row first, so that changes to one person's schedule run one after the other: the second
 * of two saves sees the first's version, the second change on a date sees the first, and the 101st cannot slip past the
 * count. The lock takes no identity lock, as a schedule takes no seat (decision 102(f)), and does not move the member's
 * version: a profile edit at the same moment waits for it and then succeeds. The unique keys on a member's schedule and on
 * a member and date stand behind the lock. The bookings a change affects are asked after its transaction, as
 * {@link MemberBookingsAffected} requires.
 */
@Service
class MemberScheduleServiceImpl implements MemberScheduleService {

    /** A member has at most 100 upcoming date changes (AC-7). */
    static final int MAX_UPCOMING_CHANGES = 100;

    private final StaffScope scope;
    private final MemberRepository members;
    private final MemberDateChangeRepository changes;
    private final MemberDateChangeBreakRepository changeBreaks;
    private final MemberLeaveRepository leaves;
    private final MemberBlockedTimeRepository blocks;
    private final ScheduleRows rows;
    private final TeamAudit audit;
    private final MemberBookingsAffected bookings;
    private final Clock clock;
    private final TransactionTemplate writing;
    private final TransactionTemplate reading;

    MemberScheduleServiceImpl(
            StaffScope scope,
            MemberRepository members,
            MemberDateChangeRepository changes,
            MemberDateChangeBreakRepository changeBreaks,
            MemberLeaveRepository leaves,
            MemberBlockedTimeRepository blocks,
            ScheduleRows rows,
            TeamAudit audit,
            MemberBookingsAffected bookings,
            Clock clock,
            PlatformTransactionManager transactions) {
        this.scope = scope;
        this.members = members;
        this.changes = changes;
        this.changeBreaks = changeBreaks;
        this.leaves = leaves;
        this.blocks = blocks;
        this.rows = rows;
        this.audit = audit;
        this.bookings = bookings;
        this.clock = clock;
        this.writing = new TransactionTemplate(transactions);
        this.reading = new TransactionTemplate(transactions);
        this.reading.setReadOnly(true);
        // One snapshot: a weekly save replaces every row of the week, so a save committing between two of the read's
        // queries
        // would otherwise show the old hours with their breaks gone.
        this.reading.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public ScheduleView read(UUID memberId) {
        Scope at = scope.of(StaffScope.TEAM_READ);
        boolean noteVisible = at.actor().permissions().contains(StaffScope.TEAM_MANAGE);
        record Read(
                long version,
                List<WorkDay> week,
                List<ChangeView> changes,
                List<MemberLeave> leave,
                List<MemberBlockedTime> blocks) {}
        Read read = reading.execute(status -> {
            members.findActive(memberId, at.branch()).orElseThrow(NotFoundException::new);
            List<MemberDateChange> upcoming = changes.upcoming(memberId, at.today());
            Map<UUID, List<TimeWindow>> breaks = rows.changeBreaks(upcoming);
            return new Read(
                    rows.version(memberId),
                    rows.week(memberId),
                    upcoming.stream()
                            .map(change -> view(change, breaks.getOrDefault(change.getId(), List.of()), noteVisible))
                            .toList(),
                    leaves.notEnded(memberId, at.today()),
                    blocks.upcoming(memberId, at.today()));
        });
        Set<MemberPeriod> periods = new HashSet<>();
        read.leave().forEach(leave -> periods.add(TimeOffViews.periodOf(leave, at.today())));
        read.blocks().forEach(block -> periods.add(TimeOffViews.periodOf(block)));
        Map<MemberPeriod, List<AffectedBooking>> flagged =
                periods.isEmpty() ? Map.of() : bookings.inPeriods(memberId, periods);
        return new ScheduleView(
                read.version(),
                at.today(),
                read.week(),
                read.changes(),
                read.leave().stream()
                        .map(leave -> TimeOffViews.leave(
                                leave,
                                noteVisible,
                                TimeOffViews.countIn(flagged, TimeOffViews.periodOf(leave, at.today()))))
                        .toList(),
                read.blocks().stream()
                        .map(block ->
                                TimeOffViews.block(block, TimeOffViews.countIn(flagged, TimeOffViews.periodOf(block))))
                        .toList());
    }

    @Override
    public SavedWeek saveWeek(UUID memberId, Long version, List<DayInput> weekly) {
        Scope at = scope.of(StaffScope.TEAM_MANAGE);
        if (version == null || version < 0) {
            throw new ApplicationProblemException(
                    400, ErrorCode.BAD_REQUEST, List.of(new FieldProblem("version", "must be zero or more")));
        }
        List<WorkDay> week = ScheduleValidation.week(weekly);
        record Saved(long version, Set<LocalDate> changedDates) {}
        Saved saved = TeamMemberServiceImpl.changing(() -> writing.execute(status -> {
            members.lockActive(memberId, at.branch()).orElseThrow(NotFoundException::new);
            if (rows.version(memberId) != version) {
                throw new ApplicationProblemException(409, ErrorCode.CONFLICT);
            }
            List<WorkDay> before = rows.week(memberId);
            List<DayOfWeek> changed = ScheduleRules.changedDays(before, week);
            if (changed.isEmpty()) {
                throw new ApplicationProblemException(409, ErrorCode.INVALID_STATE);
            }
            Instant now = clock.instant();
            long next = rows.write(memberId, at.branch(), week, now);
            audit.record(
                    at.actor(),
                    TeamAudit.HOURS_SAVED,
                    memberId,
                    weekValues(changed, before, version),
                    weekValues(changed, week, next),
                    now);
            return new Saved(
                    next,
                    changes.upcoming(memberId, at.today()).stream()
                            .map(MemberDateChange::getChangeDate)
                            .collect(Collectors.toSet()));
        }));
        List<AffectedBooking> affected =
                bookings.outsideWeek(memberId, ScheduleRules.windowsByDay(week), at.today(), saved.changedDates());
        return new SavedWeek(saved.version(), week, affected);
    }

    @Override
    public AddedChange addDateChange(UUID memberId, ChangeInput input) {
        Scope at = scope.of(StaffScope.TEAM_MANAGE);
        ValidChange valid = ScheduleValidation.change(input, at.today());
        WorkDay hours = valid.hours();
        MemberDateChange added = TeamMemberServiceImpl.changing(() -> writing.execute(status -> {
            members.lockActive(memberId, at.branch()).orElseThrow(NotFoundException::new);
            if (changes.existsOn(memberId, valid.date())) {
                throw new ApplicationProblemException(409, ErrorCode.CONFLICT);
            }
            if (changes.countUpcoming(memberId, at.today()) >= MAX_UPCOMING_CHANGES) {
                throw new ApplicationProblemException(409, ErrorCode.LIMIT_REACHED);
            }
            Instant now = clock.instant();
            MemberDateChange change = changes.persist(MemberDateChange.of(
                    at.branch(),
                    memberId,
                    valid.date(),
                    hours.starts(),
                    hours.finishes(),
                    valid.note(),
                    at.actor().staffId(),
                    now));
            changes.flush();
            for (TimeWindow each : hours.breaks()) {
                changeBreaks.persist(MemberDateChangeBreak.of(
                        at.branch(), memberId, change.getId(), each.starts(), each.finishes(), now));
            }
            changeBreaks.flush();
            audit.record(
                    at.actor(), TeamAudit.DATE_CHANGE_ADDED, memberId, null, changeValues(change, hours.breaks()), now);
            return change;
        }));
        List<AffectedBooking> affected = bookings.outsideDate(memberId, valid.date(), ScheduleRules.windows(hours));
        return new AddedChange(view(added, hours.breaks(), true), affected);
    }

    @Override
    public void removeDateChange(UUID memberId, UUID changeId) {
        Scope at = scope.of(StaffScope.TEAM_MANAGE);
        TeamMemberServiceImpl.changing(() -> writing.execute(status -> {
            members.lockActive(memberId, at.branch()).orElseThrow(NotFoundException::new);
            MemberDateChange change = changes.findOf(changeId, memberId)
                    .filter(found -> !found.getChangeDate().isBefore(at.today()))
                    .orElseThrow(NotFoundException::new);
            List<TimeWindow> breaks = rows.changeBreaks(List.of(change)).getOrDefault(changeId, List.of());
            Map<String, Object> before = changeValues(change, breaks);
            changeBreaks.removeOf(changeId);
            changes.removeById(changeId);
            audit.record(at.actor(), TeamAudit.DATE_CHANGE_REMOVED, memberId, before, null, clock.instant());
            return changeId;
        }));
    }

    private static ChangeView view(MemberDateChange change, List<TimeWindow> breaks, boolean noteVisible) {
        return new ChangeView(
                change.getId(),
                change.getChangeDate(),
                change.getStarts(),
                change.getFinishes(),
                breaks,
                noteVisible,
                noteVisible ? change.getNote() : null);
    }

    /** What the audit keeps of a save: the weekdays it changed, each as it was or became, and the version. */
    private static Map<String, Object> weekValues(List<DayOfWeek> changed, List<WorkDay> week, long version) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("version", version);
        values.put("changedDays", changed.stream().map(LocalTimes::name).toList());
        Map<String, Object> days = new LinkedHashMap<>();
        for (DayOfWeek day : changed) {
            WorkDay hours = week.get(day.getValue() - 1);
            days.put(LocalTimes.name(day), hoursValues(hours.starts(), hours.finishes(), hours.breaks()));
        }
        values.put("days", days);
        return values;
    }

    /**
     * What the audit keeps of a date change. Not its note: a support session can read the audit list and may not read notes,
     * so the audit says only whether there was one (decision 102(g)).
     */
    private static Map<String, Object> changeValues(MemberDateChange change, List<TimeWindow> breaks) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("changeId", change.getId().toString());
        values.put("date", change.getChangeDate().toString());
        values.putAll(hoursValues(change.getStarts(), change.getFinishes(), breaks));
        values.put("hasNote", change.getNote() != null);
        return values;
    }

    private static Map<String, Object> hoursValues(
            java.time.LocalTime starts, java.time.LocalTime finishes, List<TimeWindow> breaks) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("working", starts != null);
        values.put("starts", LocalTimes.text(starts));
        values.put("finishes", LocalTimes.text(finishes));
        values.put(
                "breaks",
                breaks.stream()
                        .map(each -> LocalTimes.text(each.starts()) + "-" + LocalTimes.text(each.finishes()))
                        .toList());
        return values;
    }
}
