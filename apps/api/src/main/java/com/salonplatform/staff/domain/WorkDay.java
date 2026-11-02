package com.salonplatform.staff.domain;

import com.salonplatform.staff.TimeWindow;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

/**
 * One day of a person's hours: working between two local times with breaks, or not working.
 *
 * @param day the weekday
 * @param starts when they start; null when they do not work
 * @param finishes when they finish, after {@code starts}; null when they do not work
 * @param breaks their breaks in time order; empty when they do not work
 */
public record WorkDay(DayOfWeek day, LocalTime starts, LocalTime finishes, List<TimeWindow> breaks) {

    /** A day they do not work. */
    public static WorkDay off(DayOfWeek day) {
        return new WorkDay(day, null, null, List.of());
    }

    public boolean working() {
        return starts != null;
    }
}
