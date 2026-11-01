package com.salonplatform.staff;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The bookings a settings change affects, which {@code staff} asks before it closes a day or saves new hours,
 * so the screen can list them (STF-04, decision 087). Nothing is ever cancelled or moved because of the
 * answer: the bookings are kept, and the salon deals with each one.
 *
 * <p>Declared by {@code staff} and implemented by {@code booking} in F07, so that dependencies point one way
 * ({@code booking} depends on {@code staff}). Until then {@code staff}'s own implementation answers that no
 * booking is affected. F07 registers its implementation as the {@code @Primary} bean of this type, which
 * replaces that one with no change to {@code staff}. This is the contract F07 implements.
 *
 * <p>The salon is the request's, never a parameter. An implementation opens its own read-only tenant
 * transaction and never joins the caller's; {@code staff} calls it outside its own transactions. Bookings
 * that are cancelled, completed or otherwise no longer expected are not listed.
 */
public interface BookingsOnDate {

    /**
     * The branch's live bookings on the local date, in start-time order: what closing that day affects.
     *
     * @param branchId one of the current salon's branches, already checked by the caller
     * @param date the salon's local date
     */
    List<AffectedBooking> on(UUID branchId, LocalDate date);

    /**
     * The branch's live bookings from {@code from} on that the proposed weekly hours would leave outside
     * opening time (on a weekday the week closes, or starting or ending outside its hours), in date and time
     * order. Holidays and closures are not considered: they are the salon's separate decisions.
     *
     * @param branchId one of the current salon's branches, already checked by the caller
     * @param week the proposed hours, one entry for each of the seven weekdays
     * @param from the first local date that counts, normally the salon's today
     */
    List<AffectedBooking> outsideHours(UUID branchId, List<WeekdayHours> week, LocalDate from);
}
