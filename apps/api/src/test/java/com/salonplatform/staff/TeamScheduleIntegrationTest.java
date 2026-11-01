package com.salonplatform.staff;

import static com.salonplatform.staff.TeamFixture.dateChanges;
import static com.salonplatform.staff.TeamFixture.differentHours;
import static com.salonplatform.staff.TeamFixture.notWorking;
import static com.salonplatform.staff.TeamFixture.offDay;
import static com.salonplatform.staff.TeamFixture.pause;
import static com.salonplatform.staff.TeamFixture.schedule;
import static com.salonplatform.staff.TeamFixture.week;
import static com.salonplatform.staff.TeamFixture.weekSave;
import static com.salonplatform.staff.TeamFixture.weekly;
import static com.salonplatform.staff.TeamFixture.workday;
import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.identity.domain.StaffRole;
import com.salonplatform.testing.Http;
import com.salonplatform.testing.TestSalons;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A person's weekly hours and date changes through the API (F06-S02), each answer held to the committed contract. */
class TeamScheduleIntegrationTest extends TwoSalonTeam {

    private LocalDate today;
    private Object nimali;

    @BeforeEach
    void aPerson() {
        today = LocalDate.ofInstant(clock.instant(), TestSalons.DEFAULT_TIME_ZONE);
        nimali = added("Nimali").get("id");
    }

    private Map<String, Object> readSchedule(Object member) {
        Http.Exchange read = api.call(alpha, "GET", schedule(member), null, salonOwner);
        status(read, 200);
        return api.body(read);
    }

    private long version(Object member) {
        return ((Number) readSchedule(member).get("version")).longValue();
    }

    private Http.Exchange save(Object member, long version, List<Map<String, Object>> week) {
        return api.call(alpha, "PUT", weekly(member), weekSave(version, week), salonOwner);
    }

    /** Saves this week from the current version, and answers the new one. */
    private long saved(Object member, List<Map<String, Object>> week) {
        Http.Exchange answer = save(member, version(member), week);
        status(answer, 200);
        return ((Number) api.body(answer).get("version")).longValue();
    }

    private Map<String, Object> monday(Object member) {
        return maps(readSchedule(member).get("weekly")).getFirst();
    }

    private Http.Exchange addChange(Object member, Map<String, Object> change) {
        return api.call(alpha, "POST", dateChanges(member), change, salonOwner);
    }

    private Map<String, Object> addedChange(Object member, Map<String, Object> change) {
        Http.Exchange answer = addChange(member, change);
        status(answer, 201);
        return map(api.body(answer).get("change"));
    }

    private void salonHours(String opens, String closes) {
        List<Map<String, Object>> days = new ArrayList<>();
        for (String day : List.of("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")) {
            boolean open = !"sunday".equals(day);
            days.add(new java.util.LinkedHashMap<>(Map.of(
                    "day", day,
                    "open", open)));
            days.getLast().put("opens", open ? opens : null);
            days.getLast().put("closes", open ? closes : null);
        }
        Http.Exchange read = settings.call(alpha, "GET", "/api/v1/salon/opening-hours/settings", null, salonOwner);
        status(read, 200);
        Map<String, Object> save = new java.util.LinkedHashMap<>();
        save.put("version", api.body(read).get("version"));
        save.put("weekly", days);
        status(settings.call(alpha, "PUT", "/api/v1/salon/opening-hours/settings", save, salonOwner), 200);
    }

    private void addedBeforeThisStage(UUID id, String name) {
        owner.update(
                "INSERT INTO staff_team_member (tenant_id, id, branch_id, name, shown_online, version, created_at,"
                        + " updated_at) VALUES (?, ?, ?, ?, true, 0, now(), now())",
                alpha.id(),
                id,
                salons.defaultBranch(alpha),
                name);
    }

    // --- AC-1 ----------------------------------------------------------------------------------------------------

    @Test
    void aSalonThatHasNeverSavedHoursGivesANewPersonAWeekOfNoWorkingDays() {
        Map<String, Object> read = readSchedule(nimali);

        assertThat(maps(read.get("weekly"))).hasSize(7).allSatisfy(day -> {
            assertThat(day.get("working")).isEqualTo(false);
            assertThat(day.get("starts")).isNull();
            assertThat(day.get("finishes")).isNull();
            assertThat(maps(day.get("breaks"))).isEmpty();
        });
        assertThat(read.get("version")).isEqualTo(1);
    }

    @Test
    void aNewPersonsWeekIsCopiedFromTheSalonsHoursAndNotLinkedToThemAfterwards() {
        salonHours("09:00", "18:00");

        Map<String, Object> person = added("Copied Person");

        assertThat(maps(person.get("weekly")))
                .extracting(
                        day -> day.get("day"),
                        day -> day.get("working"),
                        day -> day.get("starts"),
                        day -> day.get("finishes"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("monday", true, "09:00", "18:00"),
                        org.assertj.core.groups.Tuple.tuple("tuesday", true, "09:00", "18:00"),
                        org.assertj.core.groups.Tuple.tuple("wednesday", true, "09:00", "18:00"),
                        org.assertj.core.groups.Tuple.tuple("thursday", true, "09:00", "18:00"),
                        org.assertj.core.groups.Tuple.tuple("friday", true, "09:00", "18:00"),
                        org.assertj.core.groups.Tuple.tuple("saturday", true, "09:00", "18:00"),
                        org.assertj.core.groups.Tuple.tuple("sunday", false, null, null));
        assertThat(maps(maps(person.get("weekly")).getFirst().get("breaks"))).isEmpty();

        salonHours("10:00", "16:00");

        assertThat(monday(person.get("id"))).containsEntry("starts", "09:00").containsEntry("finishes", "18:00");
        assertThat(maps(readSchedule(nimali).get("weekly")))
                .as("someone added before the salon saved its hours")
                .noneMatch(day -> Boolean.TRUE.equals(day.get("working")));
    }

    @Test
    void aMemberAddedBeforeThisStageReadsAsHavingNoWorkingDaysAtVersionZeroAndCanBeSavedOnce() {
        UUID earlier = UUID.randomUUID();
        addedBeforeThisStage(earlier, "Earlier Person");

        Map<String, Object> read = readSchedule(earlier);

        assertThat(read.get("version")).isEqualTo(0);
        assertThat(maps(read.get("weekly"))).hasSize(7).noneMatch(day -> Boolean.TRUE.equals(day.get("working")));
        assertThat(maps(read(earlier).get("weekly")))
                .hasSize(7)
                .noneMatch(day -> Boolean.TRUE.equals(day.get("working")));

        List<Map<String, Object>> first = week(workday("monday", "09:00", "17:00"));
        Http.Exchange answer = save(earlier, 0, first);
        status(answer, 200);
        assertThat(api.body(answer).get("version")).isEqualTo(1);
        assertThat(monday(earlier)).containsEntry("starts", "09:00");

        // The row now exists, so version 0 is stale: a second first save cannot overwrite the first.
        refused(save(earlier, 0, week(workday("monday", "10:00", "17:00"))), 409, "conflict");
        assertThat(monday(earlier)).containsEntry("starts", "09:00");
    }

    @Test
    void aMemberAddedBeforeThisStageSavedAsNotWorkingChangesNothing() {
        UUID earlier = UUID.randomUUID();
        addedBeforeThisStage(earlier, "Earlier Person");

        refused(save(earlier, 0, week()), 409, "invalid_state");

        assertThat(version(earlier)).isZero();
    }

    // --- AC-2 ----------------------------------------------------------------------------------------------------

    @Test
    void aSaveStoresTheWholeWeekAndAnswersIt() {
        long before = version(nimali);

        Http.Exchange answer = save(
                nimali,
                before,
                week(
                        workday("monday", "09:00", "18:00", pause("13:00", "14:00")),
                        workday("sunday", "10:15", "12:45")));

        status(answer, 200);
        Map<String, Object> body = api.body(answer);
        assertThat(body.get("version")).isEqualTo((int) before + 1);
        assertThat(maps(body.get("affected"))).isEmpty();
        assertThat(maps(body.get("weekly")).getFirst())
                .containsEntry("day", "monday")
                .containsEntry("working", true)
                .containsEntry("starts", "09:00")
                .containsEntry("finishes", "18:00");
        List<Map<String, Object>> read = maps(readSchedule(nimali).get("weekly"));
        assertThat(maps(read.getFirst().get("breaks"))).containsExactly(Map.of("starts", "13:00", "finishes", "14:00"));
        assertThat(read.get(1)).containsEntry("working", false);
        assertThat(read.getLast()).containsEntry("starts", "10:15").containsEntry("finishes", "12:45");
        assertThat(maps(read(nimali).get("weekly")).getFirst()).containsEntry("finishes", "18:00");
    }

    @Test
    void timesAreOnQuarterHoursUpTo2345AndTheFinishIsAfterTheStart() {
        status(save(nimali, version(nimali), week(workday("monday", "00:00", "23:45"))), 200);

        long at = version(nimali);
        fieldErrors(save(nimali, at, week(workday("monday", "09:00", "09:00"))), "weekly[0].finishes");
        fieldErrors(save(nimali, at, week(workday("tuesday", "18:00", "09:00"))), "weekly[1].finishes");
        fieldErrors(save(nimali, at, week(workday("monday", "09:10", "18:00"))), "weekly[0].starts");
        fieldErrors(save(nimali, at, week(workday("monday", "09:00", "18:20"))), "weekly[0].finishes");
        fieldErrors(save(nimali, at, week(workday("monday", "09:00", "24:00"))), "weekly[0].finishes");
        fieldErrors(save(nimali, at, week(workday("monday", null, "18:00"))), "weekly[0].starts");
        assertThat(monday(nimali)).containsEntry("starts", "00:00").containsEntry("finishes", "23:45");
        assertThat(version(nimali)).isEqualTo(at);
    }

    @Test
    void theBodyNamesEachOfTheSevenWeekdaysOnce() {
        long at = version(nimali);
        fieldErrors(api.call(alpha, "PUT", weekly(nimali), weekSave(at, List.of()), salonOwner), "weekly");

        List<Map<String, Object>> twice = new ArrayList<>(week());
        twice.set(1, offDay("monday"));
        fieldErrors(save(nimali, at, twice), "weekly[1].day");

        List<Map<String, Object>> unknown = new ArrayList<>(week());
        unknown.set(0, offDay("funday"));
        fieldErrors(save(nimali, at, unknown), "weekly[0].day");

        Map<String, Object> noVersion = weekSave(at, week());
        noVersion.remove("version");
        fieldErrors(api.call(alpha, "PUT", weekly(nimali), noVersion, salonOwner), "version");

        Map<String, Object> extra = weekSave(at, week());
        extra.put("sneaky", 1);
        fieldErrors(api.call(alpha, "PUT", weekly(nimali), extra, salonOwner), "sneaky");
    }

    @Test
    void aDayNotWorkedMayNotBeSentTimes() {
        Map<String, Object> off = offDay("monday");
        off.put("starts", "09:00");
        List<Map<String, Object>> sent = new ArrayList<>(week());
        sent.set(0, off);

        fieldErrors(save(nimali, version(nimali), sent), "weekly[0].starts");
    }

    // --- AC-3 ----------------------------------------------------------------------------------------------------

    @Test
    void fiveBreaksAreKeptInTimeOrderAndASixthIsRefused() {
        long at = version(nimali);
        Http.Exchange five = save(
                nimali,
                at,
                week(workday(
                        "monday",
                        "08:00",
                        "20:00",
                        pause("17:00", "17:15"),
                        pause("09:00", "09:15"),
                        pause("13:00", "13:15"),
                        pause("11:00", "11:15"),
                        pause("15:00", "15:15"))));
        status(five, 200);
        assertThat(maps(monday(nimali).get("breaks")))
                .extracting(each -> each.get("starts"))
                .containsExactly("09:00", "11:00", "13:00", "15:00", "17:00");

        fieldErrors(
                save(
                        nimali,
                        at + 1,
                        week(workday(
                                "monday",
                                "08:00",
                                "20:00",
                                pause("09:00", "09:15"),
                                pause("11:00", "11:15"),
                                pause("13:00", "13:15"),
                                pause("15:00", "15:15"),
                                pause("17:00", "17:15"),
                                pause("19:00", "19:15")))),
                "weekly[0].breaks");
        assertThat(maps(monday(nimali).get("breaks"))).hasSize(5);
    }

    @Test
    void aBreakEndsAfterItStartsOnQuarterHoursInsideTheDay() {
        long at = version(nimali);
        fieldErrors(
                save(nimali, at, week(workday("monday", "09:00", "18:00", pause("13:00", "13:00")))),
                "weekly[0].breaks[0].finishes");
        fieldErrors(
                save(nimali, at, week(workday("monday", "09:00", "18:00", pause("14:00", "13:00")))),
                "weekly[0].breaks[0].finishes");
        fieldErrors(
                save(nimali, at, week(workday("monday", "09:00", "18:00", pause("13:05", "14:00")))),
                "weekly[0].breaks[0].starts");
        fieldErrors(
                save(nimali, at, week(workday("monday", "09:00", "18:00", pause("08:45", "10:00")))),
                "weekly[0].breaks[0]");
        fieldErrors(
                save(nimali, at, week(workday("monday", "09:00", "18:00", pause("17:00", "18:15")))),
                "weekly[0].breaks[0]");
        assertThat(version(nimali)).isEqualTo(at);
    }

    @Test
    void breaksMayTouchTheStartTheFinishAndEachOtherButNotOverlap() {
        long at = version(nimali);
        status(
                save(
                        nimali,
                        at,
                        week(workday(
                                "monday",
                                "09:00",
                                "18:00",
                                pause("09:00", "09:30"),
                                pause("12:00", "13:00"),
                                pause("13:00", "14:00"),
                                pause("17:30", "18:00")))),
                200);

        fieldErrors(
                save(
                        nimali,
                        at + 1,
                        week(workday("monday", "09:00", "18:00", pause("12:00", "13:00"), pause("12:45", "14:00")))),
                "weekly[0].breaks[1].starts");
        fieldErrors(
                save(
                        nimali,
                        at + 1,
                        week(workday("monday", "09:00", "18:00", pause("12:00", "13:00"), pause("12:00", "13:00")))),
                "weekly[0].breaks[1].starts");
    }

    @Test
    void breaksThatFillTheWholeDayAreRefusedForOneBreakAndForSeveralTouching() {
        long at = version(nimali);
        fieldErrors(
                save(nimali, at, week(workday("monday", "09:00", "18:00", pause("09:00", "18:00")))),
                "weekly[0].breaks");
        fieldErrors(
                save(
                        nimali,
                        at,
                        week(workday(
                                "tuesday",
                                "09:00",
                                "18:00",
                                pause("09:00", "13:00"),
                                pause("13:00", "15:00"),
                                pause("15:00", "18:00")))),
                "weekly[1].breaks");
        assertThat(version(nimali)).isEqualTo(at);

        // A quarter of an hour left is time left.
        status(save(nimali, at, week(workday("monday", "09:00", "18:00", pause("09:15", "18:00")))), 200);
        status(save(nimali, at + 1, week(workday("monday", "09:00", "18:00", pause("09:00", "17:45")))), 200);
    }

    @Test
    void aWeekdaySetToNotWorkingKeepsNoBreaksWhenSaved() {
        saved(nimali, week(workday("monday", "09:00", "18:00", pause("13:00", "14:00"))));

        Map<String, Object> off = offDay("monday");
        off.put("breaks", List.of(pause("13:00", "14:00")));
        List<Map<String, Object>> sent = new ArrayList<>(week());
        sent.set(0, off);
        status(save(nimali, version(nimali), sent), 200);

        assertThat(monday(nimali)).containsEntry("working", false);
        assertThat(maps(monday(nimali).get("breaks"))).isEmpty();
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM staff_member_weekday_break WHERE tenant_id = ? AND member_id = ?::uuid",
                        Long.class,
                        alpha.id(),
                        nimali.toString()))
                .isZero();
    }

    // --- AC-5 ----------------------------------------------------------------------------------------------------

    @Test
    void hoursBeyondTheSalonsAreStoredAsEntered() {
        salonHours("09:00", "18:00");

        saved(nimali, week(workday("monday", "07:00", "21:00"), workday("sunday", "10:00", "12:00")));

        List<Map<String, Object>> read = maps(readSchedule(nimali).get("weekly"));
        assertThat(read.getFirst()).containsEntry("starts", "07:00").containsEntry("finishes", "21:00");
        assertThat(read.getLast()).containsEntry("working", true);
    }

    // --- AC-6 ----------------------------------------------------------------------------------------------------

    @Test
    void aStaleVersionIsAConflictAndChangesNothing() {
        long at = version(nimali);
        status(save(nimali, at, week(workday("monday", "09:00", "18:00"))), 200);

        refused(save(nimali, at, week(workday("monday", "10:00", "18:00"))), 409, "conflict");

        assertThat(monday(nimali)).containsEntry("starts", "09:00");
        assertThat(version(nimali)).isEqualTo(at + 1);
    }

    @Test
    void aSaveThatChangesNothingIsInvalidStateAndAStaleOneIsAConflictFirst() {
        List<Map<String, Object>> same = week(workday("monday", "09:00", "18:00", pause("13:00", "14:00")));
        long at = saved(nimali, same);

        refused(save(nimali, at, same), 409, "invalid_state");
        refused(save(nimali, at - 1, same), 409, "conflict");

        assertThat(version(nimali)).isEqualTo(at);
    }

    @Test
    void breaksSentInAnotherOrderAreTheSameWeek() {
        long at = saved(
                nimali, week(workday("monday", "09:00", "18:00", pause("10:00", "10:15"), pause("13:00", "14:00"))));

        refused(
                save(
                        nimali,
                        at,
                        week(workday("monday", "09:00", "18:00", pause("13:00", "14:00"), pause("10:00", "10:15")))),
                409,
                "invalid_state");
    }

    @Test
    void savingHoursAndEditingAProfileNeverConflict() {
        Map<String, Object> profile = read(nimali);
        long at = saved(nimali, week(workday("monday", "09:00", "18:00")));

        // The profile's version did not move with the save, so the profile as read still saves.
        Map<String, Object> edit = TeamFixture.saveOf(profile);
        edit.put("title", "Colourist");
        status(api.call(alpha, "PUT", TeamFixture.MEMBERS + "/" + nimali, edit, salonOwner), 200);

        // The schedule's version did not move with the edit, so the week as read still saves.
        assertThat(version(nimali)).isEqualTo(at);
        status(save(nimali, at, week(workday("monday", "10:00", "18:00"))), 200);

        assertThat(read(nimali).get("title")).isEqualTo("Colourist");
        assertThat(monday(nimali)).containsEntry("starts", "10:00");
    }

    // --- AC-7 ----------------------------------------------------------------------------------------------------

    @Test
    void aChangeIsAddedForTodayOrUpToAYearAheadAndNotOutsideThat() {
        Map<String, Object> first = addedChange(nimali, notWorking(today, null));
        assertThat(first).containsEntry("date", today.toString()).containsEntry("working", false);
        assertThat(first.get("starts")).isNull();
        assertThat(first.get("finishes")).isNull();
        assertThat(maps(first.get("breaks"))).isEmpty();
        assertThat(first).containsEntry("note", null);

        addedChange(nimali, differentHours(today.plusYears(1), "09:00", "13:00", "Half day"));

        fieldErrors(addChange(nimali, notWorking(today.minusDays(1), null)), "date");
        fieldErrors(addChange(nimali, notWorking(today.plusYears(1).plusDays(1), null)), "date");
        fieldErrors(addChange(nimali, notWorking("not-a-date", null)), "date");
        assertThat(maps(readSchedule(nimali).get("dateChanges"))).hasSize(2);
    }

    @Test
    void differentHoursFollowTheRulesOfAWorkingDayWithoutANameOfWeekday() {
        LocalDate date = today.plusDays(3);
        fieldErrors(addChange(nimali, differentHours(date, "13:00", "09:00", null)), "finishes");
        fieldErrors(addChange(nimali, differentHours(date, "09:10", "13:00", null)), "starts");
        fieldErrors(
                addChange(nimali, differentHours(date, "09:00", "13:00", null, pause("08:00", "09:30"))), "breaks[0]");
        fieldErrors(
                addChange(nimali, differentHours(date, "09:00", "13:00", null, pause("10:00", "10:00"))),
                "breaks[0].finishes");
        fieldErrors(
                addChange(
                        nimali,
                        differentHours(date, "09:00", "13:00", null, pause("10:00", "11:00"), pause("10:30", "11:30"))),
                "breaks[1].starts");
        fieldErrors(addChange(nimali, differentHours(date, "09:00", "13:00", null, pause("09:00", "13:00"))), "breaks");
        fieldErrors(
                addChange(
                        nimali,
                        differentHours(date, "09:00", "13:00", null, pause("09:00", "11:00"), pause("11:00", "13:00"))),
                "breaks");
        fieldErrors(
                addChange(
                        nimali,
                        differentHours(
                                date,
                                "08:00",
                                "20:00",
                                null,
                                pause("09:00", "09:15"),
                                pause("10:00", "10:15"),
                                pause("11:00", "11:15"),
                                pause("12:00", "12:15"),
                                pause("13:00", "13:15"),
                                pause("14:00", "14:15"))),
                "breaks");
        assertThat(maps(readSchedule(nimali).get("dateChanges"))).isEmpty();
    }

    @Test
    void aNoteIsUpTo60CharactersTrimmedAndEmptyIsNone() {
        Map<String, Object> full = addedChange(nimali, notWorking(today.plusDays(1), "  " + "n".repeat(60) + "  "));
        assertThat(full.get("note")).isEqualTo("n".repeat(60));

        Map<String, Object> blank = addedChange(nimali, notWorking(today.plusDays(2), "   "));
        assertThat(blank).containsEntry("note", null);

        fieldErrors(addChange(nimali, notWorking(today.plusDays(3), "n".repeat(61))), "note");
    }

    @Test
    void theWorkingFieldIsRequired() {
        Map<String, Object> change = notWorking(today.plusDays(1), null);
        change.remove("working");
        fieldErrors(addChange(nimali, change), "working");
    }

    @Test
    void aSecondChangeOnADateIsAConflict() {
        addedChange(nimali, notWorking(today.plusDays(1), null));

        refused(addChange(nimali, differentHours(today.plusDays(1), "09:00", "12:00", null)), 409, "conflict");

        assertThat(maps(readSchedule(nimali).get("dateChanges"))).hasSize(1);
        // Another person may have one on the same date.
        addedChange(added("Kasun").get("id"), notWorking(today.plusDays(1), null));
    }

    @Test
    void aMemberHasAtMost100UpcomingChangesAndPassedOnesDoNotCount() {
        UUID member = UUID.fromString(nimali.toString());
        for (int i = 0; i < 99; i++) {
            owner.update(
                    "INSERT INTO staff_member_date_change (tenant_id, id, branch_id, member_id, change_date, created_by,"
                            + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, now(), now())",
                    alpha.id(),
                    UUID.randomUUID(),
                    salons.defaultBranch(alpha),
                    member,
                    java.sql.Date.valueOf(today.plusDays(i)),
                    UUID.randomUUID());
        }
        owner.update(
                "INSERT INTO staff_member_date_change (tenant_id, id, branch_id, member_id, change_date, created_by,"
                        + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, now(), now())",
                alpha.id(),
                UUID.randomUUID(),
                salons.defaultBranch(alpha),
                member,
                java.sql.Date.valueOf(today.minusDays(1)),
                UUID.randomUUID());

        addedChange(nimali, notWorking(today.plusDays(100), null));
        refused(addChange(nimali, notWorking(today.plusDays(101), null)), 409, "limit_reached");

        assertThat(maps(readSchedule(nimali).get("dateChanges"))).hasSize(100);
    }

    @Test
    void removingAChangeLeavesTheWeeklyHoursAndRemovingAgainIsNotFound() {
        Map<String, Object> change =
                addedChange(nimali, differentHours(today.plusDays(1), "09:00", "13:00", null, pause("10:00", "10:15")));
        String path = dateChanges(nimali) + "/" + change.get("id");

        status(api.call(alpha, "DELETE", path, null, salonOwner), 204);

        assertThat(maps(readSchedule(nimali).get("dateChanges"))).isEmpty();
        refused(api.call(alpha, "DELETE", path, null, salonOwner), 404, "not_found");
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM staff_member_date_change_break WHERE tenant_id = ?",
                        Long.class,
                        alpha.id()))
                .isZero();
    }

    @Test
    void todaysChangeCanBeRemovedAndOneThatHasPassedAnswersNotFoundAndStays() {
        Map<String, Object> todays = addedChange(nimali, notWorking(today, null));
        status(api.call(alpha, "DELETE", dateChanges(nimali) + "/" + todays.get("id"), null, salonOwner), 204);

        UUID passed = UUID.randomUUID();
        owner.update(
                "INSERT INTO staff_member_date_change (tenant_id, id, branch_id, member_id, change_date, created_by,"
                        + " created_at, updated_at) VALUES (?, ?, ?, ?::uuid, ?, ?, now(), now())",
                alpha.id(),
                passed,
                salons.defaultBranch(alpha),
                nimali.toString(),
                java.sql.Date.valueOf(today.minusDays(1)),
                UUID.randomUUID());

        refused(api.call(alpha, "DELETE", dateChanges(nimali) + "/" + passed, null, salonOwner), 404, "not_found");

        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM staff_member_date_change WHERE tenant_id = ? AND id = ?",
                        Long.class,
                        alpha.id(),
                        passed))
                .isEqualTo(1);
    }

    @Test
    void anotherPersonsChangeCannotBeRemovedThroughThisPerson() {
        Map<String, Object> change = addedChange(nimali, notWorking(today.plusDays(1), null));
        Object other = added("Kasun").get("id");

        refused(
                api.call(alpha, "DELETE", dateChanges(other) + "/" + change.get("id"), null, salonOwner),
                404,
                "not_found");

        assertThat(maps(readSchedule(nimali).get("dateChanges"))).hasSize(1);
    }

    @Test
    void theListRunsFromTodayOnInDateOrderAndIsOtherwiseEmpty() {
        assertThat(maps(readSchedule(nimali).get("dateChanges"))).isEmpty();
        owner.update(
                "INSERT INTO staff_member_date_change (tenant_id, id, branch_id, member_id, change_date, created_by,"
                        + " created_at, updated_at) VALUES (?, ?, ?, ?::uuid, ?, ?, now(), now())",
                alpha.id(),
                UUID.randomUUID(),
                salons.defaultBranch(alpha),
                nimali.toString(),
                java.sql.Date.valueOf(today.minusDays(1)),
                UUID.randomUUID());

        addedChange(nimali, notWorking(today.plusDays(9), null));
        addedChange(nimali, differentHours(today, "09:00", "13:00", null, pause("10:00", "10:15")));
        addedChange(nimali, notWorking(today.plusDays(2), null));

        Map<String, Object> read = readSchedule(nimali);
        assertThat(read.get("today")).isEqualTo(today.toString());
        List<Map<String, Object>> listed = maps(read.get("dateChanges"));
        assertThat(listed)
                .extracting(change -> change.get("date"))
                .containsExactly(
                        today.toString(),
                        today.plusDays(2).toString(),
                        today.plusDays(9).toString());
        assertThat(maps(listed.getFirst().get("breaks")))
                .containsExactly(Map.of("starts", "10:00", "finishes", "10:15"));
    }

    @Test
    void aWeeklySaveLeavesDateChangesAlone() {
        addedChange(nimali, differentHours(today.plusDays(1), "09:00", "13:00", "Half day", pause("10:00", "10:15")));

        saved(nimali, week(workday("monday", "10:00", "18:00", pause("13:00", "14:00"))));
        saved(nimali, week());

        List<Map<String, Object>> listed = maps(readSchedule(nimali).get("dateChanges"));
        assertThat(listed).hasSize(1);
        assertThat(listed.getFirst())
                .containsEntry("starts", "09:00")
                .containsEntry("finishes", "13:00")
                .containsEntry("note", "Half day");
        assertThat(maps(listed.getFirst().get("breaks"))).hasSize(1);
    }

    @Test
    void aDateChangeDoesNotMoveTheWeeksVersion() {
        long at = version(nimali);

        Map<String, Object> change = addedChange(nimali, notWorking(today.plusDays(1), null));
        assertThat(version(nimali)).isEqualTo(at);
        status(api.call(alpha, "DELETE", dateChanges(nimali) + "/" + change.get("id"), null, salonOwner), 204);

        assertThat(version(nimali)).isEqualTo(at);
    }

    @Test
    void aRemovedPersonsScheduleIsNotFoundForEveryRequest() {
        Map<String, Object> change = addedChange(nimali, notWorking(today.plusDays(1), null));
        long at = version(nimali);
        status(
                api.call(
                        alpha,
                        "DELETE",
                        TeamFixture.MEMBERS + "/" + nimali + "?version="
                                + read(nimali).get("version"),
                        null,
                        salonOwner),
                204);

        refused(api.call(alpha, "GET", schedule(nimali), null, salonOwner), 404, "not_found");
        refused(save(nimali, at, week()), 404, "not_found");
        refused(addChange(nimali, notWorking(today.plusDays(2), null)), 404, "not_found");
        refused(
                api.call(alpha, "DELETE", dateChanges(nimali) + "/" + change.get("id"), null, salonOwner),
                404,
                "not_found");
    }

    @Test
    void anIdThatIsNotAnIdIsNotFound() {
        refused(api.call(alpha, "GET", schedule("not-an-id"), null, salonOwner), 404, "not_found");
        refused(api.call(alpha, "DELETE", dateChanges(nimali) + "/not-an-id", null, salonOwner), 404, "not_found");
    }

    // --- AC-13 ---------------------------------------------------------------------------------------------------

    private void refusedEveryChange(String token, String code, Map<String, Object> change) {
        refused(api.call(alpha, "PUT", weekly(nimali), weekSave(version(nimali), week()), token), 403, code);
        refused(api.call(alpha, "POST", dateChanges(nimali), notWorking(today.plusDays(5), null), token), 403, code);
        refused(api.call(alpha, "DELETE", dateChanges(nimali) + "/" + change.get("id"), null, token), 403, code);
    }

    @Test
    void aReceptionistAndAProfessionalReadTheScheduleWithoutNotesAndChangeNothing() {
        Map<String, Object> change = addedChange(nimali, notWorking(today.plusDays(1), "Private note"));

        for (StaffRole role : List.of(StaffRole.RECEPTIONIST, StaffRole.PROFESSIONAL)) {
            String token = settings.signedIn(alpha, role).token();
            Http.Exchange read = api.call(alpha, "GET", schedule(nimali), null, token);
            status(read, 200);
            assertThat(maps(api.body(read).get("dateChanges")).getFirst()).doesNotContainKey("note");
            assertThat(read.body()).doesNotContain("Private note");
            refusedEveryChange(token, "permission_denied", change);
        }
    }

    @Test
    void aManagerChangesTheScheduleAndReadsTheNote() {
        String manager = settings.signedIn(alpha, StaffRole.MANAGER).token();

        Http.Exchange added =
                api.call(alpha, "POST", dateChanges(nimali), notWorking(today.plusDays(1), "A note"), manager);

        status(added, 201);
        assertThat(map(api.body(added).get("change"))).containsEntry("note", "A note");
        Http.Exchange read = api.call(alpha, "GET", schedule(nimali), null, manager);
        assertThat(maps(api.body(read).get("dateChanges")).getFirst()).containsEntry("note", "A note");
        status(
                api.call(
                        alpha,
                        "PUT",
                        weekly(nimali),
                        weekSave(version(nimali), week(workday("monday", "09:00", "10:00"))),
                        manager),
                200);
    }

    @Test
    void anAccountantGetsPermissionDeniedFromEveryScheduleEndpoint() {
        Map<String, Object> change = addedChange(nimali, notWorking(today.plusDays(1), null));
        String accountant = settings.signedIn(alpha, StaffRole.ACCOUNTANT).token();

        refused(api.call(alpha, "GET", schedule(nimali), null, accountant), 403, "permission_denied");
        refusedEveryChange(accountant, "permission_denied", change);
    }

    @Test
    void aSupportSessionReadsWithoutNotesAndIsRefusedEveryChange() {
        Map<String, Object> change = addedChange(nimali, notWorking(today.plusDays(1), "Private note"));
        String support = settings.supportSession(alpha);

        Http.Exchange read = api.call(alpha, "GET", schedule(nimali), null, support);

        status(read, 200);
        assertThat(maps(api.body(read).get("dateChanges")).getFirst()).doesNotContainKey("note");
        assertThat(read.body()).doesNotContain("Private note");
        refusedEveryChange(support, "support_read_only", change);
    }

    @Test
    void noSessionIsRefusedAndASuspendedOrReadOnlySalonAnswersAsTheCatalogueDoes() {
        Map<String, Object> change = addedChange(nimali, notWorking(today.plusDays(1), null));
        long at = version(nimali);
        refused(api.call(alpha, "GET", schedule(nimali), null, null), 401, "invalid_session");

        owner.update("UPDATE tenant SET access_state = 'read_only' WHERE id = ?", alpha.id());
        status(api.call(alpha, "GET", schedule(nimali), null, salonOwner), 200);
        refusedEveryChange(salonOwner, "salon_read_only", change);
        owner.update("UPDATE tenant SET access_state = 'full' WHERE id = ?", alpha.id());

        salons.suspend(alpha);
        refused(api.call(alpha, "GET", schedule(nimali), null, salonOwner), 403, "salon_suspended");
        refused(save(nimali, at, week()), 403, "salon_suspended");
        salons.activate(alpha);
        assertThat(version(nimali)).isEqualTo(at);
    }

    @Test
    void nothingOfAScheduleAppearsInThePublicTeamList() {
        UUID colour = category(alpha, salonOwner, "Colour");
        service(alpha, salonOwner, "Balayage", colour, true);
        Map<String, Object> person = TeamFixture.person("Shown Person");
        person.put("services", TeamFixture.services(List.of(colour), List.of()));
        Http.Exchange created = api.call(alpha, "POST", TeamFixture.MEMBERS, person, salonOwner);
        status(created, 201);
        Object shown = api.body(created).get("id");
        saved(shown, week(workday("monday", "09:15", "18:45")));
        addedChange(shown, notWorking(today.plusDays(1), "Private note"));

        Http.Exchange listed = api.call(alpha, "GET", TeamFixture.PUBLIC, null, null);

        status(listed, 200);
        assertThat(maps(api.body(listed).get("items")).getFirst())
                .containsOnlyKeys("id", "name", "title", "highlights", "bio", "photoUrl", "serviceIds");
        assertThat(listed.body()).doesNotContain("09:15", "Private note", "weekly");
    }

    // --- AC-14 ---------------------------------------------------------------------------------------------------

    @Test
    void anotherSalonsScheduleIsNotFoundForEveryRequestAndStaysAsItWas() {
        Map<String, Object> change = addedChange(nimali, notWorking(today.plusDays(1), "Alpha note"));
        long at = version(nimali);
        String betaOwner = settings.signedIn(beta, StaffRole.OWNER).token();

        refused(api.call(beta, "GET", schedule(nimali), null, betaOwner), 404, "not_found");
        refused(api.call(beta, "PUT", weekly(nimali), weekSave(at, week()), betaOwner), 404, "not_found");
        refused(
                api.call(beta, "POST", dateChanges(nimali), notWorking(today.plusDays(2), null), betaOwner),
                404,
                "not_found");
        refused(
                api.call(beta, "DELETE", dateChanges(nimali) + "/" + change.get("id"), null, betaOwner),
                404,
                "not_found");
        assertThat(version(nimali)).isEqualTo(at);
        assertThat(maps(readSchedule(nimali).get("dateChanges"))).hasSize(1);
    }

    @Test
    void aChangeOfAnotherSalonCannotBeRemovedThroughOnesOwnMember() {
        Map<String, Object> change = addedChange(nimali, notWorking(today.plusDays(1), null));
        String betaOwner = settings.signedIn(beta, StaffRole.OWNER).token();
        Http.Exchange created = api.call(beta, "POST", TeamFixture.MEMBERS, TeamFixture.person("Beta"), betaOwner);
        status(created, 201);
        Object betas = api.body(created).get("id");

        refused(
                api.call(beta, "DELETE", dateChanges(betas) + "/" + change.get("id"), null, betaOwner),
                404,
                "not_found");

        assertThat(maps(readSchedule(nimali).get("dateChanges"))).hasSize(1);
    }

    @Test
    void anotherSalonsSessionOnThisSalonsHostnameGetsNothing() {
        Map<String, Object> change = addedChange(nimali, notWorking(today.plusDays(1), "Alpha note"));
        long at = version(nimali);
        String betaOwner = settings.signedIn(beta, StaffRole.OWNER).token();

        // Beta's session on alpha's hostname is a session of another salon, answered as the catalogue answers it.
        refused(api.call(alpha, "GET", schedule(nimali), null, betaOwner), 404, "not_found");
        refused(api.call(alpha, "PUT", weekly(nimali), weekSave(at, week()), betaOwner), 404, "not_found");
        refused(
                api.call(alpha, "POST", dateChanges(nimali), notWorking(today.plusDays(2), null), betaOwner),
                404,
                "not_found");
        refused(
                api.call(alpha, "DELETE", dateChanges(nimali) + "/" + change.get("id"), null, betaOwner),
                404,
                "not_found");
        assertThat(version(nimali)).isEqualTo(at);
    }

    // --- AC-15 ---------------------------------------------------------------------------------------------------

    private Map<String, Object> audited(String action) {
        return owner.queryForMap(
                "SELECT actor_kind, actor_id, created_at, before_values::text AS before_values,"
                        + " after_values::text AS after_values FROM audit_entry"
                        + " WHERE tenant_id = ? AND subject_id = ? AND action = ? ORDER BY created_at DESC LIMIT 1",
                alpha.id(),
                nimali.toString(),
                action);
    }

    @Test
    void aWeeklySaveIsAuditedNamingTheWeekdaysItChangedWithTheValuesBeforeAndAfter() {
        saved(nimali, week(workday("monday", "09:00", "18:00"), workday("friday", "09:00", "18:00")));

        saved(
                nimali,
                week(
                        workday("monday", "10:00", "18:00", pause("13:00", "14:00")),
                        workday("friday", "09:00", "18:00")));

        Map<String, Object> entry = audited("staff.team_member_hours_saved");
        assertThat(entry.get("actor_kind")).isEqualTo("staff");
        assertThat(entry.get("actor_id")).isNotNull();
        assertThat(entry.get("created_at")).isNotNull();
        String before = (String) entry.get("before_values");
        String after = (String) entry.get("after_values");
        assertThat(before).contains("\"changedDays\": [\"monday\"]", "09:00").doesNotContain("friday");
        assertThat(after)
                .contains("\"changedDays\": [\"monday\"]", "10:00", "13:00-14:00")
                .doesNotContain("friday");
    }

    /** A support session can read the audit list and may not read notes, so a note is never in it (decision 102(g)). */
    @Test
    void aDateChangeAddedAndRemovedIsAuditedWithItsValuesAndNeverItsNote() {
        Map<String, Object> change = addedChange(
                nimali, differentHours(today.plusDays(1), "09:00", "13:00", "Private note", pause("10:00", "10:15")));
        status(api.call(alpha, "DELETE", dateChanges(nimali) + "/" + change.get("id"), null, salonOwner), 204);

        Map<String, Object> added = audited("staff.team_member_date_change_added");
        assertThat(added.get("before_values")).isNull();
        assertThat((String) added.get("after_values"))
                .contains(today.plusDays(1).toString(), "09:00", "13:00", "10:00-10:15", "\"hasNote\": true")
                .doesNotContain("Private note");
        Map<String, Object> removed = audited("staff.team_member_date_change_removed");
        assertThat(removed.get("after_values")).isNull();
        assertThat((String) removed.get("before_values"))
                .contains(today.plusDays(1).toString(), "\"hasNote\": true")
                .doesNotContain("Private note");

        // And through the route a support session actually reads.
        String support = settings.supportSession(alpha);
        Http.Exchange list = settings.call(alpha, "GET", "/api/v1/identity/audit?size=100", null, support);
        status(list, 200);
        assertThat(list.body()).doesNotContain("Private note");
    }
}
