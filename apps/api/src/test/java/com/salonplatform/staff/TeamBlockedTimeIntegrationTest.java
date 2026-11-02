package com.salonplatform.staff;

import static com.salonplatform.staff.TeamFixture.blockOf;
import static com.salonplatform.staff.TeamFixture.blockedTime;
import static com.salonplatform.staff.TeamFixture.leaveOf;
import static com.salonplatform.staff.TeamFixture.schedule;
import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.testing.Http;
import com.salonplatform.testing.TestSalons;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Blocked time from outside (F06-S03 AC-4, AC-5, AC-6), every answer held to the committed contract. */
class TeamBlockedTimeIntegrationTest extends TwoSalonTeam {

    private LocalDate today;
    private Object nimali;

    @BeforeEach
    void aPerson() {
        today = LocalDate.ofInstant(clock.instant(), TestSalons.DEFAULT_TIME_ZONE);
        nimali = added("Nimali").get("id");
    }

    private Http.Exchange add(Map<String, Object> body) {
        return api.call(alpha, "POST", blockedTime(nimali), body, salonOwner);
    }

    private Map<String, Object> added(Map<String, Object> body) {
        Http.Exchange answer = add(body);
        status(answer, 201);
        return map(api.body(answer).get("block"));
    }

    private List<Map<String, Object>> listed() {
        Http.Exchange read = api.call(alpha, "GET", schedule(nimali), null, salonOwner);
        status(read, 200);
        return maps(api.body(read).get("blockedTime"));
    }

    private long rows() {
        return owner.queryForObject(
                "SELECT count(*) FROM staff_member_blocked_time WHERE tenant_id = ? AND member_id = ?::uuid",
                Long.class,
                alpha.id(),
                nimali.toString());
    }

    /** A block made directly: one that has passed, which a request may not make, or many at once. */
    private UUID insertedBlock(LocalDate date, String starts, String finishes) {
        UUID id = UUID.randomUUID();
        owner.update(
                """
                INSERT INTO staff_member_blocked_time (tenant_id, id, branch_id, member_id, block_date, starts, finishes,
                    label, created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?::uuid, ?, ?::time, ?::time, 'Training', ?, now(), now())
                """,
                alpha.id(),
                id,
                salons.defaultBranch(alpha),
                nimali.toString(),
                date,
                starts,
                finishes,
                UUID.randomUUID());
        return id;
    }

    // --- AC-4: adding ----------------------------------------------------------------------------------------------

    @Test
    void anAddedBlockAnswersEveryFieldWithItsLabelTrimmed() {
        Http.Exchange answer = add(blockOf(today.plusDays(1), "10:00", "11:30", "  Colour training  "));

        status(answer, 201);
        assertThat(map(api.body(answer).get("block")))
                .containsEntry("date", today.plusDays(1).toString())
                .containsEntry("starts", "10:00")
                .containsEntry("finishes", "11:30")
                .containsEntry("label", "Colour training")
                .containsEntry("bookingsToResolve", 0)
                .containsKey("id");
        assertThat(maps(api.body(answer).get("affected"))).isEmpty();
    }

    @Test
    void todayAndAYearAheadAreTakenAndYesterdayAndADayBeyondAreFieldErrors() {
        status(add(blockOf(today, "10:00", "11:00", "Today")), 201);
        status(add(blockOf(today.plusYears(1), "10:00", "11:00", "A year on")), 201);

        fieldErrors(add(blockOf(today.minusDays(1), "10:00", "11:00", "Past")), "date");
        fieldErrors(add(blockOf(today.plusYears(1).plusDays(1), "10:00", "11:00", "Beyond")), "date");
        assertThat(rows()).isEqualTo(2);
    }

    @Test
    void aFinishNotAfterTheStartIsAFieldErrorAndNothingIsSaved() {
        fieldErrors(add(blockOf(today, "11:00", "11:00", "Same")), "finishes");
        fieldErrors(add(blockOf(today, "11:00", "10:00", "Before")), "finishes");
        assertThat(rows()).isZero();
    }

    @Test
    void timesOffAQuarterHourAreFieldErrors() {
        fieldErrors(add(blockOf(today, "10:10", "11:00", "Off")), "starts");
        fieldErrors(add(blockOf(today, "10:00", "11:05", "Off")), "finishes");
        fieldErrors(add(blockOf(today, null, null, "None")), "starts", "finishes");
        fieldErrors(add(blockOf(today, "25:00", "26:00", "Hours")), "starts", "finishes");
    }

    @Test
    void aLabelOf2And40IsTakenAnd1And41AndBlankAreFieldErrors() {
        assertThat(added(blockOf(today, "08:00", "08:15", "AB"))).containsEntry("label", "AB");
        assertThat(added(blockOf(today, "08:15", "08:30", "x".repeat(40)))).containsEntry("label", "x".repeat(40));

        fieldErrors(add(blockOf(today, "09:00", "09:15", "A")), "label");
        fieldErrors(add(blockOf(today, "09:00", "09:15", " A ")), "label");
        fieldErrors(add(blockOf(today, "09:00", "09:15", "x".repeat(41))), "label");
        fieldErrors(add(blockOf(today, "09:00", "09:15", "   ")), "label");
        fieldErrors(add(blockOf(today, "09:00", "09:15", null)), "label");
        fieldErrors(add(blockOf(today, "09:00", "09:15", "one\ntwo")), "label");
        assertThat(rows()).isEqualTo(2);
    }

    @Test
    void everyBrokenRuleIsNamedTogetherAndAnUnknownFieldIsRefused() {
        fieldErrors(add(blockOf("2026-02-30", "bad", "worse", "")), "date", "starts", "finishes", "label");

        Map<String, Object> body = blockOf(today, "10:00", "11:00", "Training");
        body.put("repeat", "weekly");
        fieldErrors(add(body), "repeat");
    }

    @Test
    void addingForSomeoneWhoIsNotOnTheTeamIsNotFound() {
        refused(
                api.call(
                        alpha,
                        "POST",
                        blockedTime(UUID.randomUUID()),
                        blockOf(today, "10:00", "11:00", "Training"),
                        salonOwner),
                404,
                "not_found");
    }

    // --- AC-5: overlap, the limit, and stored as entered ----------------------------------------------------------

    @Test
    void anOverlappingBlockIsAConflictInEveryShapeAndNothingIsSaved() {
        added(blockOf(today.plusDays(1), "10:00", "12:00", "Training"));

        for (List<String> times : List.of(
                List.of("10:00", "12:00"),
                List.of("09:00", "10:15"),
                List.of("11:45", "13:00"),
                List.of("10:30", "11:00"),
                List.of("09:00", "13:00"))) {
            refused(add(blockOf(today.plusDays(1), times.get(0), times.get(1), "Other")), 409, "conflict");
        }
        assertThat(rows()).isEqualTo(1);
    }

    @Test
    void blocksThatTouchAreTakenOnBothSides() {
        added(blockOf(today.plusDays(1), "10:00", "12:00", "Training"));

        added(blockOf(today.plusDays(1), "09:00", "10:00", "Before"));
        added(blockOf(today.plusDays(1), "12:00", "13:00", "After"));

        assertThat(rows()).isEqualTo(3);
    }

    @Test
    void anotherDateAndAnotherPersonAreNoConflict() {
        added(blockOf(today.plusDays(1), "10:00", "12:00", "Training"));

        added(blockOf(today.plusDays(2), "10:00", "12:00", "Another date"));
        Object kasun = added("Kasun").get("id");
        status(
                api.call(
                        alpha,
                        "POST",
                        blockedTime(kasun),
                        blockOf(today.plusDays(1), "10:00", "12:00", "Him"),
                        salonOwner),
                201);
    }

    @Test
    void theTwoHundredthBlockIsTakenAndTheNextIsLimitReached() {
        for (int i = 1; i <= 199; i++) {
            insertedBlock(today.plusDays(i), "10:00", "11:00");
        }

        status(add(blockOf(today.plusDays(200), "10:00", "11:00", "The last")), 201);
        assertThat(rows()).isEqualTo(200);

        refused(add(blockOf(today.plusDays(201), "10:00", "11:00", "One more")), 409, "limit_reached");
        assertThat(rows()).isEqualTo(200);
    }

    @Test
    void aBlockThatHasPassedDoesNotCountTowardTheLimit() {
        for (int i = 1; i <= 199; i++) {
            insertedBlock(today.plusDays(i), "10:00", "11:00");
        }
        insertedBlock(today.minusDays(1), "10:00", "11:00");
        insertedBlock(today.minusDays(2), "10:00", "11:00");

        status(add(blockOf(today.plusDays(200), "10:00", "11:00", "The last")), 201);
    }

    @Test
    void aBlockOutsideTheirHoursOrOnTheirLeaveIsStoredAsEntered() {
        status(
                api.call(
                        alpha,
                        "POST",
                        TeamFixture.leave(nimali),
                        leaveOf(today.plusDays(1), today.plusDays(1), "annual", null),
                        salonOwner),
                201);

        assertThat(added(blockOf(today.plusDays(1), "02:00", "03:00", "On leave, at night")))
                .containsEntry("starts", "02:00")
                .containsEntry("finishes", "03:00");
    }

    // --- AC-6: the list and Remove ---------------------------------------------------------------------------------

    @Test
    void noBlockListsEmpty() {
        assertThat(listed()).isEmpty();
    }

    @Test
    void theListIsInDateAndTimeOrderFromTodayOnEvenWhenItsTimeHasPassedToday() {
        Object later =
                added(blockOf(today.plusDays(2), "09:00", "10:00", "Later")).get("id");
        Object afternoon =
                added(blockOf(today.plusDays(1), "15:00", "16:00", "Afternoon")).get("id");
        Object morning =
                added(blockOf(today.plusDays(1), "09:00", "10:00", "Morning")).get("id");
        UUID earlyToday = insertedBlock(today, "00:00", "00:15");
        insertedBlock(today.minusDays(1), "09:00", "10:00");

        assertThat(listed())
                .extracting(each -> each.get("id"))
                .containsExactly(earlyToday.toString(), morning, afternoon, later);
    }

    @Test
    void removingTakesItAwayAndRemovingAgainIsNotFound() {
        Object id =
                added(blockOf(today.plusDays(1), "10:00", "11:00", "Training")).get("id");

        status(api.call(alpha, "DELETE", blockedTime(nimali) + "/" + id, null, salonOwner), 204);

        assertThat(listed()).isEmpty();
        refused(api.call(alpha, "DELETE", blockedTime(nimali) + "/" + id, null, salonOwner), 404, "not_found");
    }

    @Test
    void removingOneThatHasPassedIsNotFoundAndItStays() {
        UUID passed = insertedBlock(today.minusDays(1), "10:00", "11:00");

        refused(api.call(alpha, "DELETE", blockedTime(nimali) + "/" + passed, null, salonOwner), 404, "not_found");

        assertThat(rows()).isEqualTo(1);
    }

    @Test
    void removingAnotherPersonsBlockThroughThisPersonIsNotFound() {
        Object id =
                added(blockOf(today.plusDays(1), "10:00", "11:00", "Training")).get("id");
        Object kasun = added("Kasun").get("id");

        refused(api.call(alpha, "DELETE", blockedTime(kasun) + "/" + id, null, salonOwner), 404, "not_found");

        assertThat(rows()).isEqualTo(1);
    }

    @Test
    void aRemovedPersonsBlocksAreUnreadableAndCannotBeChanged() {
        Object id =
                added(blockOf(today.plusDays(1), "10:00", "11:00", "Training")).get("id");
        Map<String, Object> person = read(nimali);
        status(
                api.call(
                        alpha,
                        "DELETE",
                        TeamFixture.MEMBERS + "/" + nimali + "?version=" + person.get("version"),
                        null,
                        salonOwner),
                204);

        refused(api.call(alpha, "DELETE", blockedTime(nimali) + "/" + id, null, salonOwner), 404, "not_found");
        refused(add(blockOf(today.plusDays(2), "10:00", "11:00", "Training")), 404, "not_found");
        assertThat(rows()).as("its row stays until salon erasure").isEqualTo(1);
    }
}
