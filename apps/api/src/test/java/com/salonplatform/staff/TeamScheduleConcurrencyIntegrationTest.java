package com.salonplatform.staff;

import static com.salonplatform.staff.TeamFixture.dateChanges;
import static com.salonplatform.staff.TeamFixture.notWorking;
import static com.salonplatform.staff.TeamFixture.schedule;
import static com.salonplatform.staff.TeamFixture.week;
import static com.salonplatform.staff.TeamFixture.weekSave;
import static com.salonplatform.staff.TeamFixture.weekly;
import static com.salonplatform.staff.TeamFixture.workday;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.salonplatform.testing.Http;
import com.salonplatform.testing.TestSalons;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * What must hold when requests meet (F06-S02 AC-6, AC-7). None of this rests on timing: the requests are sent while a
 * transaction of this test holds the row they need, the test sees them waiting in the database, and only then lets them
 * go. A request that does not wait fails the test at once, so taking the lock away cannot pass by luck.
 */
class TeamScheduleConcurrencyIntegrationTest extends TwoSalonTeam {

    private LocalDate today;
    private Object nimali;

    @BeforeEach
    void aPerson() {
        today = LocalDate.ofInstant(clock.instant(), TestSalons.DEFAULT_TIME_ZONE);
        nimali = added("Nimali").get("id");
    }

    @FunctionalInterface
    private interface HeldWork {
        void run(Connection connection) throws Exception;
    }

    private long waitingForALock() {
        return owner.queryForObject("SELECT count(*) FROM pg_locks WHERE NOT granted", Long.class);
    }

    private static CompletableFuture<Http.Exchange> sending(Callable<Http.Exchange> request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return request.call();
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
    }

    /** Sends every request while the work's transaction is open, sees each wait, commits, and answers what they got. */
    @SafeVarargs
    private List<Http.Exchange> behind(HeldWork work, Callable<Http.Exchange>... requests) throws Exception {
        try (Connection held = owner.getDataSource().getConnection()) {
            held.setAutoCommit(false);
            work.run(held);
            long before = waitingForALock();
            List<CompletableFuture<Http.Exchange>> asked = new ArrayList<>();
            for (int i = 0; i < requests.length; i++) {
                asked.add(sending(requests[i]));
                long expected = before + i + 1;
                for (int tries = 0;
                        tries < 400
                                && waitingForALock() < expected
                                && !asked.getLast().isDone();
                        tries++) {
                    Thread.sleep(25);
                }
                assertThat(asked.getLast().isDone())
                        .as("request %d must wait, not be answered", i)
                        .isFalse();
            }
            held.commit();
            List<Http.Exchange> answers = new ArrayList<>();
            for (CompletableFuture<Http.Exchange> each : asked) {
                answers.add(each.get(60, TimeUnit.SECONDS));
            }
            return answers;
        }
    }

    /** Holds the member's row, as a save of their schedule does. */
    private HeldWork holdingTheMember(Object member) {
        return connection -> {
            try (PreparedStatement lock = connection.prepareStatement(
                    "SELECT 1 FROM staff_team_member WHERE tenant_id = ? AND id = ?::uuid FOR NO KEY UPDATE")) {
                lock.setObject(1, alpha.id());
                lock.setString(2, member.toString());
                lock.execute();
            }
        };
    }

    private long version(Object member) {
        Http.Exchange read = api.call(alpha, "GET", schedule(member), null, salonOwner);
        status(read, 200);
        return ((Number) api.body(read).get("version")).longValue();
    }

    private Map<String, Object> monday(Object member) {
        Http.Exchange read = api.call(alpha, "GET", schedule(member), null, salonOwner);
        return maps(api.body(read).get("weekly")).getFirst();
    }

    private Callable<Http.Exchange> saving(Object member, long version, List<Map<String, Object>> days) {
        return () -> api.call(alpha, "PUT", weekly(member), weekSave(version, days), salonOwner);
    }

    private Callable<Http.Exchange> adding(Object member, Map<String, Object> change) {
        return () -> api.call(alpha, "POST", dateChanges(member), change, salonOwner);
    }

    private List<Integer> statuses(List<Http.Exchange> answers) {
        return answers.stream().map(Http.Exchange::status).sorted().toList();
    }

    private String codeOfThe409(List<Http.Exchange> answers) {
        return answers.stream()
                .filter(answer -> answer.status() == 409)
                .map(answer -> (String) api.body(answer).get("code"))
                .findFirst()
                .orElseThrow();
    }

    // --- AC-6 -----------------------------------------------------------------------------------------------------

    @Test
    void ofTwoSavesOfOneVersionExactlyOneSucceeds() throws Exception {
        long at = version(nimali);

        List<Http.Exchange> answers = behind(
                holdingTheMember(nimali),
                saving(nimali, at, week(workday("monday", "09:00", "18:00"))),
                saving(nimali, at, week(workday("monday", "10:00", "19:00"))));

        assertThat(statuses(answers)).containsExactly(200, 409);
        assertThat(codeOfThe409(answers)).isEqualTo("conflict");
        assertThat(version(nimali)).isEqualTo(at + 1);
        // The week is one save's, whole: the winner's, not a mixture.
        Http.Exchange winner = answers.stream()
                .filter(answer -> answer.status() == 200)
                .findFirst()
                .orElseThrow();
        assertThat(monday(nimali))
                .containsEntry(
                        "starts",
                        maps(api.body(winner).get("weekly")).getFirst().get("starts"));
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM staff_member_weekday WHERE tenant_id = ? AND member_id = ?::uuid",
                        Long.class,
                        alpha.id(),
                        nimali.toString()))
                .isEqualTo(7);
    }

    /** A member from before this stage has no row to lock but their own, and two first saves meet there. */
    @Test
    void ofTwoFirstSavesOfAMemberFromBeforeThisStageExactlyOneSucceeds() throws Exception {
        UUID earlier = UUID.randomUUID();
        owner.update(
                "INSERT INTO staff_team_member (tenant_id, id, branch_id, name, shown_online, version, created_at,"
                        + " updated_at) VALUES (?, ?, ?, 'Earlier', true, 0, now(), now())",
                alpha.id(),
                earlier,
                salons.defaultBranch(alpha));

        List<Http.Exchange> answers = behind(
                holdingTheMember(earlier),
                saving(earlier, 0, week(workday("monday", "09:00", "18:00"))),
                saving(earlier, 0, week(workday("monday", "10:00", "19:00"))));

        assertThat(statuses(answers)).containsExactly(200, 409);
        assertThat(codeOfThe409(answers)).isEqualTo("conflict");
        assertThat(version(earlier)).isEqualTo(1);
    }

    @Test
    void theDatabaseItselfRefusesASecondScheduleRowForAMember() {
        assertThatThrownBy(() -> owner.update(
                        "INSERT INTO staff_member_schedule (tenant_id, id, branch_id, member_id, version, created_at,"
                                + " updated_at) VALUES (?, ?, ?, ?::uuid, 1, now(), now())",
                        alpha.id(),
                        UUID.randomUUID(),
                        salons.defaultBranch(alpha),
                        nimali.toString()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aSaveBesideAProfileEditDoesNotConflictAndBothAreKept() throws Exception {
        long at = version(nimali);
        Map<String, Object> profile = read(nimali);
        Map<String, Object> edit = TeamFixture.saveOf(profile);
        edit.put("title", "Colourist");

        List<Http.Exchange> answers = behind(
                holdingTheMember(nimali),
                saving(nimali, at, week(workday("monday", "09:00", "18:00"))),
                () -> api.call(alpha, "PUT", TeamFixture.MEMBERS + "/" + nimali, edit, salonOwner));

        assertThat(statuses(answers)).containsExactly(200, 200);
        assertThat(read(nimali).get("title")).isEqualTo("Colourist");
        assertThat(monday(nimali)).containsEntry("starts", "09:00");
    }

    @Test
    void aSaveWaitingBehindARemovalFindsThePersonGone() throws Exception {
        long at = version(nimali);

        List<Http.Exchange> answers = behind(
                connection -> {
                    try (PreparedStatement remove = connection.prepareStatement(
                            "UPDATE staff_team_member SET removed_at = now(), shown_online = false, version = version + 1"
                                    + " WHERE tenant_id = ? AND id = ?::uuid")) {
                        remove.setObject(1, alpha.id());
                        remove.setString(2, nimali.toString());
                        remove.executeUpdate();
                    }
                },
                saving(nimali, at, week(workday("monday", "09:00", "18:00"))),
                adding(nimali, notWorking(today.plusDays(1), null)));

        assertThat(statuses(answers)).containsExactly(404, 404);
    }

    // --- AC-7 -----------------------------------------------------------------------------------------------------

    @Test
    void ofTwoChangesOnOneDateExactlyOneIsKept() throws Exception {
        List<Http.Exchange> answers = behind(
                holdingTheMember(nimali),
                adding(nimali, notWorking(today.plusDays(1), "First")),
                adding(nimali, notWorking(today.plusDays(1), "Second")));

        assertThat(statuses(answers)).containsExactly(201, 409);
        assertThat(codeOfThe409(answers)).isEqualTo("conflict");
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM staff_member_date_change WHERE tenant_id = ? AND member_id = ?::uuid",
                        Long.class,
                        alpha.id(),
                        nimali.toString()))
                .isEqualTo(1);
    }

    /**
     * The key stands behind the lock: a row this test has inserted without taking the member's lock, not yet committed, is
     * invisible to the request's check, so the request reaches the key, waits there, and answers {@code conflict}.
     */
    @Test
    void theUniqueKeyAloneRefusesASecondChangeOnADateAsAConflict() throws Exception {
        List<Http.Exchange> answers = behind(
                connection -> {
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO staff_member_date_change (tenant_id, id, branch_id, member_id, change_date,"
                                    + " created_by, created_at, updated_at)"
                                    + " VALUES (?, ?, ?, ?::uuid, ?, ?, now(), now())")) {
                        insert.setObject(1, alpha.id());
                        insert.setObject(2, UUID.randomUUID());
                        insert.setObject(3, salons.defaultBranch(alpha));
                        insert.setString(4, nimali.toString());
                        insert.setDate(5, java.sql.Date.valueOf(today.plusDays(1)));
                        insert.setObject(6, UUID.randomUUID());
                        insert.executeUpdate();
                    }
                },
                adding(nimali, notWorking(today.plusDays(1), null)));

        assertThat(statuses(answers)).containsExactly(409);
        assertThat(codeOfThe409(answers)).isEqualTo("conflict");
    }

    @Test
    void ofTwoChangesForTheLastPlaceExactlyOneIsKept() throws Exception {
        for (int i = 0; i < 99; i++) {
            owner.update(
                    "INSERT INTO staff_member_date_change (tenant_id, id, branch_id, member_id, change_date, created_by,"
                            + " created_at, updated_at) VALUES (?, ?, ?, ?::uuid, ?, ?, now(), now())",
                    alpha.id(),
                    UUID.randomUUID(),
                    salons.defaultBranch(alpha),
                    nimali.toString(),
                    java.sql.Date.valueOf(today.plusDays(i)),
                    UUID.randomUUID());
        }

        List<Http.Exchange> answers = behind(
                holdingTheMember(nimali),
                adding(nimali, notWorking(today.plusDays(200), null)),
                adding(nimali, notWorking(today.plusDays(201), null)));

        assertThat(statuses(answers)).containsExactly(201, 409);
        assertThat(codeOfThe409(answers)).isEqualTo("limit_reached");
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM staff_member_date_change WHERE tenant_id = ? AND member_id = ?::uuid",
                        Long.class,
                        alpha.id(),
                        nimali.toString()))
                .isEqualTo(100);
    }

    @Test
    void ofTwoRemovalsOfOneChangeExactlyOneSucceeds() throws Exception {
        Http.Exchange added =
                api.call(alpha, "POST", dateChanges(nimali), notWorking(today.plusDays(1), null), salonOwner);
        status(added, 201);
        String path =
                dateChanges(nimali) + "/" + map(api.body(added).get("change")).get("id");

        List<Http.Exchange> answers = behind(
                holdingTheMember(nimali),
                () -> api.call(alpha, "DELETE", path, null, salonOwner),
                () -> api.call(alpha, "DELETE", path, null, salonOwner));

        assertThat(statuses(answers)).containsExactly(204, 404);
    }

    @Test
    void theDatabaseRefusesTimesOffTheQuarterHourAndAFinishNotAfterTheStart() {
        UUID id = UUID.randomUUID();
        for (String times : List.of(
                "'09:10', '18:00'", "'09:00', '18:05'", "'18:00', '09:00'", "'09:00', '09:00'", "'09:00', NULL")) {
            assertThatThrownBy(() -> owner.update(
                            "INSERT INTO staff_member_weekday (tenant_id, id, branch_id, member_id, weekday, starts,"
                                    + " finishes, created_at, updated_at) VALUES (?, ?, ?, ?::uuid, 1, "
                                    + times
                                    + ", now(), now())",
                            alpha.id(),
                            id,
                            salons.defaultBranch(alpha),
                            UUID.randomUUID().toString()))
                    .as(times)
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }
}
