package com.salonplatform.staff.domain;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Locale;

/**
 * Times and weekdays as the settings API writes them: a local time {@code HH:MM} on a quarter hour, and a
 * weekday by its lower-case English name ({@code monday}). Pure.
 */
public final class LocalTimes {

    private LocalTimes() {}

    /** The time written {@code HH:MM} on a quarter hour (00, 15, 30 or 45 minutes), or null when it is not. */
    public static LocalTime quarterHour(String text) {
        if (text == null || !text.matches("([01]\\d|2[0-3]):(00|15|30|45)")) {
            return null;
        }
        return LocalTime.of(Integer.parseInt(text.substring(0, 2)), Integer.parseInt(text.substring(3)));
    }

    /** The time as the API writes it, {@code HH:MM}; null for null. */
    public static String text(LocalTime time) {
        return time == null ? null : String.format(Locale.ROOT, "%02d:%02d", time.getHour(), time.getMinute());
    }

    /** The weekday named in lower case ({@code monday}), or null when it names none. */
    public static DayOfWeek weekday(String name) {
        if (name == null) {
            return null;
        }
        for (DayOfWeek day : DayOfWeek.values()) {
            if (name(day).equals(name)) {
                return day;
            }
        }
        return null;
    }

    /** The weekday's lower-case English name. */
    public static String name(DayOfWeek day) {
        return day.name().toLowerCase(Locale.ROOT);
    }
}
