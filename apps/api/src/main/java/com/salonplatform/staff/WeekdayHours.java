package com.salonplatform.staff;

import java.time.DayOfWeek;
import java.time.LocalTime;

/**
 * One weekday of a branch's weekly hours: open between two local times, or closed.
 *
 * @param day the weekday
 * @param opens when it opens; null when the branch is closed that weekday
 * @param closes when it closes, after {@code opens}; null when closed
 */
public record WeekdayHours(DayOfWeek day, LocalTime opens, LocalTime closes) {}
