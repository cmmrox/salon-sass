package com.salonplatform.staff;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The bookings a change to a person's schedule leaves outside their working time, which {@code staff} lists in its answer
 * to the change (STF-02, decision 102), and those in a period of their leave or blocked time (STF-03, STF-05, decision
 * 104). Nothing is ever moved or cancelled because of the answer: the bookings are kept, and the salon deals with each one.
 *
 * <p>Declared here and <strong>implemented by {@code booking} in F07</strong>, so that dependencies point one way
 * ({@code booking} depends on {@code staff}; decision 087's pattern). Until then {@code staff}'s own implementation
 * answers that no booking is affected; F07 deletes that implementation when {@code booking} provides its own.
 *
 * <p>A change's questions are asked after the change has committed, since nothing depends on the answer. The salon is the
 * request's, never a parameter. An implementation opens its own read-only tenant transaction and never joins the
 * caller's. Bookings that are cancelled, completed or otherwise no longer expected are not listed. A booking is outside
 * when it does not lie wholly inside one window. Each answer is in date and time order.
 */
public interface MemberBookingsAffected {

    /**
     * The member's bookings from {@code from} on that a proposed week leaves outside their working time.
     *
     * @param memberId one of the current salon's members, already checked by the caller
     * @param windows when they can be booked on each of the seven weekdays; empty on a day they do not work
     * @param from the first local date that counts, normally the salon's today
     * @param exceptDates dates that have a change of their own, which the week does not decide, so a booking on one of
     *     them is not listed
     */
    List<AffectedBooking> outsideWeek(
            UUID memberId, Map<DayOfWeek, List<TimeWindow>> windows, LocalDate from, Set<LocalDate> exceptDates);

    /**
     * The member's bookings on one date that a proposed change for it leaves outside their working time.
     *
     * @param memberId one of the current salon's members, already checked by the caller
     * @param date the salon's local date
     * @param windows when they can be booked on that date; empty when the change is that they do not work
     */
    List<AffectedBooking> outsideDate(UUID memberId, LocalDate date, List<TimeWindow> windows);

    /**
     * The member's bookings in each of these periods of leave or blocked time (F06-S03, STF-03, STF-05, decision 104). <strong>
     * This answer is what "flagged" means:</strong> a booking is flagged while it lies in leave or a block, nothing about it
     * is stored or changed, and so removing the leave clears the flag. F10 reads the same answer for the calendar.
     *
     * <p>A booking is in a period when any of it overlaps: on any of its dates for whole days, and for part of a date any
     * overlap with that time, a booking that only touches it not counting. One booking in two periods is listed in each. The
     * periods are asked together because a person's schedule lists up to 300 of them, and this is read each time it is opened.
     *
     * @param memberId one of the current salon's members, already checked by the caller
     * @param periods the periods; a period with no booking in it may be left out of the answer
     * @return for each period that has any, its bookings in date and time order
     */
    Map<MemberPeriod, List<AffectedBooking>> inPeriods(UUID memberId, Set<MemberPeriod> periods);
}
