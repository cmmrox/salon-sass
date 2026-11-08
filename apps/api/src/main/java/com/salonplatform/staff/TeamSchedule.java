package com.salonplatform.staff;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * When a team member of the current salon can be booked (STF-02, STF-03, STF-05, decisions 087, 102 and 104). <strong>This
 * is the contract F07 uses:</strong> its availability reads this answer and {@link OpeningSchedule}'s, and offers only what
 * both allow. It does not read leave or blocked time of its own.
 *
 * <p>The answer for a local date comes from the person's own schedule alone:
 *
 * <ol>
 *   <li>A date within their leave is not worked, with no windows, whichever way their hours or a date change read.
 *   <li>Otherwise their hours are those of a change for that date if there is one, which replaces both the times and the
 *       breaks of their weekly hours on that date only, or else their weekly hours for that weekday.
 *   <li>Their breaks and their blocked time on that date are taken out of those hours. A block may touch or cross either
 *       edge of a window, cross a break or lie outside the hours altogether.
 *   <li>A day with no time left is not worked, with no windows: {@code working} true always has at least one window
 *       (decision 103).
 * </ol>
 *
 * <p>{@link MemberWorkingDay#decidedBy()} says which of those decided the day. Nothing is cached, so removing a leave or a
 * block restores the answer on the next call. The salon's opening hours, closures and holidays are ignored, so a day the
 * salon is closed still answers the person's hours; keeping the two answers apart lets a salon open a date without touching
 * anyone's hours. A leave's type and note never appear here.
 *
 * <p>A removed member works no date. A member added before F06-S02, whose week has never been saved, has no working
 * days. Dates and times are the salon's local dates and times; no instant is involved, so a day boundary is the salon's
 * midnight. The salon is the request's, never a parameter, and there is no permission check: the caller has made its own.
 * Each call opens its own read-only tenant transaction and does not join the caller's, and reads one snapshot: a call during
 * a weekly save answers the whole old week or the whole new one, never a mix, and does not wait for the save. Nothing is
 * cached, so a change applies to the next call.
 */
public interface TeamSchedule {

    /** The most days {@link #between} answers in one call. */
    int MAX_DAYS = 400;

    /**
     * Whether the member works on the date, and when they can be booked in it.
     *
     * @throws com.salonplatform.shared.web.NotFoundException when the id is not a member of the current salon (another
     *     salon's member and an id that names none answer the same)
     * @throws com.salonplatform.shared.tenancy.TenantNotFoundException when the request names no salon
     */
    MemberWorkingDay on(UUID memberId, LocalDate date);

    /**
     * Each date from {@code from} to {@code to}, both included, in order.
     *
     * @throws IllegalArgumentException when {@code to} is before {@code from}, or the range is longer than
     *     {@link #MAX_DAYS}
     * @throws com.salonplatform.shared.web.NotFoundException as {@link #on}
     * @throws com.salonplatform.shared.tenancy.TenantNotFoundException as {@link #on}
     */
    List<MemberWorkingDay> between(UUID memberId, LocalDate from, LocalDate to);
}
