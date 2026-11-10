package com.salonplatform.booking;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * The free start times of a service on one local date, worked out live (BKG-01, BKG-03): the one calculation every
 * feature that offers times uses. F07's staff screens reach it through {@code GET /api/v1/bookings/availability}; F09
 * (rescheduling), F10 (the front desk and walk-ins) and F15 (customers in the app) call it here, naming their
 * {@link Audience}.
 *
 * <p>A time is offered only when the whole booking (the service's, or the variant's, segments from the start to the end
 * of the last) fits, for the professional who would take it:
 *
 * <ul>
 *   <li>inside the branch's opening hours of that date ({@code staff.OpeningSchedule}: weekly hours, holiday choices and
 *       closures);
 *   <li>inside one of their working windows of that date ({@code staff.TeamSchedule}, which already takes out breaks,
 *       leave and blocked time);
 *   <li>overlapping none of their live bookings or unexpired holds, buffers included (held before its expiry, pending
 *       payment, confirmed, arrived, in service, completed). Processing segments occupy nobody (decision 074).
 * </ul>
 *
 * <p>Start times are on the current booking rules' slot interval counted from the salon's local midnight (with 15
 * minutes: 9:00, 9:15, 9:30). A local time the clocks skip is not offered; in the hour that repeats, a time is offered
 * once, at its earlier instant (the shared conversion, {@code shared.time.SalonTime}).
 *
 * <p><strong>Audience rules.</strong>
 *
 * <ul>
 *   <li>{@link Audience#STAFF} (F07, F09 and F10 when staff act): any start after the current instant; the customer
 *       notice and horizon of BKG-02 do not apply (decision 108(c)). The caller bounds the date itself: staff book from
 *       today to one year ahead, and the endpoint answers 400 outside that.
 *   <li>{@link Audience#CUSTOMER} (F15, and F09 when a customer reschedules): a start at least the rules' minimum notice
 *       after the current instant, on a date no later than the rules' horizon in days after today. A date outside that
 *       answers no times with {@link Empty#OUTSIDE_BOOKING_WINDOW}. Whether a professional is shown online is not
 *       considered here; a customer caller that must offer only those filters the answer itself.
 * </ul>
 *
 * <p><strong>"No preference".</strong> Without a professional, each time is offered once, with the eligible professional
 * the rules' {@link NoPreference} strategy picks among those free then: the fewest (or the most) minutes of live bookings
 * and unexpired holds on that local date, ties in the order the Team page lists them. Eligible means on the team and
 * performing the service, shown online or not. The answer is a reading: a hold assigns its professional again when it
 * is placed, and the database's exclusion constraint decides between two holds for the same time.
 *
 * <p>The salon is the request's, never a parameter. There is no permission check: the caller has made its own. Each
 * call reads other modules' answers first and then opens its own read-only tenant transaction; it never joins the
 * caller's, so call it outside one. Nothing is cached.
 */
public interface Availability {

    /** Who the times are for, which decides how near and how far ahead they may be. */
    enum Audience {
        /** The salon's staff: any time from now on (decision 108(c)). */
        STAFF,
        /** A customer booking themselves: the minimum notice and the horizon of the current rules apply (BKG-02). */
        CUSTOMER
    }

    /** Why an answer has no times. */
    enum Empty {
        /** The branch is closed on the date. */
        CLOSED,
        /** No professional on the team performs the service. */
        NOBODY_PERFORMS,
        /** The chosen professional, or every eligible one, works no time on the date. */
        NOT_WORKING,
        /** Someone works, but no whole booking fits anywhere still free. */
        FULLY_BOOKED,
        /** A customer may not book on the date: before today, or beyond the rules' horizon. */
        OUTSIDE_BOOKING_WINDOW
    }

    /**
     * What is asked.
     *
     * @param serviceId one of the salon's listed services
     * @param variantId one of its variants when it has them, else null
     * @param date the salon's local date
     * @param memberId the professional, or null for "No preference"
     * @param audience who the times are for
     */
    record Query(UUID serviceId, UUID variantId, LocalDate date, UUID memberId, Audience audience) {}

    /**
     * One time that can be booked.
     *
     * @param time the local start time
     * @param startsAt its instant
     * @param memberId the professional who would take it: the chosen one, or the one the strategy picks
     * @param memberName their name
     */
    record FreeTime(LocalTime time, Instant startsAt, UUID memberId, String memberName) {}

    /**
     * The answer for one date.
     *
     * @param times in time order
     * @param reason why there are none; null when there are some
     */
    record Answer(LocalDate date, List<FreeTime> times, Empty reason) {}

    /**
     * The free times of the service on the date.
     *
     * @throws com.salonplatform.shared.web.NotFoundException when the service, the variant or the professional is not
     *     the salon's (another salon's, deleted, removed or unknown alike)
     * @throws com.salonplatform.shared.web.ApplicationProblemException 400 on {@code variantId} when the service needs
     *     one of its variants and none, or another service's, is named, or it has none and one is named; 409
     *     {@code slot_unavailable} when the professional does not perform the service; 409 {@code rules_not_set} when
     *     the salon has never saved its booking rules
     * @throws com.salonplatform.shared.tenancy.TenantNotFoundException when the request names no salon
     */
    Answer freeTimes(Query query);

    /**
     * The local dates from {@code from} to {@code to}, both included, on which the salon's branch is open, in order.
     *
     * @throws IllegalArgumentException when {@code to} is before {@code from}, or the range is longer than 400 days
     * @throws com.salonplatform.shared.tenancy.TenantNotFoundException when the request names no salon
     */
    List<LocalDate> openDays(LocalDate from, LocalDate to);
}
