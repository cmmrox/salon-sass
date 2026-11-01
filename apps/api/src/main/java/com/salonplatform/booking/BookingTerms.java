package com.salonplatform.booking;

/**
 * The rules themselves (BKG-02, BKG-09), as one version of a salon's booking rules holds them.
 *
 * @param slotIntervalMinutes how often start times are offered: 10, 15, 20 or 30 minutes
 * @param minimumNoticeMinutes how long before an appointment it must be booked: 0 (any time before), 30, 60,
 *     120, 240 or 1440 minutes
 * @param bookingHorizonDays how far ahead customers can book: 14, 30, 60, 90 or 180 days
 * @param deposit what a booking asks for when it is made
 * @param freeCancellationHours until how many hours before the appointment a cancellation keeps nothing: 2, 6,
 *     12, 24 or 48
 * @param lateCancellation what a later cancellation keeps
 * @param noShow what a no-show keeps (never {@link Retention.Nothing})
 * @param reschedule how the customer may move the booking themselves
 */
public record BookingTerms(
        int slotIntervalMinutes,
        int minimumNoticeMinutes,
        int bookingHorizonDays,
        DepositRule deposit,
        int freeCancellationHours,
        Retention lateCancellation,
        Retention noShow,
        Rescheduling reschedule) {}
