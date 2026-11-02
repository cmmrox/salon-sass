package com.salonplatform.staff.service.impl;

import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.NotFoundException;
import com.salonplatform.staff.AffectedBooking;
import com.salonplatform.staff.MemberBookingsAffected;
import com.salonplatform.staff.MemberPeriod;
import com.salonplatform.staff.TimeWindow;
import com.salonplatform.staff.domain.LocalTimes;
import com.salonplatform.staff.entity.MemberBlockedTime;
import com.salonplatform.staff.entity.MemberLeave;
import com.salonplatform.staff.repository.MemberBlockedTimeRepository;
import com.salonplatform.staff.repository.MemberLeaveRepository;
import com.salonplatform.staff.repository.MemberRepository;
import com.salonplatform.staff.service.MemberTimeOffService;
import com.salonplatform.staff.service.impl.StaffScope.Scope;
import com.salonplatform.staff.service.impl.TimeOffValidation.Days;
import com.salonplatform.staff.service.impl.TimeOffValidation.Part;
import com.salonplatform.staff.service.impl.TimeOffValidation.ValidBlock;
import com.salonplatform.staff.service.impl.TimeOffValidation.ValidLeave;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every change holds the member's row first, as a date change does, so that one person's changes run one after the other: the
 * overlap rules and the limits are read and written under that lock, and hold under simultaneous requests with no constraint
 * behind them. It also stops either being added to someone being removed. The lock does not move the member's version. The
 * bookings in a period are asked after the transaction, as {@link MemberBookingsAffected} requires, and nothing about them
 * changes or is stored: their flag is worked out when read (decision 104(c)).
 */
@Service
class MemberTimeOffServiceImpl implements MemberTimeOffService {

    /** A person has at most 100 leaves that have not ended (AC-2). */
    static final int MAX_UPCOMING_LEAVES = 100;

    /** A person has at most 200 blocks from today on (AC-5). */
    static final int MAX_UPCOMING_BLOCKS = 200;

    private final StaffScope scope;
    private final MemberRepository members;
    private final MemberLeaveRepository leaves;
    private final MemberBlockedTimeRepository blocks;
    private final TeamAudit audit;
    private final MemberBookingsAffected bookings;
    private final Clock clock;
    private final TransactionTemplate writing;
    private final TransactionTemplate reading;

    MemberTimeOffServiceImpl(
            StaffScope scope,
            MemberRepository members,
            MemberLeaveRepository leaves,
            MemberBlockedTimeRepository blocks,
            TeamAudit audit,
            MemberBookingsAffected bookings,
            Clock clock,
            PlatformTransactionManager transactions) {
        this.scope = scope;
        this.members = members;
        this.leaves = leaves;
        this.blocks = blocks;
        this.audit = audit;
        this.bookings = bookings;
        this.clock = clock;
        this.writing = new TransactionTemplate(transactions);
        this.reading = new TransactionTemplate(transactions);
        this.reading.setReadOnly(true);
    }

    @Override
    public AddedLeave addLeave(UUID memberId, LeaveInput input) {
        Scope at = scope.of(StaffScope.TEAM_MANAGE);
        ValidLeave valid = TimeOffValidation.leave(input, at.today());
        Days days = valid.days();
        MemberLeave added = TeamMemberServiceImpl.changing(() -> writing.execute(status -> {
            members.lockActive(memberId, at.branch()).orElseThrow(NotFoundException::new);
            if (!leaves.overlapping(memberId, days.firstDay(), days.lastDay()).isEmpty()) {
                throw new ApplicationProblemException(409, ErrorCode.CONFLICT);
            }
            if (leaves.countNotEnded(memberId, at.today()) >= MAX_UPCOMING_LEAVES) {
                throw new ApplicationProblemException(409, ErrorCode.LIMIT_REACHED);
            }
            Instant now = clock.instant();
            MemberLeave leave = leaves.persist(MemberLeave.of(
                    at.branch(),
                    memberId,
                    days.firstDay(),
                    days.lastDay(),
                    valid.type(),
                    valid.note(),
                    at.actor().staffId(),
                    now));
            leaves.flush();
            audit.record(at.actor(), TeamAudit.LEAVE_ADDED, memberId, null, leaveValues(leave), now);
            return leave;
        }));
        List<AffectedBooking> affected = bookingsIn(memberId, TimeOffViews.periodOf(added, at.today()));
        return new AddedLeave(TimeOffViews.leave(added, true, affected.size()), affected);
    }

    @Override
    public void removeLeave(UUID memberId, UUID leaveId) {
        Scope at = scope.of(StaffScope.TEAM_MANAGE);
        TeamMemberServiceImpl.changing(() -> writing.execute(status -> {
            members.lockActive(memberId, at.branch()).orElseThrow(NotFoundException::new);
            MemberLeave leave = leaves.findOf(leaveId, memberId)
                    .filter(found -> !found.getLastDay().isBefore(at.today()))
                    .orElseThrow(NotFoundException::new);
            Map<String, Object> before = leaveValues(leave);
            leaves.removeById(leaveId);
            audit.record(at.actor(), TeamAudit.LEAVE_REMOVED, memberId, before, null, clock.instant());
            return leaveId;
        }));
    }

    @Override
    public List<AffectedBooking> leaveAffected(UUID memberId, String firstDay, String lastDay) {
        Scope at = scope.of(StaffScope.TEAM_MANAGE);
        Days days = TimeOffValidation.days(firstDay, lastDay, at.today());
        requireActive(memberId, at);
        return bookingsIn(memberId, MemberPeriod.days(days.firstDay(), days.lastDay()));
    }

    @Override
    public AddedBlock addBlock(UUID memberId, BlockInput input) {
        Scope at = scope.of(StaffScope.TEAM_BLOCK);
        ValidBlock valid = TimeOffValidation.block(input, at.today());
        Part part = valid.part();
        MemberBlockedTime added = TeamMemberServiceImpl.changing(() -> writing.execute(status -> {
            members.lockActive(memberId, at.branch()).orElseThrow(NotFoundException::new);
            if (blocks.overlaps(memberId, part.date(), part.starts(), part.finishes())) {
                throw new ApplicationProblemException(409, ErrorCode.CONFLICT);
            }
            if (blocks.countUpcoming(memberId, at.today()) >= MAX_UPCOMING_BLOCKS) {
                throw new ApplicationProblemException(409, ErrorCode.LIMIT_REACHED);
            }
            Instant now = clock.instant();
            MemberBlockedTime block = blocks.persist(MemberBlockedTime.of(
                    at.branch(),
                    memberId,
                    part.date(),
                    part.starts(),
                    part.finishes(),
                    valid.label(),
                    at.actor().staffId(),
                    now));
            blocks.flush();
            audit.record(at.actor(), TeamAudit.BLOCK_ADDED, memberId, null, blockValues(block), now);
            return block;
        }));
        List<AffectedBooking> affected = bookingsIn(memberId, TimeOffViews.periodOf(added));
        return new AddedBlock(TimeOffViews.block(added, affected.size()), affected);
    }

    @Override
    public void removeBlock(UUID memberId, UUID blockId) {
        Scope at = scope.of(StaffScope.TEAM_BLOCK);
        TeamMemberServiceImpl.changing(() -> writing.execute(status -> {
            members.lockActive(memberId, at.branch()).orElseThrow(NotFoundException::new);
            MemberBlockedTime block = blocks.findOf(blockId, memberId)
                    .filter(found -> !found.getBlockDate().isBefore(at.today()))
                    .orElseThrow(NotFoundException::new);
            Map<String, Object> before = blockValues(block);
            blocks.removeById(blockId);
            audit.record(at.actor(), TeamAudit.BLOCK_REMOVED, memberId, before, null, clock.instant());
            return blockId;
        }));
    }

    @Override
    public List<AffectedBooking> blockAffected(UUID memberId, String date, String starts, String finishes) {
        Scope at = scope.of(StaffScope.TEAM_BLOCK);
        Part part = TimeOffValidation.part(date, starts, finishes, at.today());
        requireActive(memberId, at);
        return bookingsIn(memberId, MemberPeriod.partOf(part.date(), new TimeWindow(part.starts(), part.finishes())));
    }

    private void requireActive(UUID memberId, Scope at) {
        reading.execute(status -> members.findActive(memberId, at.branch()).orElseThrow(NotFoundException::new));
    }

    private List<AffectedBooking> bookingsIn(UUID memberId, MemberPeriod period) {
        return bookings.inPeriods(memberId, Set.of(period)).getOrDefault(period, List.of());
    }

    /**
     * What the audit keeps of leave: that it was added or removed, and its days. Neither its type nor its note: a support
     * session can read the audit list and may not receive them, so it says only whether there was a note (decision 104(a)).
     */
    private static Map<String, Object> leaveValues(MemberLeave leave) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("leaveId", leave.getId().toString());
        values.put("firstDay", leave.getFirstDay().toString());
        values.put("lastDay", leave.getLastDay().toString());
        values.put("hasNote", leave.getNote() != null);
        return values;
    }

    /** What the audit keeps of a block, its label included: everyone who reads the team reads that (AC-12). */
    private static Map<String, Object> blockValues(MemberBlockedTime block) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("blockId", block.getId().toString());
        values.put("date", block.getBlockDate().toString());
        values.put("starts", LocalTimes.text(block.getStarts()));
        values.put("finishes", LocalTimes.text(block.getFinishes()));
        values.put("label", block.getLabel());
        return values;
    }
}
