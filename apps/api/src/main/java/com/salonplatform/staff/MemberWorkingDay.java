package com.salonplatform.staff;

import java.time.LocalDate;
import java.util.List;

/**
 * Whether a team member works on one local date, and when they can be booked in it.
 *
 * @param date the salon's local date
 * @param working whether they work on it; false for a removed member on every date
 * @param windows when they can be booked: their hours with the breaks taken out, in time order, never touching one
 *     another. Empty exactly when they do not work: a working day always has at least one, since breaks that would fill
 *     the day are refused when they are saved
 * @param fromDateChange whether a change for this date decided the answer, rather than their weekly hours
 */
public record MemberWorkingDay(LocalDate date, boolean working, List<TimeWindow> windows, boolean fromDateChange) {}
