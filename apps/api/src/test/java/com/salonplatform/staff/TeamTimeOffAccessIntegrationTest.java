package com.salonplatform.staff;

import static com.salonplatform.staff.TeamFixture.blockOf;
import static com.salonplatform.staff.TeamFixture.blockedTime;
import static com.salonplatform.staff.TeamFixture.leave;
import static com.salonplatform.staff.TeamFixture.leaveOf;
import static com.salonplatform.staff.TeamFixture.schedule;
import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.identity.domain.StaffRole;
import com.salonplatform.testing.Http;
import com.salonplatform.testing.TestSalons;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Who reaches a person's leave and blocks (F06-S03 AC-9, AC-10, AC-11, AC-12). A leave's type and note are private (decision
 * 104): every route they could leave by is read here as a caller without {@code team.manage}, for a Sick leave with a note, and
 * the words are looked for in the whole answer, so a field added later cannot carry them out unnoticed.
 */
class TeamTimeOffAccessIntegrationTest extends TwoSalonTeam {

    private static final String PRIVATE_NOTE = "Hospital appointment";
    private static final String SICK = "sick";

    private LocalDate today;
    private Object nimali;
    private LocalDate first;
    private LocalDate last;
    private Object leaveId;
    private Object blockId;

    @BeforeEach
    void aPersonOnSickLeaveWithABlock() {
        today = LocalDate.ofInstant(clock.instant(), TestSalons.DEFAULT_TIME_ZONE);
        nimali = added("Nimali").get("id");
        first = today.plusDays(3);
        last = today.plusDays(5);
        Http.Exchange added =
                api.call(alpha, "POST", leave(nimali), leaveOf(first, last, SICK, PRIVATE_NOTE), salonOwner);
        status(added, 201);
        leaveId = map(api.body(added).get("leave")).get("id");
        Http.Exchange block = api.call(
                alpha,
                "POST",
                blockedTime(nimali),
                blockOf(today.plusDays(1), "10:00", "11:00", "Colour training"),
                salonOwner);
        status(block, 201);
        blockId = map(api.body(block).get("block")).get("id");
    }

    private String signedIn(StaffRole role) {
        return settings.signedIn(alpha, role).token();
    }

    /** Only the days, the id and the count: nothing of its type or note, under any name. */
    private void onlyTheDays(Map<String, Object> entry) {
        assertThat(entry).containsOnlyKeys("id", "firstDay", "lastDay", "bookingsToResolve");
        assertThat(entry).containsEntry("firstDay", first.toString()).containsEntry("lastDay", last.toString());
    }

    private void nothingPrivateIn(Http.Exchange answer) {
        assertThat(answer.body())
                .doesNotContain(PRIVATE_NOTE)
                .doesNotContainIgnoringCase("\"sick\"")
                .doesNotContain("\"type\"")
                .doesNotContain("hasNote");
    }

    private List<Http.Exchange> everyChange(String token) {
        return List.of(
                api.call(
                        alpha,
                        "POST",
                        leave(nimali),
                        leaveOf(today.plusDays(20), today.plusDays(20), "other", null),
                        token),
                api.call(alpha, "DELETE", leave(nimali) + "/" + leaveId, null, token),
                api.call(
                        alpha,
                        "POST",
                        blockedTime(nimali),
                        blockOf(today.plusDays(20), "10:00", "11:00", "Nope"),
                        token),
                api.call(alpha, "DELETE", blockedTime(nimali) + "/" + blockId, null, token));
    }

    private void refusedEveryChange(String token, String code) {
        everyChange(token).forEach(answer -> refused(answer, 403, code));
    }

    private String leaveAffected() {
        return leave(nimali) + "/affected?firstDay=" + first + "&lastDay=" + last;
    }

    private String blockAffected() {
        return blockedTime(nimali) + "/affected?date=" + today.plusDays(1) + "&starts=10:00&finishes=11:00";
    }

    // --- a leave's type and note, by every route ------------------------------------------------------------------

    @Test
    void anOwnerAndAManagerReceiveItsTypeAndNoteInTheScheduleAndTheCard() {
        for (String token : List.of(salonOwner, signedIn(StaffRole.MANAGER))) {
            Map<String, Object> entry = maps(api.body(api.call(alpha, "GET", schedule(nimali), null, token))
                            .get("leave"))
                    .getFirst();
            assertThat(entry).containsEntry("type", SICK).containsEntry("note", PRIVATE_NOTE);
            assertThat(map(read(nimali).get("nextLeave")))
                    .containsEntry("type", SICK)
                    .containsEntry("firstDay", first.toString())
                    .containsEntry("lastDay", last.toString());
        }
    }

    @Test
    void aReceptionistAProfessionalAndASupportSessionReceiveOnlyTheDaysFromTheSchedule() {
        for (String token : List.of(
                signedIn(StaffRole.RECEPTIONIST), signedIn(StaffRole.PROFESSIONAL), settings.supportSession(alpha))) {
            Http.Exchange read = api.call(alpha, "GET", schedule(nimali), null, token);

            status(read, 200);
            nothingPrivateIn(read);
            onlyTheDays(maps(api.body(read).get("leave")).getFirst());
            assertThat(maps(api.body(read).get("blockedTime")).getFirst()).containsEntry("label", "Colour training");
        }
    }

    @Test
    void aReceptionistAProfessionalAndASupportSessionReceiveOnlyTheDaysOnTheMemberListAndTheMember() {
        for (String token : List.of(
                signedIn(StaffRole.RECEPTIONIST), signedIn(StaffRole.PROFESSIONAL), settings.supportSession(alpha))) {
            Http.Exchange list = api.call(alpha, "GET", TeamFixture.MEMBERS, null, token);
            Http.Exchange one = api.call(alpha, "GET", TeamFixture.MEMBERS + "/" + nimali, null, token);

            status(list, 200);
            status(one, 200);
            nothingPrivateIn(list);
            nothingPrivateIn(one);
            Map<String, Object> onTheList = maps(api.body(list).get("items")).getFirst();
            assertThat(map(onTheList.get("nextLeave")))
                    .containsOnlyKeys("firstDay", "lastDay")
                    .containsEntry("firstDay", first.toString());
            assertThat(map(api.body(one).get("nextLeave"))).containsOnlyKeys("firstDay", "lastDay");
        }
    }

    @Test
    void aCallerWithoutTeamManageIsRefusedTheLeavesImpactSoCannotProbeIt() {
        for (String token : List.of(
                signedIn(StaffRole.RECEPTIONIST), signedIn(StaffRole.PROFESSIONAL), settings.supportSession(alpha))) {
            refused(api.call(alpha, "GET", leaveAffected(), null, token), 403, "permission_denied");
        }
    }

    @Test
    void thePublicListHasNothingOfLeaveOrBlocksByAnyName() {
        // Only someone who performs a bookable service is listed, so this person is, with leave and a block of their
        // own.
        UUID colour = category(alpha, salonOwner, "Colour");
        service(alpha, salonOwner, "Bookable", colour, true);
        Map<String, Object> shown = TeamFixture.person("Shown Person");
        shown.put("services", TeamFixture.services(List.of(colour), List.of()));
        Http.Exchange person = api.call(alpha, "POST", TeamFixture.MEMBERS, shown, salonOwner);
        status(person, 201);
        Object id = api.body(person).get("id");
        status(api.call(alpha, "POST", leave(id), leaveOf(first, last, SICK, PRIVATE_NOTE), salonOwner), 201);
        status(
                api.call(
                        alpha,
                        "POST",
                        blockedTime(id),
                        blockOf(today.plusDays(1), "10:00", "11:00", "Colour training"),
                        salonOwner),
                201);

        Http.Exchange list = api.call(alpha, "GET", TeamFixture.PUBLIC, null, null);

        status(list, 200);
        assertThat(maps(api.body(list).get("items")))
                .extracting(each -> each.get("id"))
                .containsExactly(id);
        assertThat(list.body())
                .doesNotContain(PRIVATE_NOTE)
                .doesNotContain("Colour training")
                .doesNotContainIgnoringCase("leave")
                .doesNotContainIgnoringCase("blocked")
                .doesNotContain(first.toString());
    }

    @Test
    void theAuditTrailRecordsTheDaysAndHasNoteAndNeverTheTypeOrNoteInTheRowsOrThroughEveryRoute() {
        status(api.call(alpha, "DELETE", leave(nimali) + "/" + leaveId, null, salonOwner), 204);

        Map<String, Object> added = audited("staff.team_member_leave_added");
        assertThat(added.get("actor_kind")).isEqualTo("staff");
        assertThat(added.get("actor_id")).isNotNull();
        assertThat(added.get("created_at")).isNotNull();
        assertThat(added.get("before_values")).isNull();
        assertThat((String) added.get("after_values"))
                .contains(first.toString(), last.toString(), "\"hasNote\": true", leaveId.toString())
                .doesNotContain(PRIVATE_NOTE)
                .doesNotContain(SICK)
                .doesNotContain("type");
        Map<String, Object> removed = audited("staff.team_member_leave_removed");
        assertThat(removed.get("after_values")).isNull();
        assertThat((String) removed.get("before_values"))
                .contains(first.toString(), last.toString(), "\"hasNote\": true")
                .doesNotContain(PRIVATE_NOTE)
                .doesNotContain(SICK)
                .doesNotContain("type");

        // The route that lists the audit today shows identity's and support's entries only, so these do not reach it at
        // all.
        // The rows above are what keeps them private if a later route lists them; this keeps the route honest
        // meanwhile.
        for (String token : List.of(settings.supportSession(alpha), salonOwner)) {
            Http.Exchange list = settings.call(alpha, "GET", "/api/v1/identity/audit?size=100", null, token);
            status(list, 200);
            assertThat(list.body()).doesNotContain(PRIVATE_NOTE).doesNotContain("\"sick\"");
        }
    }

    @Test
    void aLeaveWithNoNoteIsAuditedAsHavingNone() {
        Http.Exchange added = api.call(
                alpha,
                "POST",
                leave(nimali),
                leaveOf(today.plusDays(9), today.plusDays(9), "annual", null),
                salonOwner);
        status(added, 201);

        assertThat((String) audited("staff.team_member_leave_added").get("after_values"))
                .contains("\"hasNote\": false");
    }

    @Test
    void aBlockAddedAndRemovedIsAuditedWithItsLabel() {
        status(api.call(alpha, "DELETE", blockedTime(nimali) + "/" + blockId, null, salonOwner), 204);

        Map<String, Object> added = audited("staff.team_member_blocked_time_added");
        assertThat(added.get("before_values")).isNull();
        assertThat((String) added.get("after_values"))
                .contains("Colour training", today.plusDays(1).toString(), "10:00", "11:00", blockId.toString());
        Map<String, Object> removed = audited("staff.team_member_blocked_time_removed");
        assertThat(removed.get("after_values")).isNull();
        assertThat((String) removed.get("before_values")).contains("Colour training", "10:00", "11:00");
    }

    private Map<String, Object> audited(String action) {
        return owner.queryForMap(
                "SELECT actor_kind, actor_id, created_at, before_values::text AS before_values,"
                        + " after_values::text AS after_values FROM audit_entry"
                        + " WHERE tenant_id = ? AND subject_id = ? AND action = ? ORDER BY created_at DESC LIMIT 1",
                alpha.id(),
                nimali.toString(),
                action);
    }

    // --- the card's next leave (AC-9) -----------------------------------------------------------------------------

    @Test
    void someoneWithNoLeaveHasANullNextLeaveAndSomeoneJustAddedToo() {
        Map<String, Object> kasun = added("Kasun");

        assertThat(kasun).containsEntry("nextLeave", null);
        assertThat(read(kasun.get("id"))).containsEntry("nextLeave", null);
    }

    @Test
    void theCardShowsTheEarliestOfSeveralAndItFollowsARemoval() {
        Http.Exchange sooner = api.call(
                alpha,
                "POST",
                leave(nimali),
                leaveOf(today.plusDays(1), today.plusDays(1), "annual", null),
                salonOwner);
        status(sooner, 201);
        assertThat(map(read(nimali).get("nextLeave")))
                .containsEntry("firstDay", today.plusDays(1).toString())
                .containsEntry("type", "annual");

        status(
                api.call(
                        alpha,
                        "DELETE",
                        leave(nimali) + "/" + map(api.body(sooner).get("leave")).get("id"),
                        null,
                        salonOwner),
                204);

        assertThat(map(read(nimali).get("nextLeave"))).containsEntry("firstDay", first.toString());
    }

    @Test
    void savingAProfileAnswersTheNextLeaveToo() {
        Map<String, Object> edit = TeamFixture.saveOf(read(nimali));
        edit.put("title", "Colourist");

        Http.Exchange saved = api.call(alpha, "PUT", TeamFixture.MEMBERS + "/" + nimali, edit, salonOwner);

        status(saved, 200);
        assertThat(map(api.body(saved).get("nextLeave"))).containsEntry("type", SICK);
    }

    // --- roles (AC-10) ---------------------------------------------------------------------------------------------

    @Test
    void aReceptionistAddsAndRemovesBlocksAndNeverLeave() {
        String receptionist = signedIn(StaffRole.RECEPTIONIST);

        refused(
                api.call(
                        alpha,
                        "POST",
                        leave(nimali),
                        leaveOf(today.plusDays(20), today.plusDays(20), "other", null),
                        receptionist),
                403,
                "permission_denied");
        refused(api.call(alpha, "DELETE", leave(nimali) + "/" + leaveId, null, receptionist), 403, "permission_denied");

        status(api.call(alpha, "GET", blockAffected(), null, receptionist), 200);
        Http.Exchange added = api.call(
                alpha,
                "POST",
                blockedTime(nimali),
                blockOf(today.plusDays(20), "10:00", "11:00", "Errand"),
                receptionist);
        status(added, 201);
        status(
                api.call(
                        alpha,
                        "DELETE",
                        blockedTime(nimali) + "/"
                                + map(api.body(added).get("block")).get("id"),
                        null,
                        receptionist),
                204);
    }

    @Test
    void aManagerAddsAndRemovesBoth() {
        String manager = signedIn(StaffRole.MANAGER);

        status(api.call(alpha, "GET", leaveAffected(), null, manager), 200);
        status(api.call(alpha, "GET", blockAffected(), null, manager), 200);
        Http.Exchange block = api.call(
                alpha, "POST", blockedTime(nimali), blockOf(today.plusDays(20), "10:00", "11:00", "Errand"), manager);
        status(block, 201);
        Http.Exchange more = api.call(
                alpha,
                "POST",
                leave(nimali),
                leaveOf(today.plusDays(20), today.plusDays(20), "personal", "Mine"),
                manager);
        status(more, 201);
        assertThat(map(api.body(more).get("leave")))
                .containsEntry("type", "personal")
                .containsEntry("note", "Mine");
        status(api.call(alpha, "DELETE", leave(nimali) + "/" + leaveId, null, manager), 204);
    }

    @Test
    void aProfessionalReadsAndIsRefusedEveryChangeAndEveryImpact() {
        String professional = signedIn(StaffRole.PROFESSIONAL);

        status(api.call(alpha, "GET", schedule(nimali), null, professional), 200);
        refusedEveryChange(professional, "permission_denied");
        refused(api.call(alpha, "GET", leaveAffected(), null, professional), 403, "permission_denied");
        refused(api.call(alpha, "GET", blockAffected(), null, professional), 403, "permission_denied");
    }

    @Test
    void aSupportSessionReadsAndIsRefusedEveryChangeAsReadOnly() {
        String support = settings.supportSession(alpha);

        status(api.call(alpha, "GET", schedule(nimali), null, support), 200);
        refusedEveryChange(support, "support_read_only");
        refused(api.call(alpha, "GET", blockAffected(), null, support), 403, "permission_denied");
    }

    @Test
    void anAccountantGetsPermissionDeniedFromEveryEndpointHere() {
        String accountant = signedIn(StaffRole.ACCOUNTANT);

        refused(api.call(alpha, "GET", schedule(nimali), null, accountant), 403, "permission_denied");
        refused(api.call(alpha, "GET", TeamFixture.MEMBERS, null, accountant), 403, "permission_denied");
        refused(api.call(alpha, "GET", leaveAffected(), null, accountant), 403, "permission_denied");
        refused(api.call(alpha, "GET", blockAffected(), null, accountant), 403, "permission_denied");
        refusedEveryChange(accountant, "permission_denied");
    }

    @Test
    void noSessionIsRefusedAndAReadOnlyOrSuspendedSalonAnswersAsTheCatalogueDoes() {
        refused(api.call(alpha, "GET", leaveAffected(), null, null), 401, "invalid_session");
        everyChange(null).forEach(answer -> refused(answer, 401, "invalid_session"));

        owner.update("UPDATE tenant SET access_state = 'read_only' WHERE id = ?", alpha.id());
        status(api.call(alpha, "GET", schedule(nimali), null, salonOwner), 200);
        status(api.call(alpha, "GET", leaveAffected(), null, salonOwner), 200);
        refusedEveryChange(salonOwner, "salon_read_only");
        owner.update("UPDATE tenant SET access_state = 'full' WHERE id = ?", alpha.id());

        salons.suspend(alpha);
        refused(api.call(alpha, "GET", leaveAffected(), null, salonOwner), 403, "salon_suspended");
        refused(api.call(alpha, "GET", blockAffected(), null, salonOwner), 403, "salon_suspended");
        refusedEveryChange(salonOwner, "salon_suspended");
        salons.activate(alpha);
    }

    @Test
    void theImpactReadsRefuseBadInputNamingEachField() {
        fieldErrors(api.call(alpha, "GET", leave(nimali) + "/affected", null, salonOwner), "firstDay", "lastDay");
        fieldErrors(
                api.call(
                        alpha,
                        "GET",
                        leave(nimali) + "/affected?firstDay=" + last + "&lastDay=" + first,
                        null,
                        salonOwner),
                "lastDay");
        fieldErrors(
                api.call(alpha, "GET", blockedTime(nimali) + "/affected", null, salonOwner),
                "date",
                "starts",
                "finishes");
        fieldErrors(
                api.call(
                        alpha,
                        "GET",
                        blockedTime(nimali) + "/affected?date=" + today + "&starts=11:00&finishes=10:00",
                        null,
                        salonOwner),
                "finishes");
        refused(
                api.call(
                        alpha,
                        "GET",
                        leave(UUID.randomUUID()) + "/affected?firstDay=" + first + "&lastDay=" + last,
                        null,
                        salonOwner),
                404,
                "not_found");
    }

    // --- another salon (AC-11) -------------------------------------------------------------------------------------

    @Test
    void anotherSalonsOwnerFindsNoneOfThisByAnyEndpoint() {
        String betaOwner = settings.signedIn(beta, StaffRole.OWNER).token();

        refused(api.call(beta, "GET", schedule(nimali), null, betaOwner), 404, "not_found");
        refused(
                api.call(beta, "POST", leave(nimali), leaveOf(today, today, "other", null), betaOwner),
                404,
                "not_found");
        refused(api.call(beta, "DELETE", leave(nimali) + "/" + leaveId, null, betaOwner), 404, "not_found");
        refused(
                api.call(
                        beta,
                        "GET",
                        leave(nimali) + "/affected?firstDay=" + today + "&lastDay=" + today,
                        null,
                        betaOwner),
                404,
                "not_found");
        refused(
                api.call(beta, "POST", blockedTime(nimali), blockOf(today, "10:00", "11:00", "Nope"), betaOwner),
                404,
                "not_found");
        refused(api.call(beta, "DELETE", blockedTime(nimali) + "/" + blockId, null, betaOwner), 404, "not_found");
        refused(
                api.call(
                        beta,
                        "GET",
                        blockedTime(nimali) + "/affected?date=" + today + "&starts=10:00&finishes=11:00",
                        null,
                        betaOwner),
                404,
                "not_found");
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM staff_member_leave WHERE id = ?::uuid", Long.class, leaveId.toString()))
                .isEqualTo(1);
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM staff_member_blocked_time WHERE id = ?::uuid",
                        Long.class,
                        blockId.toString()))
                .isEqualTo(1);
    }

    @Test
    void anotherSalonsLeaveThroughThatSalonsOwnMemberIsNotFound() {
        String betaOwner = settings.signedIn(beta, StaffRole.OWNER).token();
        Http.Exchange kasun = api.call(beta, "POST", TeamFixture.MEMBERS, TeamFixture.person("Kasun"), betaOwner);
        status(kasun, 201);
        Object betasMember = api.body(kasun).get("id");

        refused(api.call(beta, "DELETE", leave(betasMember) + "/" + leaveId, null, betaOwner), 404, "not_found");
        refused(api.call(beta, "DELETE", blockedTime(betasMember) + "/" + blockId, null, betaOwner), 404, "not_found");
        Http.Exchange read = api.call(beta, "GET", schedule(betasMember), null, betaOwner);
        assertThat(maps(api.body(read).get("leave"))).isEmpty();
        assertThat(maps(api.body(read).get("blockedTime"))).isEmpty();
    }

    @Test
    void anotherSalonsSessionOnThisHostIsNotASession() {
        String betaOwner = settings.signedIn(beta, StaffRole.OWNER).token();

        Http.Exchange read = api.call(alpha, "GET", schedule(nimali), null, betaOwner);

        assertThat(read.status()).isIn(401, 404);
        assertThat(read.body()).doesNotContain(PRIVATE_NOTE).doesNotContain("Colour training");
    }
}
