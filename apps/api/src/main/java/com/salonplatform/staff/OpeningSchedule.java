package com.salonplatform.staff;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * When a branch of the current salon is open, for the booking features (decision 087): F07's availability
 * offers only times inside the answer, and F10 lists what a change affects. This is the contract F07 uses.
 *
 * <p>The answer for a local date comes from three sources, each overriding the one before it:
 * <ol>
 * <li>the branch's weekly hours for that weekday (a branch that has never saved weekly hours is closed);</li>
 * <li>its accepted choice for a published holiday on that date: closed, or open for special hours ("open as
 * usual" leaves the weekly hours). A holiday the salon has not accepted a choice for, a choice made against
 * an earlier revision of a day the operator has since changed, and a choice for a day the operator withdrew
 * all change nothing;</li>
 * <li>a special closure on that date, which closes the whole day.</li>
 * </ol>
 *
 * <p>Dates and times are the salon's local dates and times (its time zone is the tenant's); no instant is
 * involved, so a day boundary is the salon's midnight. The salon is the request's, never a parameter. Each
 * call reads the published holiday calendar first and then opens its own read-only tenant transaction; it
 * never joins the caller's, so call it outside one. Changes made through the settings endpoints apply to the
 * next call: nothing is cached.
 */
public interface OpeningSchedule {

    /** The most days {@link #between} answers in one call. */
    int MAX_DAYS = 400;

    /**
     * Whether the branch is open on the date, and between which times.
     *
     * @throws com.salonplatform.shared.web.NotFoundException when the branch is not one of the current salon's
     *     (another salon's branch and an id that names none answer the same)
     * @throws com.salonplatform.shared.tenancy.TenantNotFoundException when the request names no salon
     */
    OpeningDay on(UUID branchId, LocalDate date);

    /**
     * Each date from {@code from} to {@code to}, both included, in order.
     *
     * @throws IllegalArgumentException when {@code to} is before {@code from}, or the range is longer than
     *     {@link #MAX_DAYS}
     * @throws com.salonplatform.shared.web.NotFoundException as {@link #on}
     */
    List<OpeningDay> between(UUID branchId, LocalDate from, LocalDate to);
}
