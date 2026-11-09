package com.salonplatform.staff.service.impl;

import com.salonplatform.staff.AffectedBooking;
import com.salonplatform.staff.MemberPeriod;
import com.salonplatform.staff.TimeWindow;
import com.salonplatform.staff.entity.MemberBlockedTime;
import com.salonplatform.staff.entity.MemberLeave;
import com.salonplatform.staff.service.MemberTimeOffService.BlockView;
import com.salonplatform.staff.service.MemberTimeOffService.LeaveView;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Leave and blocks as a caller may see them. A leave's type and note reach a caller with {@code team.manage} only
 * (decision 104(a)): this is the one place they are let through, and a view without them holds neither.
 */
final class TimeOffViews {

    private TimeOffViews() {}

    /** What is left of leave from today, which is the period its bookings are asked about: a past day is not to resolve. */
    static MemberPeriod periodOf(MemberLeave leave, LocalDate today) {
        return MemberPeriod.days(leave.getFirstDay().isBefore(today) ? today : leave.getFirstDay(), leave.getLastDay());
    }

    static MemberPeriod periodOf(MemberBlockedTime block) {
        return MemberPeriod.partOf(block.getBlockDate(), new TimeWindow(block.getStarts(), block.getFinishes()));
    }

    static LeaveView leave(MemberLeave leave, boolean privateVisible, int bookingsToResolve) {
        return new LeaveView(
                leave.getId(),
                leave.getFirstDay(),
                leave.getLastDay(),
                privateVisible,
                privateVisible ? leave.type() : null,
                privateVisible ? leave.getNote() : null,
                bookingsToResolve);
    }

    static BlockView block(MemberBlockedTime block, int bookingsToResolve) {
        return new BlockView(
                block.getId(),
                block.getBlockDate(),
                block.getStarts(),
                block.getFinishes(),
                block.getLabel(),
                bookingsToResolve);
    }

    /** How many of the answer's bookings lie in the period. */
    static int countIn(Map<MemberPeriod, List<AffectedBooking>> answer, MemberPeriod period) {
        return answer.getOrDefault(period, List.of()).size();
    }
}
