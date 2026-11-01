package com.salonplatform.staff;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Whether a branch is open on one local date, and between which local times.
 *
 * @param date the salon's local date
 * @param open whether customers can book on it
 * @param opens when it opens, a local time on a quarter hour; null when closed
 * @param closes when it closes, after {@code opens}; null when closed
 * @param reason what decided it
 * @param holidayName the published holiday's name when the date is one, whether or not it decided; else null
 */
public record OpeningDay(
        LocalDate date, boolean open, LocalTime opens, LocalTime closes, OpeningReason reason, String holidayName) {}
