package com.salonplatform.staff;

import java.time.LocalDate;

/**
 * A period of a person's time that {@link MemberBookingsAffected} is asked about: whole days, as leave is, or part of one
 * date, as blocked time is.
 *
 * @param firstDay the salon's local date it starts on
 * @param lastDay the salon's local date it ends on, both included; the same as {@code firstDay} for part of a date
 * @param time the part of the date, or null for whole days
 */
public record MemberPeriod(LocalDate firstDay, LocalDate lastDay, TimeWindow time) {

    /** Whole days, both included. */
    public static MemberPeriod days(LocalDate firstDay, LocalDate lastDay) {
        return new MemberPeriod(firstDay, lastDay, null);
    }

    /** Part of one date. */
    public static MemberPeriod partOf(LocalDate date, TimeWindow time) {
        return new MemberPeriod(date, date, time);
    }
}
