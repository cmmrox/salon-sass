package com.salonplatform.staff;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * When a team member of the current salon can be booked (STF-02, decisions 087 and 102). <strong>This is the contract
 * F07 uses:</strong> its availability reads this answer and {@link OpeningSchedule}'s, and offers only what both allow.
 *
 * <p>The answer for a local date comes from the person's own schedule alone: a change for that date if there is one,
 * which replaces both the times and the breaks of their weekly hours on that date only, or else their weekly hours for
 * that weekday. The salon's opening hours, closures and holidays are ignored, so a day the salon is closed still answers
 * the person's hours; keeping the two answers apart lets a salon open a date without touching anyone's hours. From
 * F06-S03 leave and blocked time are taken out here too, with no change for F07.
 *
 * <p>A removed member works no date. A member added before F06-S02, whose week has never been saved, has no working
 * days. Dates and times are the salon's local dates and times; no instant is involved, so a day boundary is the salon's
 * midnight. The salon is the request's, never a parameter, and there is no permission check: the caller has made its own.
 * Each call opens its own read-only tenant transaction and does not join the caller's. Nothing is cached, so a change
 * applies to the next call.
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
