package com.salonplatform.booking;

/** How a customer may move a booking themselves (BKG-09). */
public sealed interface Rescheduling permits Rescheduling.NotOnline, Rescheduling.Limited, Rescheduling.Unlimited {

    /** Not online: the customer contacts the salon. */
    record NotOnline() implements Rescheduling {}

    /**
     * A number of times per booking, up to a time before the appointment.
     *
     * @param times 1 or 2
     * @param untilHours the latest, in hours before the appointment: 2, 6, 12 or 24
     */
    record Limited(int times, int untilHours) implements Rescheduling {}

    /**
     * As often as the customer needs, up to a time before the appointment.
     *
     * @param untilHours the latest, in hours before the appointment: 2, 6, 12 or 24
     */
    record Unlimited(int untilHours) implements Rescheduling {}
}
