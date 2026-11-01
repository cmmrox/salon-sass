package com.salonplatform.staff;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * A booking that a closure or an hours change affects, as the settings screens list it.
 *
 * @param date the booking's local date
 * @param time when it starts, the salon's local time
 * @param customerName the customer's display name
 * @param serviceName the booked service's name
 */
public record AffectedBooking(LocalDate date, LocalTime time, String customerName, String serviceName) {}
