package com.salonplatform.staff.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.staff.OpeningDay;
import com.salonplatform.staff.OpeningReason;
import com.salonplatform.staff.WeekdayHours;
import com.salonplatform.staff.domain.OpeningRules.AppliedChoice;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** F04-S03 AC-8: a closure overrides a holiday choice, which overrides the weekly hours. */
class OpeningRulesTest {

    private static final LocalDate MONDAY = LocalDate.of(2027, 1, 4);
    private static final LocalDate SUNDAY = LocalDate.of(2027, 1, 10);
    private static final LocalTime NINE = LocalTime.of(9, 0);
    private static final LocalTime SIX = LocalTime.of(18, 0);

    private static Map<DayOfWeek, WeekdayHours> weekClosedOnSunday() {
        Map<DayOfWeek, WeekdayHours> week = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek day : DayOfWeek.values()) {
            week.put(
                    day,
                    day == DayOfWeek.SUNDAY ? new WeekdayHours(day, null, null) : new WeekdayHours(day, NINE, SIX));
        }
        return week;
    }

    @Test
    void theWeeklyHoursDecideAnOrdinaryDayAndAClosedWeekday() {
        assertThat(OpeningRules.decide(MONDAY, weekClosedOnSunday(), null, false, null))
                .isEqualTo(new OpeningDay(MONDAY, true, NINE, SIX, OpeningReason.WEEKLY_HOURS, null));
        assertThat(OpeningRules.decide(SUNDAY, weekClosedOnSunday(), null, false, null))
                .isEqualTo(new OpeningDay(SUNDAY, false, null, null, OpeningReason.WEEKLY_HOURS, null));
        assertThat(OpeningRules.decide(MONDAY, null, null, false, null).reason())
                .isEqualTo(OpeningReason.NO_WEEKLY_HOURS);
    }

    @Test
    void anAcceptedChoiceOverridesTheWeeklyHoursAndOpenAsUsualLeavesThem() {
        AppliedChoice closed = new AppliedChoice(HolidayChoiceKind.CLOSED, null, null);
        AppliedChoice special = new AppliedChoice(HolidayChoiceKind.SPECIAL, LocalTime.of(10, 0), LocalTime.of(13, 0));
        AppliedChoice asUsual = new AppliedChoice(HolidayChoiceKind.OPEN, null, null);

        assertThat(OpeningRules.decide(MONDAY, weekClosedOnSunday(), closed, false, "Poya"))
                .isEqualTo(new OpeningDay(MONDAY, false, null, null, OpeningReason.HOLIDAY, "Poya"));
        assertThat(OpeningRules.decide(SUNDAY, weekClosedOnSunday(), special, false, "Poya"))
                .isEqualTo(new OpeningDay(
                        SUNDAY, true, LocalTime.of(10, 0), LocalTime.of(13, 0), OpeningReason.HOLIDAY, "Poya"));
        assertThat(OpeningRules.decide(MONDAY, weekClosedOnSunday(), asUsual, false, "Poya"))
                .isEqualTo(new OpeningDay(MONDAY, true, NINE, SIX, OpeningReason.WEEKLY_HOURS, "Poya"));
    }

    @Test
    void aClosureOverridesEverything() {
        AppliedChoice special = new AppliedChoice(HolidayChoiceKind.SPECIAL, LocalTime.of(10, 0), LocalTime.of(13, 0));

        assertThat(OpeningRules.decide(MONDAY, weekClosedOnSunday(), special, true, "Poya"))
                .isEqualTo(new OpeningDay(MONDAY, false, null, null, OpeningReason.CLOSURE, "Poya"));
        assertThat(OpeningRules.decide(MONDAY, null, null, true, null).reason()).isEqualTo(OpeningReason.CLOSURE);
    }

    @Test
    void timesAreQuarterHoursWrittenHhMm() {
        assertThat(LocalTimes.quarterHour("09:45")).isEqualTo(LocalTime.of(9, 45));
        assertThat(LocalTimes.quarterHour("09:10")).isNull();
        assertThat(LocalTimes.quarterHour("24:00")).isNull();
        assertThat(LocalTimes.quarterHour("9:00")).isNull();
        assertThat(LocalTimes.text(LocalTime.of(7, 5))).isEqualTo("07:05");
        assertThat(LocalTimes.weekday("sunday")).isEqualTo(DayOfWeek.SUNDAY);
        assertThat(LocalTimes.weekday("Sunday")).isNull();
    }
}
