package com.salonplatform.staff.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.staff.MemberWorkingDay;
import com.salonplatform.staff.MemberWorkingDay.DecidedBy;
import com.salonplatform.staff.TimeWindow;
import com.salonplatform.staff.WeekdayHours;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The windows F07 trusts (F06-S02 AC-8, AC-9), at every edge. */
class ScheduleRulesTest {

    private static final LocalDate A_MONDAY = LocalDate.of(2026, 11, 2);

    private static LocalTime at(String time) {
        return LocalTime.parse(time);
    }

    private static TimeWindow span(String starts, String finishes) {
        return new TimeWindow(at(starts), at(finishes));
    }

    private static List<TimeWindow> windows(String starts, String finishes, TimeWindow... taken) {
        return ScheduleRules.windows(at(starts), at(finishes), List.of(taken));
    }

    private static WorkDay monday(String starts, String finishes, TimeWindow... breaks) {
        return new WorkDay(DayOfWeek.MONDAY, at(starts), at(finishes), List.of(breaks));
    }

    @Test
    void aDayWithNoBreakIsOneWindow() {
        assertThat(windows("09:00", "18:00")).containsExactly(span("09:00", "18:00"));
    }

    @Test
    void aBreakInTheMiddleLeavesTheTimeOnEitherSide() {
        assertThat(windows("09:00", "18:00", span("13:00", "14:00")))
                .containsExactly(span("09:00", "13:00"), span("14:00", "18:00"));
    }

    @Test
    void aBreakTouchingTheStartLeavesNoEmptyWindowBeforeIt() {
        assertThat(windows("09:00", "18:00", span("09:00", "10:00"))).containsExactly(span("10:00", "18:00"));
    }

    @Test
    void aBreakTouchingTheFinishLeavesNoEmptyWindowAfterIt() {
        assertThat(windows("09:00", "18:00", span("17:00", "18:00"))).containsExactly(span("09:00", "17:00"));
    }

    @Test
    void breaksTouchingBothEndsLeaveOnlyTheMiddle() {
        assertThat(windows("09:00", "18:00", span("09:00", "09:15"), span("17:45", "18:00")))
                .containsExactly(span("09:15", "17:45"));
    }

    @Test
    void touchingBreaksLeaveNoWindowBetweenThem() {
        assertThat(windows("09:00", "18:00", span("12:00", "13:00"), span("13:00", "14:00")))
                .containsExactly(span("09:00", "12:00"), span("14:00", "18:00"));
    }

    @Test
    void theWindowsAreInTimeOrderWhateverOrderTheBreaksCameIn() {
        assertThat(windows("09:00", "18:00", span("15:00", "15:30"), span("10:00", "10:30"), span("12:00", "13:00")))
                .containsExactly(
                        span("09:00", "10:00"), span("10:30", "12:00"), span("13:00", "15:00"), span("15:30", "18:00"));
    }

    @Test
    void fiveBreaksLeaveSixWindows() {
        assertThat(windows(
                        "08:00",
                        "20:00",
                        span("09:00", "09:15"),
                        span("11:00", "11:15"),
                        span("13:00", "13:15"),
                        span("15:00", "15:15"),
                        span("17:00", "17:15")))
                .hasSize(6);
    }

    @Test
    void aQuarterHourWindowBetweenTwoBreaksIsKept() {
        assertThat(windows("09:00", "10:00", span("09:00", "09:15"), span("09:30", "10:00")))
                .containsExactly(span("09:15", "09:30"));
    }

    @Test
    void aFinishAt2345IsAnOrdinaryFinish() {
        assertThat(windows("00:00", "23:45", span("23:30", "23:45"))).containsExactly(span("00:00", "23:30"));
    }

    /** The subtraction itself; validation refuses breaks like these, so that a working day always has a window. */
    @Test
    void stretchesFillingTheWholeDayLeaveNothing() {
        assertThat(windows("09:00", "18:00", span("09:00", "18:00"))).isEmpty();
        assertThat(windows("09:00", "18:00", span("09:00", "13:00"), span("13:00", "18:00")))
                .isEmpty();
    }

    @Test
    void aDayNotWorkedHasNoWindows() {
        assertThat(ScheduleRules.windows(WorkDay.off(DayOfWeek.MONDAY))).isEmpty();
    }

    // F06-S03's leave and blocked time arrive through the same subtraction, and need not fit the rules of breaks.

    @Test
    void takenStretchesOverlappingEachOtherAreTakenOutOnce() {
        assertThat(windows("09:00", "18:00", span("10:00", "12:00"), span("11:00", "11:30"), span("11:45", "13:00")))
                .containsExactly(span("09:00", "10:00"), span("13:00", "18:00"));
    }

    @Test
    void takenStretchesReachingOutsideTheHoursAreCutToThem() {
        assertThat(windows("09:00", "18:00", span("08:00", "10:00"), span("17:00", "19:00")))
                .containsExactly(span("10:00", "17:00"));
    }

    @Test
    void takenStretchesWhollyOutsideTheHoursChangeNothing() {
        assertThat(windows("09:00", "18:00", span("07:00", "08:00"), span("18:00", "19:00"), span("20:00", "21:00")))
                .containsExactly(span("09:00", "18:00"));
    }

    // --- a date change replaces the weekly hours on that date (AC-8) ----------------------------------------------

    private static MemberWorkingDay decide(WorkDay weekly, WorkDay change) {
        return ScheduleRules.decide(A_MONDAY, weekly, change, false, List.of());
    }

    @Test
    void withNoChangeTheWeeklyHoursDecide() {
        MemberWorkingDay day = decide(monday("09:00", "18:00", span("13:00", "14:00")), null);

        assertThat(day)
                .isEqualTo(new MemberWorkingDay(
                        A_MONDAY, true, List.of(span("09:00", "13:00"), span("14:00", "18:00")), DecidedBy.WEEK));
    }

    @Test
    void aWorkingDayBecomesNotWorking() {
        MemberWorkingDay day = decide(monday("09:00", "18:00", span("13:00", "14:00")), WorkDay.off(DayOfWeek.MONDAY));

        assertThat(day).isEqualTo(new MemberWorkingDay(A_MONDAY, false, List.of(), DecidedBy.DATE_CHANGE));
    }

    @Test
    void aDayOffBecomesWorking() {
        MemberWorkingDay day = decide(WorkDay.off(DayOfWeek.MONDAY), monday("10:00", "14:00"));

        assertThat(day)
                .isEqualTo(
                        new MemberWorkingDay(A_MONDAY, true, List.of(span("10:00", "14:00")), DecidedBy.DATE_CHANGE));
    }

    @Test
    void differentHoursReplaceBothTheTimesAndTheBreaksOfTheWeek() {
        MemberWorkingDay day = decide(
                monday("09:00", "18:00", span("13:00", "14:00")), monday("08:00", "12:00", span("10:00", "10:15")));

        assertThat(day.decidedBy()).isEqualTo(DecidedBy.DATE_CHANGE);
        assertThat(day.windows()).containsExactly(span("08:00", "10:00"), span("10:15", "12:00"));
    }

    @Test
    void differentHoursWithNoBreaksDropTheBreaksOfTheWeek() {
        MemberWorkingDay day = decide(monday("09:00", "18:00", span("13:00", "14:00")), monday("09:00", "18:00"));

        assertThat(day.windows()).containsExactly(span("09:00", "18:00"));
    }

    // --- leave and blocked time (F06-S03 AC-7) --------------------------------------------------------------------

    private static final WorkDay NINE_TO_SIX_WITH_LUNCH = monday("09:00", "18:00", span("13:00", "14:00"));

    private static MemberWorkingDay blocked(WorkDay weekly, WorkDay change, TimeWindow... blocks) {
        return ScheduleRules.decide(A_MONDAY, weekly, change, false, List.of(blocks));
    }

    private static void left(MemberWorkingDay day, TimeWindow... windows) {
        assertThat(day.windows()).containsExactly(windows);
        assertThat(day.working()).isTrue();
        assertThat(day.decidedBy()).isEqualTo(DecidedBy.BLOCKED_TIME);
    }

    private static void nothingLeft(MemberWorkingDay day) {
        assertThat(day).isEqualTo(new MemberWorkingDay(A_MONDAY, false, List.of(), DecidedBy.BLOCKED_TIME));
    }

    @Test
    void leaveBeatsTheWeek() {
        assertThat(ScheduleRules.decide(A_MONDAY, NINE_TO_SIX_WITH_LUNCH, null, true, List.of()))
                .isEqualTo(new MemberWorkingDay(A_MONDAY, false, List.of(), DecidedBy.LEAVE));
    }

    @Test
    void leaveBeatsADateChangeThatGivesHours() {
        assertThat(ScheduleRules.decide(A_MONDAY, NINE_TO_SIX_WITH_LUNCH, monday("10:00", "12:00"), true, List.of()))
                .isEqualTo(new MemberWorkingDay(A_MONDAY, false, List.of(), DecidedBy.LEAVE));
    }

    @Test
    void leaveDecidesADayThatWasNotWorkedAnywayAndABlockOnItChangesNothing() {
        assertThat(ScheduleRules.decide(
                        A_MONDAY, WorkDay.off(DayOfWeek.MONDAY), null, true, List.of(span("10:00", "11:00"))))
                .isEqualTo(new MemberWorkingDay(A_MONDAY, false, List.of(), DecidedBy.LEAVE));
    }

    @Test
    void aBlockInTheMiddleOfAWindowSplitsIt() {
        left(
                blocked(NINE_TO_SIX_WITH_LUNCH, null, span("10:00", "11:00")),
                span("09:00", "10:00"),
                span("11:00", "13:00"),
                span("14:00", "18:00"));
    }

    @Test
    void aBlockFromTheStartOfTheDayLeavesNoEmptyWindowBeforeIt() {
        left(
                blocked(NINE_TO_SIX_WITH_LUNCH, null, span("09:00", "10:00")),
                span("10:00", "13:00"),
                span("14:00", "18:00"));
    }

    @Test
    void aBlockToTheFinishOfTheDayLeavesNoEmptyWindowAfterIt() {
        left(
                blocked(NINE_TO_SIX_WITH_LUNCH, null, span("17:00", "18:00")),
                span("09:00", "13:00"),
                span("14:00", "17:00"));
    }

    @Test
    void aBlockStartingBeforeTheHoursTakesOnlyWhatIsInThem() {
        left(
                blocked(NINE_TO_SIX_WITH_LUNCH, null, span("07:00", "09:30")),
                span("09:30", "13:00"),
                span("14:00", "18:00"));
    }

    @Test
    void aBlockFinishingAfterTheHoursTakesOnlyWhatIsInThem() {
        left(
                blocked(NINE_TO_SIX_WITH_LUNCH, null, span("17:30", "20:00")),
                span("09:00", "13:00"),
                span("14:00", "17:30"));
    }

    @Test
    void aBlockTouchingABreakOnEitherSideLeavesNoTouchingWindows() {
        left(
                blocked(NINE_TO_SIX_WITH_LUNCH, null, span("12:00", "13:00"), span("14:00", "15:00")),
                span("09:00", "12:00"),
                span("15:00", "18:00"));
    }

    @Test
    void aBlockAcrossABreakTakesBothAndLeavesTheEitherSide() {
        left(
                blocked(NINE_TO_SIX_WITH_LUNCH, null, span("12:30", "14:30")),
                span("09:00", "12:30"),
                span("14:30", "18:00"));
    }

    @Test
    void aBlockAcrossABreakFromTheStartLeavesOnlyTheAfternoon() {
        left(blocked(NINE_TO_SIX_WITH_LUNCH, null, span("08:00", "15:00")), span("15:00", "18:00"));
    }

    @Test
    void touchingBlocksLeaveNoEmptyWindowBetweenThem() {
        left(
                blocked(monday("09:00", "18:00"), null, span("10:00", "11:00"), span("11:00", "12:00")),
                span("09:00", "10:00"),
                span("12:00", "18:00"));
    }

    @Test
    void blocksInAnyOrderGiveTheSameWindows() {
        left(
                blocked(monday("09:00", "18:00"), null, span("15:00", "16:00"), span("10:00", "11:00")),
                span("09:00", "10:00"),
                span("11:00", "15:00"),
                span("16:00", "18:00"));
    }

    @Test
    void aBlockOverTheWholeDayLeavesItNotWorked() {
        nothingLeft(blocked(monday("09:00", "18:00"), null, span("09:00", "18:00")));
    }

    @Test
    void aBlockBeyondTheWholeDayLeavesItNotWorked() {
        nothingLeft(blocked(NINE_TO_SIX_WITH_LUNCH, null, span("00:00", "23:45")));
    }

    @Test
    void blocksCoveringAllThatIsLeftAroundABreakLeaveItNotWorked() {
        nothingLeft(blocked(NINE_TO_SIX_WITH_LUNCH, null, span("09:00", "13:00"), span("14:00", "18:00")));
    }

    @Test
    void touchingBlocksCoveringTheWholeDayLeaveItNotWorked() {
        nothingLeft(blocked(monday("09:00", "18:00"), null, span("09:00", "12:00"), span("12:00", "18:00")));
    }

    @Test
    void aBlockInsideABreakTakesNothingAndDoesNotDecideTheDay() {
        MemberWorkingDay day = blocked(NINE_TO_SIX_WITH_LUNCH, null, span("13:15", "13:45"));

        assertThat(day.windows()).containsExactly(span("09:00", "13:00"), span("14:00", "18:00"));
        assertThat(day.decidedBy()).isEqualTo(DecidedBy.WEEK);
    }

    @Test
    void aBlockOutsideTheHoursTakesNothingAndDoesNotDecideTheDay() {
        MemberWorkingDay day = blocked(monday("09:00", "18:00"), null, span("07:00", "09:00"), span("18:00", "20:00"));

        assertThat(day.windows()).containsExactly(span("09:00", "18:00"));
        assertThat(day.decidedBy()).isEqualTo(DecidedBy.WEEK);
    }

    @Test
    void aBlockOnADayNotWorkedChangesNothing() {
        assertThat(blocked(WorkDay.off(DayOfWeek.MONDAY), null, span("10:00", "11:00")))
                .isEqualTo(new MemberWorkingDay(A_MONDAY, false, List.of(), DecidedBy.WEEK));
    }

    @Test
    void aBlockComesOutOfADateChangesHoursAndNotTheWeeks() {
        left(
                blocked(
                        NINE_TO_SIX_WITH_LUNCH,
                        monday("10:00", "16:00", span("12:00", "12:30")),
                        span("15:00", "17:00")),
                span("10:00", "12:00"),
                span("12:30", "15:00"));
    }

    @Test
    void aBlockOutsideADateChangesHoursLeavesTheDateChangeDeciding() {
        MemberWorkingDay day = blocked(NINE_TO_SIX_WITH_LUNCH, monday("10:00", "12:00"), span("15:00", "17:00"));

        assertThat(day.windows()).containsExactly(span("10:00", "12:00"));
        assertThat(day.decidedBy()).isEqualTo(DecidedBy.DATE_CHANGE);
    }

    @Test
    void blocksCoveringADateChangesHoursLeaveItNotWorked() {
        nothingLeft(blocked(NINE_TO_SIX_WITH_LUNCH, monday("10:00", "12:00"), span("10:00", "12:00")));
    }

    @Test
    void aDateChangeOfNotWorkingStaysDecidingThoughABlockIsOnIt() {
        assertThat(blocked(NINE_TO_SIX_WITH_LUNCH, WorkDay.off(DayOfWeek.MONDAY), span("10:00", "11:00")))
                .isEqualTo(new MemberWorkingDay(A_MONDAY, false, List.of(), DecidedBy.DATE_CHANGE));
    }

    // --- a new person's week (AC-1) -------------------------------------------------------------------------------

    @Test
    void aSalonThatHasNeverSavedHoursGivesAWeekOfNoWorkingDays() {
        assertThat(ScheduleRules.copiedFrom(null)).hasSize(7).noneMatch(WorkDay::working);
    }

    @Test
    void eachOpenWeekdayIsAWorkingDayWithNoBreakAndEachClosedOneIsNot() {
        List<WeekdayHours> opening = List.of(
                new WeekdayHours(DayOfWeek.MONDAY, at("09:00"), at("18:00")),
                new WeekdayHours(DayOfWeek.TUESDAY, null, null));

        assertThat(ScheduleRules.copiedFrom(opening))
                .containsExactly(monday("09:00", "18:00"), WorkDay.off(DayOfWeek.TUESDAY));
    }

    @Test
    void onlyTheWeekdaysThatDifferAreChanged() {
        List<WorkDay> before = ScheduleRules.noWorkingDays();
        List<WorkDay> after = new java.util.ArrayList<>(before);
        after.set(0, monday("09:00", "18:00"));

        assertThat(ScheduleRules.changedDays(before, after)).containsExactly(DayOfWeek.MONDAY);
        assertThat(ScheduleRules.changedDays(before, before)).isEmpty();
    }
}
