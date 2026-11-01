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
                        false));
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

        assertThat(on(nextMonday)).isEqualTo(new MemberWorkingDay(nextMonday, false, List.of(), false));
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

        assertThat(on(nextMonday)).isEqualTo(new MemberWorkingDay(nextMonday, false, List.of(), true));
        assertThat(on(nextMonday.plusWeeks(1)))
                .isEqualTo(new MemberWorkingDay(
                        nextMonday.plusWeeks(1),
                        true,
                        List.of(window("09:00", "13:00"), window("14:00", "18:00")),
                        false));
    }

    @Test
    void aDayOffBecomesWorkingOnThatDateOnly() throws Exception {
        saveWeek(week(workday("tuesday", "09:00", "18:00")));
        change(differentHours(nextMonday, "10:00", "14:00", null));

        assertThat(on(nextMonday))
                .isEqualTo(new MemberWorkingDay(nextMonday, true, List.of(window("10:00", "14:00")), true));
        assertThat(on(nextMonday.plusWeeks(1)).working()).isFalse();
    }

    @Test
    void differentHoursReplaceBothTheTimesAndTheBreaks() throws Exception {
        saveWeek(week(workday("monday", "09:00", "18:00", pause("13:00", "14:00"))));
        change(differentHours(nextMonday, "08:00", "12:00", null, pause("10:00", "10:15")));
        change(differentHours(nextMonday.plusWeeks(1), "09:00", "18:00", null));

        assertThat(on(nextMonday).windows()).containsExactly(window("08:00", "10:00"), window("10:15", "12:00"));
        assertThat(on(nextMonday).fromDateChange()).isTrue();
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
                .isEqualTo(new MemberWorkingDay(nextMonday, true, List.of(window("09:00", "18:00")), false));
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
                        new MemberWorkingDay(nextMonday, true, List.of(window("09:00", "18:00")), false),
                        new MemberWorkingDay(nextMonday.plusDays(1), true, List.of(window("10:00", "11:00")), true),
                        new MemberWorkingDay(nextMonday.plusDays(2), true, List.of(window("09:00", "12:00")), false),
                        new MemberWorkingDay(nextMonday.plusDays(3), false, List.of(), false));

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

        assertThat(on(nextMonday)).isEqualTo(new MemberWorkingDay(nextMonday, false, List.of(), false));
        assertThat(on(nextMonday.plusDays(1)))
                .isEqualTo(new MemberWorkingDay(nextMonday.plusDays(1), false, List.of(), false));
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
                .isEqualTo(new MemberWorkingDay(nextMonday, false, List.of(), false));
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
                        "WHEN 'staff_member_date_change_break'");
    }
}
