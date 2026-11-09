package com.salonplatform.staff;

import static com.salonplatform.staff.TeamFixture.dateChanges;
import static com.salonplatform.staff.TeamFixture.differentHours;
import static com.salonplatform.staff.TeamFixture.notWorking;
import static com.salonplatform.staff.TeamFixture.pause;
import static com.salonplatform.staff.TeamFixture.schedule;
import static com.salonplatform.staff.TeamFixture.week;
import static com.salonplatform.staff.TeamFixture.weekSave;
import static com.salonplatform.staff.TeamFixture.weekly;
import static com.salonplatform.staff.TeamFixture.workday;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.salonplatform.identity.domain.StaffRole;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.shared.tenancy.TenantNotFoundException;
import com.salonplatform.shared.web.NotFoundException;
import com.salonplatform.staff.MemberWorkingDay.DecidedBy;
import com.salonplatform.testing.Http;
import com.salonplatform.testing.TestSalons;
import com.salonplatform.testing.TestSalons.TestSalon;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link TeamSchedule}, the contract F07 trusts (F06-S02 AC-8, AC-9, AC-14): the person's windows after breaks and date
 * changes, for the caller's salon only, read back after real saves through the API, with hand-written expected windows.
 */
class TeamScheduleContractIntegrationTest extends TwoSalonTeam {

    @Autowired
    TeamSchedule schedule;

    @Autowired
    OpeningSchedule opening;

    private LocalDate today;
    private LocalDate nextMonday;
    private UUID nimali;

    @BeforeEach
    void aPerson() {
        today = LocalDate.ofInstant(clock.instant(), TestSalons.DEFAULT_TIME_ZONE);
        nextMonday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        nimali = UUID.fromString(added("Nimali").get("id").toString());
    }

    private <T> T as(TestSalon salon, Callable<T> work) throws Exception {
        return TenantContext.callAs(salons.asCurrent(salon), work);
    }

    private static TimeWindow window(String starts, String finishes) {
        return new TimeWindow(LocalTime.parse(starts), LocalTime.parse(finishes));
    }

    private void saveWeek(List<Map<String, Object>> days) {
        Http.Exchange read = api.call(alpha, "GET", schedule(nimali), null, salonOwner);
        status(read, 200);
        long version = ((Number) api.body(read).get("version")).longValue();
        status(api.call(alpha, "PUT", weekly(nimali), weekSave(version, days), salonOwner), 200);
    }

    private Map<String, Object> change(Map<String, Object> body) {
        Http.Exchange added = api.call(alpha, "POST", dateChanges(nimali), body, salonOwner);
        status(added, 201);
        return map(api.body(added).get("change"));
    }

    private MemberWorkingDay on(LocalDate date) throws Exception {
        return as(alpha, () -> schedule.on(nimali, date));
    }

    // --- the windows, from what was really saved ------------------------------------------------------------------

    @Test
    void aWorkingDayIsItsHoursWithTheBreaksTakenOutInTimeOrder() throws Exception {
        saveWeek(week(workday("monday", "09:00", "18:00", pause("15:00", "15:30"), pause("13:00", "14:00"))));

        assertThat(on(nextMonday))
                .isEqualTo(new MemberWorkingDay(
                        nextMonday,
                        true,
                        List.of(window("09:00", "13:00"), window("14:00", "15:00"), window("15:30", "18:00")),
                        DecidedBy.WEEK));
    }

    @Test
    void aBreakTouchingTheStartOrTheFinishLeavesNoEmptyWindow() throws Exception {
        saveWeek(week(
                workday("monday", "09:00", "18:00", pause("09:00", "10:00")),
                workday("tuesday", "09:00", "18:00", pause("17:00", "18:00")),
                workday("wednesday", "09:00", "18:00", pause("09:00", "09:15"), pause("17:45", "18:00"))));

        assertThat(on(nextMonday).windows()).containsExactly(window("10:00", "18:00"));
        assertThat(on(nextMonday.plusDays(1)).windows()).containsExactly(window("09:00", "17:00"));
        assertThat(on(nextMonday.plusDays(2)).windows()).containsExactly(window("09:15", "17:45"));
    }

    @Test
    void touchingBreaksLeaveNoWindowBetweenThem() throws Exception {
        saveWeek(week(workday("monday", "09:00", "18:00", pause("12:00", "13:00"), pause("13:00", "14:00"))));

        assertThat(on(nextMonday).windows()).containsExactly(window("09:00", "12:00"), window("14:00", "18:00"));
    }

    @Test
    void aDayNotWorkedHasNoWindows() throws Exception {
        saveWeek(week(workday("tuesday", "09:00", "18:00")));

        assertThat(on(nextMonday)).isEqualTo(new MemberWorkingDay(nextMonday, false, List.of(), DecidedBy.WEEK));
    }

    /** F07 relies on it: a working day always has somewhere to book, as breaks that fill a day are refused. */
    @Test
    void aWorkingAnswerAlwaysHasAWindowAndANonWorkingOneNever() throws Exception {
        saveWeek(week(
                workday("monday", "09:00", "18:00", pause("09:00", "17:45")),
                workday("tuesday", "09:00", "18:00", pause("09:15", "18:00")),
                workday("wednesday", "09:00", "09:15"),
                workday("thursday", "09:00", "18:00", pause("09:00", "13:00"), pause("13:00", "17:45")),
                workday("saturday", "00:00", "23:45")));
        change(differentHours(nextMonday.plusDays(4), "09:00", "10:00", null, pause("09:00", "09:45")));
        change(notWorking(nextMonday.plusDays(5), null));
        change(differentHours(nextMonday.plusDays(6), "23:30", "23:45", null));

        List<MemberWorkingDay> days = as(alpha, () -> schedule.between(nimali, nextMonday, nextMonday.plusDays(13)));

        assertThat(days)
                .hasSize(14)
                .allSatisfy(day -> assertThat(day.windows().isEmpty()).isEqualTo(!day.working()));
        assertThat(days.getFirst().windows()).containsExactly(window("17:45", "18:00"));
        assertThat(days.get(1).windows()).containsExactly(window("09:00", "09:15"));
    }

    // --- AC-8: a date change replaces the weekly hours on that date only ------------------------------------------

    @Test
    void aWorkingDayBecomesNotWorkingOnThatDateOnly() throws Exception {
        saveWeek(week(workday("monday", "09:00", "18:00", pause("13:00", "14:00"))));
        change(notWorking(nextMonday, null));

        assertThat(on(nextMonday)).isEqualTo(new MemberWorkingDay(nextMonday, false, List.of(), DecidedBy.DATE_CHANGE));
        assertThat(on(nextMonday.plusWeeks(1)))
                .isEqualTo(new MemberWorkingDay(
                        nextMonday.plusWeeks(1),
                        true,
                        List.of(window("09:00", "13:00"), window("14:00", "18:00")),
                        DecidedBy.WEEK));
    }

    @Test
    void aDayOffBecomesWorkingOnThatDateOnly() throws Exception {
        saveWeek(week(workday("tuesday", "09:00", "18:00")));
        change(differentHours(nextMonday, "10:00", "14:00", null));

        assertThat(on(nextMonday))
                .isEqualTo(new MemberWorkingDay(
                        nextMonday, true, List.of(window("10:00", "14:00")), DecidedBy.DATE_CHANGE));
        assertThat(on(nextMonday.plusWeeks(1)).working()).isFalse();
    }

    @Test
    void differentHoursReplaceBothTheTimesAndTheBreaks() throws Exception {
        saveWeek(week(workday("monday", "09:00", "18:00", pause("13:00", "14:00"))));
        change(differentHours(nextMonday, "08:00", "12:00", null, pause("10:00", "10:15")));
        change(differentHours(nextMonday.plusWeeks(1), "09:00", "18:00", null));

        assertThat(on(nextMonday).windows()).containsExactly(window("08:00", "10:00"), window("10:15", "12:00"));
        assertThat(on(nextMonday).decidedBy()).isEqualTo(MemberWorkingDay.DecidedBy.DATE_CHANGE);
        assertThat(on(nextMonday.plusWeeks(1)).windows())
                .as("no break of the week survives on a date with its own hours")
                .containsExactly(window("09:00", "18:00"));
    }

    @Test
    void removingAChangePutsTheWeeklyHoursBackAtTheNextCall() throws Exception {
        saveWeek(week(workday("monday", "09:00", "18:00")));
        Map<String, Object> added = change(notWorking(nextMonday, null));
        assertThat(on(nextMonday).working()).isFalse();

        status(api.call(alpha, "DELETE", dateChanges(nimali) + "/" + added.get("id"), null, salonOwner), 204);

        assertThat(on(nextMonday))
                .isEqualTo(new MemberWorkingDay(nextMonday, true, List.of(window("09:00", "18:00")), DecidedBy.WEEK));
    }

    @Test
    void aWeeklySaveLeavesADateChangeInCharge() throws Exception {
        saveWeek(week(workday("monday", "09:00", "18:00")));
        change(differentHours(nextMonday, "08:00", "12:00", null));

        saveWeek(week(workday("monday", "10:00", "11:00")));

        assertThat(on(nextMonday).windows()).containsExactly(window("08:00", "12:00"));
        assertThat(on(nextMonday.plusWeeks(1)).windows()).containsExactly(window("10:00", "11:00"));
    }

    // --- AC-9 -----------------------------------------------------------------------------------------------------

    @Test
    void aRangeAnswersEachDateInOrderAndAChangeAppliesToTheNextCall() throws Exception {
        saveWeek(week(workday("monday", "09:00", "18:00"), workday("wednesday", "09:00", "12:00")));
        change(differentHours(nextMonday.plusDays(1), "10:00", "11:00", null));

        List<MemberWorkingDay> days = as(alpha, () -> schedule.between(nimali, nextMonday, nextMonday.plusDays(3)));

        assertThat(days)
                .containsExactly(
                        new MemberWorkingDay(nextMonday, true, List.of(window("09:00", "18:00")), DecidedBy.WEEK),
                        new MemberWorkingDay(
                                nextMonday.plusDays(1), true, List.of(window("10:00", "11:00")), DecidedBy.DATE_CHANGE),
                        new MemberWorkingDay(
                                nextMonday.plusDays(2), true, List.of(window("09:00", "12:00")), DecidedBy.WEEK),
                        new MemberWorkingDay(nextMonday.plusDays(3), false, List.of(), DecidedBy.WEEK));

        saveWeek(week(workday("monday", "11:00", "12:00")));

        assertThat(on(nextMonday).windows()).containsExactly(window("11:00", "12:00"));
    }

    @Test
    void aRangeOf400DaysIsAnsweredAndALongerOrReversedOneIsRefused() throws Exception {
        assertThat(as(alpha, () -> schedule.between(nimali, today, today.plusDays(399))))
                .hasSize(400);
        assertThat(as(alpha, () -> schedule.between(nimali, today, today))).hasSize(1);

        assertThatThrownBy(() -> as(alpha, () -> schedule.between(nimali, today, today.plusDays(400))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> as(alpha, () -> schedule.between(nimali, today, today.minusDays(1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void itIgnoresTheSalonsHoursSoADayTheSalonIsClosedStillAnswersThePersonsHours() throws Exception {
        // The salon has never saved hours, so it is closed every day; and it closes this date on top of that.
        saveWeek(week(workday("monday", "09:00", "18:00")));
        String manager = settings.signedIn(alpha, StaffRole.MANAGER).token();
        Map<String, Object> closure = new java.util.LinkedHashMap<>();
        closure.put("date", nextMonday.toString());
        status(settings.call(alpha, "POST", "/api/v1/salon/closures", closure, manager), 201);

        assertThat(on(nextMonday).windows()).containsExactly(window("09:00", "18:00"));
    }

    @Test
    void aRemovedMemberWorksNoDateEvenWithADateChange() throws Exception {
        saveWeek(week(workday("monday", "09:00", "18:00")));
        change(differentHours(nextMonday.plusDays(1), "09:00", "18:00", null));
        status(
                api.call(
                        alpha,
                        "DELETE",
                        TeamFixture.MEMBERS + "/" + nimali + "?version="
                                + read(nimali).get("version"),
                        null,
                        salonOwner),
                204);

        assertThat(on(nextMonday)).isEqualTo(new MemberWorkingDay(nextMonday, false, List.of(), DecidedBy.WEEK));
        assertThat(on(nextMonday.plusDays(1)))
                .isEqualTo(new MemberWorkingDay(nextMonday.plusDays(1), false, List.of(), DecidedBy.WEEK));
    }

    @Test
    void aMemberAddedBeforeThisStageWorksNoDate() throws Exception {
        UUID earlier = UUID.randomUUID();
        owner.update(
                "INSERT INTO staff_team_member (tenant_id, id, branch_id, name, shown_online, version, created_at,"
                        + " updated_at) VALUES (?, ?, ?, 'Earlier', true, 0, now(), now())",
                alpha.id(),
                earlier,
                salons.defaultBranch(alpha));

        assertThat(as(alpha, () -> schedule.on(earlier, nextMonday)))
                .isEqualTo(new MemberWorkingDay(nextMonday, false, List.of(), DecidedBy.WEEK));
    }

    // --- AC-14 ----------------------------------------------------------------------------------------------------

    @Test
    void anUnknownIdOrAnotherSalonsIsNotFoundAndTheirHoursAreNotReadable() throws Exception {
        saveWeek(week(workday("monday", "09:00", "18:00")));

        assertThatThrownBy(() -> as(beta, () -> schedule.on(nimali, nextMonday)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> as(beta, () -> schedule.between(nimali, today, today.plusDays(7))))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> as(alpha, () -> schedule.on(UUID.randomUUID(), nextMonday)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void withNoSalonInTheRequestItRefuses() {
        assertThatThrownBy(() -> schedule.on(nimali, today)).isInstanceOf(TenantNotFoundException.class);
    }

    // --- the erasure function learned the new tables ----------------------------------------------------------------

    @Test
    void theEraserKnowsEveryNewTableAndRefusesOneItDoesNot() {
        // Its retention guard refuses a salon whose retention has not passed before it reaches the table: the guard
        // holds, and the table names are read from the function itself.
        String function = owner.queryForObject(
                "SELECT pg_get_functiondef('staff_erase_salon_rows(uuid,text,timestamptz,integer)'::regprocedure)",
                String.class);

        assertThat(function)
                .contains(
                        "WHEN 'staff_member_schedule'",
                        "WHEN 'staff_member_weekday'",
                        "WHEN 'staff_member_weekday_break'",
                        "WHEN 'staff_member_date_change'",
                        "WHEN 'staff_member_date_change_break'",
                        "WHEN 'staff_member_leave'",
                        "WHEN 'staff_member_blocked_time'");
    }

    // --- F06-S03 AC-7: leave and blocked time taken out, after real saves through the API
    // ------------------------------

    private Object leaveAdded(LocalDate first, LocalDate last) {
        Http.Exchange added = api.call(
                alpha, "POST", TeamFixture.leave(nimali), TeamFixture.leaveOf(first, last, "annual", null), salonOwner);
        status(added, 201);
        return map(api.body(added).get("leave")).get("id");
    }

    private Object blockAdded(LocalDate date, String starts, String finishes) {
        Http.Exchange added = api.call(
                alpha,
                "POST",
                TeamFixture.blockedTime(nimali),
                TeamFixture.blockOf(date, starts, finishes, "Blocked"),
                salonOwner);
        status(added, 201);
        return map(api.body(added).get("block")).get("id");
    }

    private void removed(String path, Object id) {
        status(api.call(alpha, "DELETE", path + "/" + id, null, salonOwner), 204);
    }

    private static final Map<String, Object> A_WEEKDAY = workday("monday", "09:00", "18:00", pause("13:00", "14:00"));

    @Test
    void aDateWithinLeaveIsNotWorkedAndTheDaysEitherSideAreUntouched() throws Exception {
        saveWeek(week(
                workday("monday", "09:00", "18:00"),
                workday("tuesday", "09:00", "18:00"),
                workday("wednesday", "09:00", "18:00"),
                workday("thursday", "09:00", "18:00")));
        leaveAdded(nextMonday.plusDays(1), nextMonday.plusDays(2));

        assertThat(as(alpha, () -> schedule.between(nimali, nextMonday, nextMonday.plusDays(3))))
                .containsExactly(
                        new MemberWorkingDay(nextMonday, true, List.of(window("09:00", "18:00")), DecidedBy.WEEK),
                        new MemberWorkingDay(nextMonday.plusDays(1), false, List.of(), DecidedBy.LEAVE),
                        new MemberWorkingDay(nextMonday.plusDays(2), false, List.of(), DecidedBy.LEAVE),
                        new MemberWorkingDay(
                                nextMonday.plusDays(3), true, List.of(window("09:00", "18:00")), DecidedBy.WEEK));
    }

    @Test
    void leaveBeatsADateChangeWhicheverWasSavedFirstAndRemovingItBringsTheChangeBack() throws Exception {
        saveWeek(week(A_WEEKDAY));
        change(differentHours(nextMonday, "10:00", "12:00", null));
        Object leave = leaveAdded(nextMonday, nextMonday);

        assertThat(on(nextMonday)).isEqualTo(new MemberWorkingDay(nextMonday, false, List.of(), DecidedBy.LEAVE));

        removed(TeamFixture.leave(nimali), leave);

        assertThat(on(nextMonday))
                .isEqualTo(new MemberWorkingDay(
                        nextMonday, true, List.of(window("10:00", "12:00")), DecidedBy.DATE_CHANGE));

        Object second = leaveAdded(nextMonday.plusWeeks(1), nextMonday.plusWeeks(1));
        change(differentHours(nextMonday.plusWeeks(1), "10:00", "12:00", null));

        assertThat(on(nextMonday.plusWeeks(1)).decidedBy()).isEqualTo(DecidedBy.LEAVE);
        assertThat(second).isNotNull();
    }

    @Test
    void removingLeaveRestoresTheWeeksAnswerOnTheNextCall() throws Exception {
        saveWeek(week(A_WEEKDAY));
        MemberWorkingDay before = on(nextMonday);
        Object leave = leaveAdded(nextMonday, nextMonday);
        assertThat(on(nextMonday).working()).isFalse();

        removed(TeamFixture.leave(nimali), leave);

        assertThat(on(nextMonday)).isEqualTo(before);
    }

    @Test
    void aBlockAtEachEdgeAndAcrossTheBreakIsTakenOut() throws Exception {
        saveWeek(week(
                A_WEEKDAY,
                workday("tuesday", "09:00", "18:00", pause("13:00", "14:00")),
                workday("wednesday", "09:00", "18:00", pause("13:00", "14:00")),
                workday("thursday", "09:00", "18:00", pause("13:00", "14:00"))));
        blockAdded(nextMonday, "09:00", "10:00");
        blockAdded(nextMonday.plusDays(1), "17:00", "18:00");
        blockAdded(nextMonday.plusDays(2), "12:30", "14:30");
        blockAdded(nextMonday.plusDays(3), "07:00", "09:15");

        assertThat(on(nextMonday).windows()).containsExactly(window("10:00", "13:00"), window("14:00", "18:00"));
        assertThat(on(nextMonday.plusDays(1)).windows())
                .containsExactly(window("09:00", "13:00"), window("14:00", "17:00"));
        assertThat(on(nextMonday.plusDays(2)).windows())
                .containsExactly(window("09:00", "12:30"), window("14:30", "18:00"));
        assertThat(on(nextMonday.plusDays(3)).windows())
                .containsExactly(window("09:15", "13:00"), window("14:00", "18:00"));
        assertThat(on(nextMonday).decidedBy()).isEqualTo(DecidedBy.BLOCKED_TIME);
    }

    @Test
    void touchingBlocksCoveringAllThatIsLeftLeaveTheDayNotWorkedAndWorkingAlwaysHasAWindow() throws Exception {
        saveWeek(week(A_WEEKDAY));
        blockAdded(nextMonday, "09:00", "13:00");
        blockAdded(nextMonday, "14:00", "16:00");
        Object last = blockAdded(nextMonday, "16:00", "18:00");

        assertThat(on(nextMonday))
                .isEqualTo(new MemberWorkingDay(nextMonday, false, List.of(), DecidedBy.BLOCKED_TIME));

        removed(TeamFixture.blockedTime(nimali), last);

        assertThat(on(nextMonday))
                .isEqualTo(new MemberWorkingDay(
                        nextMonday, true, List.of(window("16:00", "18:00")), DecidedBy.BLOCKED_TIME));
    }

    @Test
    void aBlockComesOutOfADateChangesHoursAndIsNotAddedToTheWeek() throws Exception {
        saveWeek(week(A_WEEKDAY));
        change(differentHours(nextMonday, "10:00", "16:00", null));
        blockAdded(nextMonday, "11:00", "12:00");

        assertThat(on(nextMonday).windows()).containsExactly(window("10:00", "11:00"), window("12:00", "16:00"));
        assertThat(on(nextMonday).decidedBy()).isEqualTo(DecidedBy.BLOCKED_TIME);
    }

    @Test
    void aBlockOutsideTheHoursOrOnLeaveChangesNothing() throws Exception {
        saveWeek(week(A_WEEKDAY));
        blockAdded(nextMonday, "06:00", "08:00");

        assertThat(on(nextMonday).windows()).containsExactly(window("09:00", "13:00"), window("14:00", "18:00"));
        assertThat(on(nextMonday).decidedBy()).isEqualTo(DecidedBy.WEEK);

        Object leave = leaveAdded(nextMonday.plusWeeks(1), nextMonday.plusWeeks(1));
        Object onLeave = blockAdded(nextMonday.plusWeeks(1), "10:00", "11:00");

        assertThat(on(nextMonday.plusWeeks(1)).decidedBy()).isEqualTo(DecidedBy.LEAVE);

        removed(TeamFixture.leave(nimali), leave);

        assertThat(on(nextMonday.plusWeeks(1)).windows())
                .as("the block was stored as entered, and now takes its time")
                .containsExactly(window("09:00", "10:00"), window("11:00", "13:00"), window("14:00", "18:00"));

        removed(TeamFixture.blockedTime(nimali), onLeave);

        assertThat(on(nextMonday.plusWeeks(1)).decidedBy()).isEqualTo(DecidedBy.WEEK);
    }

    @Test
    void removingABlockRestoresTheAnswerOnTheNextCall() throws Exception {
        saveWeek(week(A_WEEKDAY));
        MemberWorkingDay before = on(nextMonday);
        Object block = blockAdded(nextMonday, "10:00", "11:00");
        assertThat(on(nextMonday)).isNotEqualTo(before);

        removed(TeamFixture.blockedTime(nimali), block);

        assertThat(on(nextMonday)).isEqualTo(before);
    }

    @Test
    void acrossARangeWorkingAlwaysHasAWindowAndNotWorkingNeverDoes() throws Exception {
        saveWeek(week(A_WEEKDAY, workday("tuesday", "09:00", "09:15"), workday("wednesday", "00:00", "23:45")));
        blockAdded(nextMonday.plusDays(1), "09:00", "09:15");
        blockAdded(nextMonday.plusDays(2), "00:00", "23:45");
        leaveAdded(nextMonday.plusWeeks(1), nextMonday.plusWeeks(2));

        assertThat(as(alpha, () -> schedule.between(nimali, today, today.plusDays(60))))
                .isNotEmpty()
                .allSatisfy(day ->
                        assertThat(day.working()).isEqualTo(!day.windows().isEmpty()));
    }

    @Test
    void aRangeAsksInAFewQueriesAndAnswersLeaveThatOnlyPartlyLiesInIt() throws Exception {
        saveWeek(week(A_WEEKDAY));
        leaveAdded(nextMonday, nextMonday.plusWeeks(1));

        assertThat(on(nextMonday.plusWeeks(1)).decidedBy())
                .as("the range starts inside the leave's last day")
                .isEqualTo(DecidedBy.LEAVE);
        assertThat(as(alpha, () -> schedule.between(nimali, nextMonday.minusDays(1), nextMonday)))
                .extracting(MemberWorkingDay::decidedBy)
                .containsExactly(DecidedBy.WEEK, DecidedBy.LEAVE);
    }

    @Test
    void anotherSalonsLeaveAndBlocksDoNotLeakThroughTheAnswer() throws Exception {
        saveWeek(week(A_WEEKDAY));
        leaveAdded(nextMonday, nextMonday);
        blockAdded(nextMonday.plusWeeks(1), "10:00", "11:00");

        assertThatThrownBy(() -> as(beta, () -> schedule.on(nimali, nextMonday)))
                .isInstanceOf(NotFoundException.class);

        // One of beta's own people on those dates is untouched by alpha's rows.
        String betaOwner = settings.signedIn(beta, StaffRole.OWNER).token();
        Http.Exchange kasun = api.call(beta, "POST", TeamFixture.MEMBERS, TeamFixture.person("Kasun"), betaOwner);
        status(kasun, 201);
        UUID betasMember = UUID.fromString(api.body(kasun).get("id").toString());
        Http.Exchange read = api.call(beta, "GET", schedule(betasMember), null, betaOwner);
        long version = ((Number) api.body(read).get("version")).longValue();
        status(api.call(beta, "PUT", weekly(betasMember), weekSave(version, week(A_WEEKDAY)), betaOwner), 200);

        assertThat(as(beta, () -> schedule.on(betasMember, nextMonday)).decidedBy())
                .isEqualTo(DecidedBy.WEEK);
        assertThat(as(beta, () -> schedule.on(betasMember, nextMonday.plusWeeks(1)))
                        .windows())
                .containsExactly(window("09:00", "13:00"), window("14:00", "18:00"));
    }

    @Test
    void itStillIgnoresTheSalonsClosuresWithLeaveAndBlocksTakenOut() throws Exception {
        saveWeek(week(A_WEEKDAY));
        blockAdded(nextMonday, "10:00", "11:00");
        String manager = settings.signedIn(alpha, StaffRole.MANAGER).token();
        Map<String, Object> closure = new java.util.LinkedHashMap<>();
        closure.put("date", nextMonday.toString());
        status(settings.call(alpha, "POST", "/api/v1/salon/closures", closure, manager), 201);

        assertThat(on(nextMonday).windows())
                .containsExactly(window("09:00", "10:00"), window("11:00", "13:00"), window("14:00", "18:00"));
    }

    @Test
    void aWeekdayClosedInTheSalonsSavedHoursStillAnswersThePersonsHours() throws Exception {
        String manager = settings.signedIn(alpha, StaffRole.MANAGER).token();
        status(
                settings.call(
                        alpha,
                        "PUT",
                        StaffSettingsFixture.HOURS_SETTINGS,
                        StaffSettingsFixture.save(0, StaffSettingsFixture.week("10:00", "12:00", "monday")),
                        manager),
                200);
        saveWeek(week(A_WEEKDAY, workday("tuesday", "09:00", "18:00")));

        OpeningDay salonsMonday = as(alpha, () -> opening.on(salons.defaultBranch(alpha), nextMonday));
        assertThat(salonsMonday.open()).isFalse();
        assertThat(on(nextMonday))
                .isEqualTo(new MemberWorkingDay(
                        nextMonday, true, List.of(window("09:00", "13:00"), window("14:00", "18:00")), DecidedBy.WEEK));
        assertThat(on(nextMonday.plusDays(1)).windows())
                .as("nor are they cut down to a day the salon is open for less")
                .containsExactly(window("09:00", "18:00"));
    }

    private void passedRow(String sql, Object... values) {
        owner.update(sql, values);
    }

    @Test
    void aPastDateIsAnsweredFromTheWeekAndFromWhatWasThenChangedOnIt() throws Exception {
        saveWeek(week(A_WEEKDAY));
        LocalDate lastMonday = today.with(TemporalAdjusters.previous(DayOfWeek.MONDAY));
        LocalDate mondayBefore = lastMonday.minusWeeks(1);
        LocalDate twoBefore = lastMonday.minusWeeks(2);
        LocalDate threeBefore = lastMonday.minusWeeks(3);
        UUID branch = salons.defaultBranch(alpha);
        UUID passedChange = UUID.randomUUID();
        passedRow(
                "INSERT INTO staff_member_date_change (tenant_id, id, branch_id, member_id, change_date, starts, finishes,"
                        + " created_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, '10:00', '12:00', ?, now(), now())",
                alpha.id(),
                passedChange,
                branch,
                nimali,
                java.sql.Date.valueOf(mondayBefore),
                UUID.randomUUID());
        passedRow(
                "INSERT INTO staff_member_date_change_break (tenant_id, id, branch_id, member_id, change_id, starts, finishes,"
                        + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, '11:00', '11:15', now(), now())",
                alpha.id(),
                UUID.randomUUID(),
                branch,
                nimali,
                passedChange);
        passedRow(
                "INSERT INTO staff_member_leave (tenant_id, id, branch_id, member_id, first_day, last_day, leave_type,"
                        + " created_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 'annual', ?, now(), now())",
                alpha.id(),
                UUID.randomUUID(),
                branch,
                nimali,
                java.sql.Date.valueOf(twoBefore),
                java.sql.Date.valueOf(twoBefore),
                UUID.randomUUID());
        passedRow(
                "INSERT INTO staff_member_blocked_time (tenant_id, id, branch_id, member_id, block_date, starts, finishes,"
                        + " label, created_by, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, '10:00', '11:00', 'Training', ?, now(), now())",
                alpha.id(),
                UUID.randomUUID(),
                branch,
                nimali,
                java.sql.Date.valueOf(threeBefore),
                UUID.randomUUID());

        assertThat(on(lastMonday))
                .as("a past date with nothing of its own reads as the week does now")
                .isEqualTo(new MemberWorkingDay(
                        lastMonday, true, List.of(window("09:00", "13:00"), window("14:00", "18:00")), DecidedBy.WEEK));
        assertThat(on(mondayBefore))
                .as("a date change that has passed still decides its date, breaks and all")
                .isEqualTo(new MemberWorkingDay(
                        mondayBefore,
                        true,
                        List.of(window("10:00", "11:00"), window("11:15", "12:00")),
                        DecidedBy.DATE_CHANGE));
        assertThat(on(twoBefore)).isEqualTo(new MemberWorkingDay(twoBefore, false, List.of(), DecidedBy.LEAVE));
        assertThat(on(threeBefore).windows())
                .containsExactly(window("09:00", "10:00"), window("11:00", "13:00"), window("14:00", "18:00"));
        assertThat(as(alpha, () -> schedule.between(nimali, threeBefore, lastMonday)))
                .as("and a range reaching back answers each")
                .hasSize(22);
    }

    @Test
    void aRemovedMemberWorksNoDateAndHasNothingSaidOfTheirLeaveOrBlocks() throws Exception {
        saveWeek(week(A_WEEKDAY));
        leaveAdded(nextMonday, nextMonday);
        blockAdded(nextMonday.plusWeeks(1), "10:00", "11:00");
        status(
                api.call(
                        alpha,
                        "DELETE",
                        TeamFixture.MEMBERS + "/" + nimali + "?version="
                                + read(nimali).get("version"),
                        null,
                        salonOwner),
                204);

        assertThat(on(nextMonday)).isEqualTo(new MemberWorkingDay(nextMonday, false, List.of(), DecidedBy.WEEK));
        assertThat(on(nextMonday.plusWeeks(1)))
                .isEqualTo(new MemberWorkingDay(nextMonday.plusWeeks(1), false, List.of(), DecidedBy.WEEK));
    }
}
