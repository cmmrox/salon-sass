package com.salonplatform.staff;

import java.time.LocalDate;
import java.util.List;

/**
 * Whether a team member works on one local date, and when they can be booked in it.
 *
 * @param date the salon's local date
 * @param working whether they work on it; false for a removed member on every date
 * @param windows when they can be booked: their hours with the breaks and blocked time taken out, in time order, never
 *     touching one another. Empty exactly when they do not work: a working day always has at least one, since breaks that
 *     would fill the day are refused when they are saved, and a day whose blocks leave no time is not worked
 * @param decidedBy what decided the answer
 */
public record MemberWorkingDay(LocalDate date, boolean working, List<TimeWindow> windows, DecidedBy decidedBy) {

    /** What decided a day's answer, the first that applies, in this order. */
    public enum DecidedBy {
        /** The date is within their leave, so it is not worked, whatever their hours or a date change say. */
        LEAVE,
        /** A block took time out of the day, possibly all of it, which leaves it not worked. */
        BLOCKED_TIME,
        /** A change for this date gave its hours. A block lying wholly outside them took nothing, and does not count. */
        DATE_CHANGE,
        /** Their weekly hours for that weekday gave the answer. */
        WEEK
    }
}
