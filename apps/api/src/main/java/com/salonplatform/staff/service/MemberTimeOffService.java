package com.salonplatform.staff.service;

import com.salonplatform.staff.AffectedBooking;
import com.salonplatform.staff.domain.LeaveType;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * A team member's leave and blocked time (STF-03, STF-05, decisions 099 and 104). Leave needs {@code team.manage}, and
 * blocked time {@code team.block}. Reading them is part of the schedule, {@link MemberScheduleService#read}, where a leave's
 * type and note reach a caller with {@code team.manage} only. No booking is ever moved or cancelled: a booking in the period
 * is kept, and is flagged for as long as it lies there, which is worked out when read and stored nowhere.
 */
public interface MemberTimeOffService {

    /** Leave as a request sends it; nothing here has been checked. */
    record LeaveInput(String firstDay, String lastDay, String type, String note) {}

    /** A block as a request sends it; nothing here has been checked. */
    record BlockInput(String date, String starts, String finishes, String label) {}

    /**
     * Leave as a caller may see it.
     *
     * @param privateVisible whether the caller may see its type and note, with {@code team.manage}
     * @param type its type, or null when the caller may not see it
     * @param note its note, or null for none or when the caller may not see it
     * @param bookingsToResolve how many bookings lie in what is left of it
     */
    record LeaveView(
            UUID id,
            LocalDate firstDay,
            LocalDate lastDay,
            boolean privateVisible,
            LeaveType type,
            String note,
            int bookingsToResolve) {}

    /**
     * Blocked time.
     *
     * @param bookingsToResolve how many bookings lie in it
     */
    record BlockView(
            UUID id, LocalDate date, LocalTime starts, LocalTime finishes, String label, int bookingsToResolve) {}

    /** The leave as added, and the bookings in it, which are kept. */
    record AddedLeave(LeaveView leave, List<AffectedBooking> affected) {}

    /** The block as added, and the bookings in it, which are kept. */
    record AddedBlock(BlockView block, List<AffectedBooking> affected) {}

    /**
     * Adds leave, from a first day of today or later to a last day at most a year ahead, by the salon's date.
     *
     * @throws com.salonplatform.shared.web.ApplicationProblemException 400 naming each field, nothing saved; 404; 409
     *     {@code conflict} when it overlaps this person's leave; 409 {@code limit_reached} at 100 that have not ended
     */
    AddedLeave addLeave(UUID memberId, LeaveInput input);

    /**
     * Removes leave that has not ended, including leave under way: they become bookable on those days again.
     *
     * @throws com.salonplatform.shared.web.NotFoundException when it has ended, is gone, or is not this person's
     */
    void removeLeave(UUID memberId, UUID leaveId);

    /**
     * The bookings leave for these days would affect, before it is added.
     *
     * @throws com.salonplatform.shared.web.ApplicationProblemException 400 naming each field; 404
     */
    List<AffectedBooking> leaveAffected(UUID memberId, String firstDay, String lastDay);

    /**
     * Blocks part of one date, today to a year ahead by the salon's date, stored as entered: it may lie outside the person's
     * hours or on their leave.
     *
     * @throws com.salonplatform.shared.web.ApplicationProblemException 400 naming each field, nothing saved; 404; 409
     *     {@code conflict} when it overlaps another of this person's blocks, which may touch it; 409 {@code limit_reached}
     *     at 200 from today on
     */
    AddedBlock addBlock(UUID memberId, BlockInput input);

    /**
     * Removes a block from today on.
     *
     * @throws com.salonplatform.shared.web.NotFoundException when its date has passed, or it is gone or not this person's
     */
    void removeBlock(UUID memberId, UUID blockId);

    /**
     * The bookings a block of this time would affect, before it is added.
     *
     * @throws com.salonplatform.shared.web.ApplicationProblemException 400 naming each field; 404
     */
    List<AffectedBooking> blockAffected(UUID memberId, String date, String starts, String finishes);
}
