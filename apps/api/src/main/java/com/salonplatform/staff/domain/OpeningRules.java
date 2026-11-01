package com.salonplatform.staff.domain;

import com.salonplatform.staff.OpeningDay;
import com.salonplatform.staff.OpeningReason;
import com.salonplatform.staff.WeekdayHours;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;

/**
 * How one local date's opening is decided (decision 087): a closure overrides a holiday choice, which overrides
 * the weekly hours; a holiday with no choice that applies changes nothing. Pure: the caller has already
 * decided which choice applies (made against the published day's current revision).
 */
public final class OpeningRules {

    private OpeningRules() {}

    /** A holiday choice that applies on the date. */
    public record AppliedChoice(HolidayChoiceKind kind, LocalTime opens, LocalTime closes) {}

    /**
     * The opening on {@code date}.
     *
     * @param week the branch's weekly hours by weekday, or null when it has never saved any
     * @param choice the holiday choice that applies on the date, or null
     * @param closed whether a special closure closes the date
     * @param holidayName the published holiday's name on the date, or null
     */
    public static OpeningDay decide(
            LocalDate date,
            Map<DayOfWeek, WeekdayHours> week,
            AppliedChoice choice,
            boolean closed,
            String holidayName) {
        if (closed) {
            return new OpeningDay(date, false, null, null, OpeningReason.CLOSURE, holidayName);
        }
        if (choice != null && choice.kind() == HolidayChoiceKind.CLOSED) {
            return new OpeningDay(date, false, null, null, OpeningReason.HOLIDAY, holidayName);
        }
        if (choice != null && choice.kind() == HolidayChoiceKind.SPECIAL) {
            return new OpeningDay(date, true, choice.opens(), choice.closes(), OpeningReason.HOLIDAY, holidayName);
        }
        if (week == null) {
            return new OpeningDay(date, false, null, null, OpeningReason.NO_WEEKLY_HOURS, holidayName);
        }
        WeekdayHours day = week.get(date.getDayOfWeek());
        if (day == null || day.opens() == null) {
            return new OpeningDay(date, false, null, null, OpeningReason.WEEKLY_HOURS, holidayName);
        }
        return new OpeningDay(date, true, day.opens(), day.closes(), OpeningReason.WEEKLY_HOURS, holidayName);
    }
}
