package com.salonplatform.staff;

import static com.salonplatform.staff.TeamFixture.leave;
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

/** Leave from outside (F06-S03 AC-1, AC-2, AC-3), every answer held to the committed contract. */
class TeamLeaveIntegrationTest extends TwoSalonTeam {

    private LocalDate today;
    private Object nimali;

    @BeforeEach
    void aPerson() {
        today = LocalDate.ofInstant(clock.instant(), TestSalons.DEFAULT_TIME_ZONE);
        nimali = added("Nimali").get("id");
    }

    private Http.Exchange add(Map<String, Object> body) {
        return api.call(alpha, "POST", leave(nimali), body, salonOwner);
    }

    private Map<String, Object> added(Map<String, Object> body) {
        Http.Exchange answer = add(body);
        status(answer, 201);
        return map(api.body(answer).get("leave"));
    }

    private List<Map<String, Object>> listed() {
        Http.Exchange read = api.call(alpha, "GET", schedule(nimali), null, salonOwner);
        status(read, 200);
        return maps(api.body(read).get("leave"));
    }

    private long rows() {
        return owner.queryForObject(
                "SELECT count(*) FROM staff_member_leave WHERE tenant_id = ? AND member_id = ?::uuid",
                Long.class,
                alpha.id(),
                nimali.toString());
    }

    /** Leave that has already started or ended, which only SQL can make, as a request may not. */
    private UUID insertedLeave(LocalDate first, LocalDate last) {
        UUID id = UUID.randomUUID();
        owner.update(
                """
                INSERT INTO staff_member_leave (tenant_id, id, branch_id, member_id, first_day, last_day, leave_type,
                    created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?::uuid, ?, ?, 'annual', ?, now(), now())
                """, alpha.id(), id, salons.defaultBranch(alpha), nimali.toString(), first, last, UUID.randomUUID());
        return id;
    }

    // --- AC-1: adding ----------------------------------------------------------------------------------------------

    @Test
    void addedLeaveAnswersEveryFieldWithItsTypeAndNote() {
        Http.Exchange answer = add(leaveOf(today.plusDays(1), today.plusDays(3), "annual", "  Family trip  "));

        status(answer, 201);
        assertThat(map(api.body(answer).get("leave")))
                .containsEntry("firstDay", today.plusDays(1).toString())
                .containsEntry("lastDay", today.plusDays(3).toString())
                .containsEntry("type", "annual")
                .containsEntry("note", "Family trip")
                .containsEntry("bookingsToResolve", 0)
                .containsKey("id");
        assertThat(maps(api.body(answer).get("affected"))).isEmpty();
    }

    @Test
    void everyTypeIsTaken() {
        int day = 1;
        for (String type : List.of("annual", "sick", "personal", "other")) {
            assertThat(added(leaveOf(today.plusDays(day), today.plusDays(day), type, null)))
                    .containsEntry("type", type);
            day++;
        }
    }

    @Test
    void anEmptyOrBlankNoteIsNone() {
        assertThat(added(leaveOf(today.plusDays(1), today.plusDays(1), "other", null)))
                .containsEntry("note", null);
        assertThat(added(leaveOf(today.plusDays(2), today.plusDays(2), "other", "")))
                .containsEntry("note", null);
        assertThat(added(leaveOf(today.plusDays(3), today.plusDays(3), "other", "   ")))
                .containsEntry("note", null);
    }

    @Test
    void aNoteOf60CharactersIsTakenAnd61IsAFieldError() {
        assertThat(added(leaveOf(today.plusDays(1), today.plusDays(1), "other", "x".repeat(60))))
                .containsEntry("note", "x".repeat(60));

        fieldErrors(add(leaveOf(today.plusDays(2), today.plusDays(2), "other", "x".repeat(61))), "note");
        assertThat(rows()).isEqualTo(1);
    }

    @Test
    void aNoteWithAControlCharacterIsAFieldError() {
        fieldErrors(add(leaveOf(today.plusDays(1), today.plusDays(1), "other", "one\ntwo")), "note");
    }

    @Test
    void todayIsTakenAsTheFirstDayAndAOneDayLeaveIsTaken() {
        assertThat(added(leaveOf(today, today, "annual", null)))
                .containsEntry("firstDay", today.toString())
                .containsEntry("lastDay", today.toString());
    }

    @Test
    void yesterdayIsAFieldErrorAndNothingIsSaved() {
        fieldErrors(add(leaveOf(today.minusDays(1), today, "annual", null)), "firstDay");
        assertThat(rows()).isZero();
    }

    @Test
    void aLastDayBeforeTheFirstIsAFieldErrorAndNothingIsSaved() {
        fieldErrors(add(leaveOf(today.plusDays(3), today.plusDays(2), "annual", null)), "lastDay");
        assertThat(rows()).isZero();
    }

    @Test
    void aYearAheadIsTakenAndADayBeyondIsAFieldError() {
        assertThat(added(leaveOf(today.plusYears(1), today.plusYears(1), "annual", null)))
                .containsEntry("lastDay", today.plusYears(1).toString());

        fieldErrors(add(leaveOf(today.plusDays(1), today.plusYears(1).plusDays(1), "annual", null)), "lastDay");
        assertThat(rows()).isEqualTo(1);
    }

    @Test
    void aFirstDayBeyondAYearNamesFirstDayAloneAndALastDayBeyondWithAFineFirstNamesLastDay() {
        LocalDate beyond = today.plusYears(1).plusDays(1);

        fieldErrors(add(leaveOf(beyond, beyond, "annual", null)), "firstDay");
        fieldErrors(add(leaveOf(beyond, beyond.plusDays(5), "annual", null)), "firstDay");
        fieldErrors(add(leaveOf(today, beyond, "annual", null)), "lastDay");
        assertThat(rows()).isZero();
    }

    @Test
    void aFirstDayInThePastNamesFirstDayAloneThoughTheLastIsPastToo() {
        fieldErrors(add(leaveOf(today.minusDays(3), today.minusDays(1), "annual", null)), "firstDay");
        assertThat(rows()).isZero();
    }

    @Test
    void anUnknownTypeAndAMissingOneAreFieldErrors() {
        fieldErrors(add(leaveOf(today, today, "holiday", null)), "type");
        fieldErrors(add(leaveOf(today, today, null, null)), "type");
    }

    @Test
    void impossibleDatesAndEveryBrokenRuleAreNamedTogether() {
        Map<String, Object> body = leaveOf("2026-02-30", "not a date", "nothing", "x".repeat(61));

        fieldErrors(add(body), "firstDay", "lastDay", "type", "note");
    }

    @Test
    void anUnknownFieldIsAFieldError() {
        Map<String, Object> body = leaveOf(today, today, "annual", null);
        body.put("workingAnyway", true);

        fieldErrors(add(body), "workingAnyway");
    }

    @Test
    void addingForSomeoneWhoIsNotOnTheTeamIsNotFound() {
        refused(
                api.call(alpha, "POST", leave(UUID.randomUUID()), leaveOf(today, today, "annual", null), salonOwner),
                404,
                "not_found");
        refused(
                api.call(alpha, "POST", leave("not-an-id"), leaveOf(today, today, "annual", null), salonOwner),
                404,
                "not_found");
    }

    @Test
    void addingLeaveDoesNotMoveTheMembersVersionOrTheSchedulesVersion() {
        Object memberVersion = read(nimali).get("version");
        Http.Exchange before = api.call(alpha, "GET", schedule(nimali), null, salonOwner);
        Object scheduleVersion = api.body(before).get("version");

        added(leaveOf(today, today, "annual", null));

        assertThat(read(nimali).get("version")).isEqualTo(memberVersion);
        assertThat(api.body(api.call(alpha, "GET", schedule(nimali), null, salonOwner))
                        .get("version"))
                .isEqualTo(scheduleVersion);
    }

    // --- AC-2: overlap and the limit -------------------------------------------------------------------------------

    @Test
    void overlappingLeaveIsAConflictInEveryShapeAndNothingIsSaved() {
        added(leaveOf(today.plusDays(10), today.plusDays(14), "annual", null));

        for (List<Integer> days : List.of(
                List.of(10, 14), // the same
                List.of(8, 10), // up to its first day
                List.of(14, 16), // from its last day
                List.of(12, 12), // inside
                List.of(8, 20))) { // around it
            refused(
                    add(leaveOf(today.plusDays(days.get(0)), today.plusDays(days.get(1)), "other", null)),
                    409,
                    "conflict");
        }
        assertThat(rows()).isEqualTo(1);
    }

    @Test
    void leaveOnTheDaysEitherSideDoesNotOverlap() {
        added(leaveOf(today.plusDays(10), today.plusDays(14), "annual", null));

        added(leaveOf(today.plusDays(8), today.plusDays(9), "other", null));
        added(leaveOf(today.plusDays(15), today.plusDays(16), "other", null));

        assertThat(rows()).isEqualTo(3);
    }

    @Test
    void anotherPersonsLeaveIsNoConflict() {
        added(leaveOf(today.plusDays(1), today.plusDays(1), "annual", null));
        Object kasun = added("Kasun").get("id");

        status(
                api.call(
                        alpha,
                        "POST",
                        leave(kasun),
                        leaveOf(today.plusDays(1), today.plusDays(1), "annual", null),
                        salonOwner),
                201);
    }

    @Test
    void leaveUnderWayConflictsWithNewLeaveOnItsRemainingDays() {
        insertedLeave(today.minusDays(2), today.plusDays(2));

        refused(add(leaveOf(today, today, "annual", null)), 409, "conflict");
        status(add(leaveOf(today.plusDays(3), today.plusDays(3), "annual", null)), 201);
    }

    @Test
    void theHundredthLeaveIsTakenAndTheNextIsLimitReached() {
        for (int i = 1; i <= 99; i++) {
            insertedLeave(today.plusDays(i), today.plusDays(i));
        }

        status(add(leaveOf(today.plusDays(100), today.plusDays(100), "annual", null)), 201);
        assertThat(rows()).isEqualTo(100);

        refused(add(leaveOf(today.plusDays(101), today.plusDays(101), "annual", null)), 409, "limit_reached");
        assertThat(rows()).isEqualTo(100);
    }

    @Test
    void leaveThatHasEndedDoesNotCountTowardTheLimitAndLeaveUnderWayDoes() {
        for (int i = 1; i <= 98; i++) {
            insertedLeave(today.plusDays(i), today.plusDays(i));
        }
        insertedLeave(today.minusDays(5), today.minusDays(4));
        insertedLeave(today.minusDays(1), today);

        status(add(leaveOf(today.plusDays(100), today.plusDays(100), "annual", null)), 201);
        refused(add(leaveOf(today.plusDays(101), today.plusDays(101), "annual", null)), 409, "limit_reached");
    }

    @Test
    void aConflictIsSaidBeforeTheLimit() {
        for (int i = 1; i <= 100; i++) {
            insertedLeave(today.plusDays(i), today.plusDays(i));
        }

        refused(add(leaveOf(today.plusDays(1), today.plusDays(1), "annual", null)), 409, "conflict");
    }

    // --- AC-3: the list and Remove ---------------------------------------------------------------------------------

    @Test
    void noLeaveListsEmpty() {
        assertThat(listed()).isEmpty();
    }

    @Test
    void theListIsInDateOrderWithoutWhatHasEnded() {
        Object later = added(leaveOf(today.plusDays(10), today.plusDays(11), "sick", "Later"))
                .get("id");
        Object sooner = added(leaveOf(today.plusDays(2), today.plusDays(2), "annual", null))
                .get("id");
        UUID underWay = insertedLeave(today.minusDays(1), today);
        insertedLeave(today.minusDays(5), today.minusDays(1));

        assertThat(listed()).extracting(each -> each.get("id")).containsExactly(underWay.toString(), sooner, later);
        assertThat(listed().getLast())
                .containsEntry("firstDay", today.plusDays(10).toString())
                .containsEntry("lastDay", today.plusDays(11).toString())
                .containsEntry("type", "sick")
                .containsEntry("note", "Later")
                .containsEntry("bookingsToResolve", 0);
    }

    @Test
    void removingTakesItAwayAndRemovingAgainIsNotFound() {
        Object id = added(leaveOf(today.plusDays(1), today.plusDays(2), "annual", null))
                .get("id");

        status(api.call(alpha, "DELETE", leave(nimali) + "/" + id, null, salonOwner), 204);

        assertThat(listed()).isEmpty();
        assertThat(rows()).isZero();
        refused(api.call(alpha, "DELETE", leave(nimali) + "/" + id, null, salonOwner), 404, "not_found");
    }

    @Test
    void leaveUnderWayIsRemovedAndLeaveEndingTodayToo() {
        UUID underWay = insertedLeave(today.minusDays(1), today);

        status(api.call(alpha, "DELETE", leave(nimali) + "/" + underWay, null, salonOwner), 204);
    }

    @Test
    void removingLeaveThatHasEndedIsNotFoundAndItStays() {
        UUID ended = insertedLeave(today.minusDays(3), today.minusDays(1));

        refused(api.call(alpha, "DELETE", leave(nimali) + "/" + ended, null, salonOwner), 404, "not_found");

        assertThat(rows()).isEqualTo(1);
    }

    @Test
    void removingAnotherPersonsLeaveThroughThisPersonIsNotFound() {
        Object id = added(leaveOf(today.plusDays(1), today.plusDays(2), "annual", null))
                .get("id");
        Object kasun = added("Kasun").get("id");

        refused(api.call(alpha, "DELETE", leave(kasun) + "/" + id, null, salonOwner), 404, "not_found");

        assertThat(rows()).isEqualTo(1);
    }

    @Test
    void afterRemovalTheDaysCanBeTakenAgain() {
        Object id = added(leaveOf(today.plusDays(1), today.plusDays(2), "annual", null))
                .get("id");
        status(api.call(alpha, "DELETE", leave(nimali) + "/" + id, null, salonOwner), 204);

        status(add(leaveOf(today.plusDays(1), today.plusDays(2), "annual", null)), 201);
    }

    @Test
    void aRemovedPersonsLeaveIsUnreadableAndCannotBeChanged() {
        Object id = added(leaveOf(today.plusDays(1), today.plusDays(2), "annual", null))
                .get("id");
        Map<String, Object> person = read(nimali);
        status(
                api.call(
                        alpha,
                        "DELETE",
                        TeamFixture.MEMBERS + "/" + nimali + "?version=" + person.get("version"),
                        null,
                        salonOwner),
                204);

        refused(api.call(alpha, "GET", schedule(nimali), null, salonOwner), 404, "not_found");
        refused(api.call(alpha, "DELETE", leave(nimali) + "/" + id, null, salonOwner), 404, "not_found");
        refused(add(leaveOf(today.plusDays(5), today.plusDays(5), "annual", null)), 404, "not_found");
        assertThat(rows()).as("its row stays until salon erasure").isEqualTo(1);
    }
}
