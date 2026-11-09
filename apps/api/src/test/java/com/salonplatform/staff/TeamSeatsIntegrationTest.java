package com.salonplatform.staff;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.identity.domain.StaffRole;
import com.salonplatform.testing.Http;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The seat rule across {@code identity} and {@code staff} (F06-S01 AC-6, decisions 099 and 100): seats used are active
 * accounts plus active members with no linked account, as both modules and the Billing page count them. The races go
 * through the real endpoints, so a request that deadlocked would answer 5xx or never answer at all.
 */
class TeamSeatsIntegrationTest extends TwoSalonTeam {

    private static final Duration RACE_LIMIT = Duration.ofSeconds(60);

    private Callable<Integer> addsAMember(String name) {
        return () -> settings.call(alpha, "POST", TeamFixture.MEMBERS, TeamFixture.person(name), salonOwner)
                .status();
    }

    private Callable<Integer> createsAnAccount() {
        String email = TeamFixture.email("racer");
        return () -> settings.call(
                        alpha,
                        "POST",
                        TeamFixture.IDENTITY_STAFF,
                        TeamFixture.account(email, "RECEPTIONIST"),
                        salonOwner)
                .status();
    }

    /** Runs them all at once, released together, and answers their statuses in the order they were given. */
    private static List<Integer> atOnce(List<Callable<Integer>> requests) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(requests.size());
        try {
            List<Future<Integer>> running = new ArrayList<>();
            for (Callable<Integer> request : requests) {
                running.add(pool.submit(() -> {
                    start.await();
                    return request.call();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : running) {
                // A deadlock that never resolves fails here rather than hanging the build.
                statuses.add(result.get(RACE_LIMIT.toSeconds(), TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    private static long winners(List<Integer> statuses, int success) {
        return statuses.stream().filter(status -> status == success).count();
    }

    // --- the last seat under simultaneous requests ----------------------------------------------------------------

    @Test
    void ofAMemberAndAnAccountAskingForTheLastSeatAtOnceExactlyOneSucceedsWhicheverStartsFirst() throws Exception {
        onPlanOf(alpha, 25);
        // Each round: one seat free, one request of each kind for it, given in both orders.
        for (int round = 0; round < 12; round++) {
            List<Callable<Integer>> requests = round % 2 == 0
                    ? List.of(addsAMember("Racer " + round), createsAnAccount())
                    : List.of(createsAnAccount(), addsAMember("Racer " + round));
            long before = seatsInTheDatabase(alpha);
            onPlanOf(alpha, (int) before + 1);

            List<Integer> statuses = atOnce(requests);

            assertThat(statuses).allMatch(status -> status == 201 || status == 409);
            assertThat(winners(statuses, 201))
                    .as("round %d: %s", round, statuses)
                    .isEqualTo(1);
            assertThat(seatsInTheDatabase(alpha)).as("round %d", round).isEqualTo(before + 1);
        }
    }

    @Test
    void ofManyMembersAndAccountsAskingForTheLastSeatAtOnceExactlyOneSucceeds() throws Exception {
        onPlanOf(alpha, 2);
        List<Callable<Integer>> requests = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            requests.add(addsAMember("Racer " + i));
            requests.add(createsAnAccount());
        }

        List<Integer> statuses = atOnce(requests);

        assertThat(statuses).containsOnly(201, 409);
        assertThat(winners(statuses, 201)).isEqualTo(1);
        assertThat(seatsInTheDatabase(alpha)).isEqualTo(2);
        assertThat(usageSeats(alpha, salonOwner)).isEqualTo(2);
    }

    @Test
    void ofTwoUnlinksAskingForTheLastSeatAtOnceExactlyOneSucceeds() throws Exception {
        onPlanOf(alpha, 10);
        Map<String, Object> first = linkedMember("First");
        Map<String, Object> second = linkedMember("Second");
        // Owner and two accounts, each pair counting once: three used, so one seat is left at a limit of four.
        onPlanOf(alpha, 4);
        assertThat(seatsInTheDatabase(alpha)).isEqualTo(3);

        List<Integer> statuses = atOnce(List.of(unlinks(first), unlinks(second)));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(seatsInTheDatabase(alpha)).isEqualTo(4);
    }

    @Test
    void anAccountRemovedWhileItsMemberIsSavedAndMembersAddedNeverDeadlocks() throws Exception {
        onPlanOf(alpha, 50);
        for (int round = 0; round < 8; round++) {
            Map<String, Object> member = linkedMember("Busy " + round);
            String accountId = (String) member.get("signInAccountId");
            Map<String, Object> rename = TeamFixture.saveOf(member);
            rename.put("name", "Renamed " + round);
            String path = TeamFixture.MEMBERS + "/" + member.get("id");

            List<Integer> statuses = atOnce(List.of(
                    () -> settings.call(alpha, "PUT", path, rename, salonOwner).status(),
                    () -> settings.call(alpha, "DELETE", TeamFixture.IDENTITY_STAFF + "/" + accountId, null, salonOwner)
                            .status(),
                    addsAMember("Bystander " + round),
                    createsAnAccount()));

            assertThat(statuses.get(0)).as("the save, round %d", round).isIn(200, 400, 409);
            assertThat(statuses.get(1)).as("the removal, round %d", round).isEqualTo(204);
            assertThat(statuses.get(2)).as("the addition, round %d", round).isEqualTo(201);
            assertThat(statuses.get(3)).as("the creation, round %d", round).isEqualTo(201);
            // However they fell, the account is gone and no member holds it.
            assertThat(owner.queryForObject(
                            "SELECT count(*) FROM staff_team_member WHERE tenant_id = ? AND sign_in_account_id = ?::uuid",
                            Long.class,
                            alpha.id(),
                            accountId))
                    .isZero();
        }
    }

    // --- what needs a seat ----------------------------------------------------------------------------------------

    @Test
    void addingSomeoneWithNoSignInNeedsAFreeSeatAndNothingChangesWithoutOne() {
        onPlanOf(alpha, 2);
        added("Nimali Perera");
        assertThat(seatsInTheDatabase(alpha)).isEqualTo(2);

        Http.Exchange refused = api.call(alpha, "POST", TeamFixture.MEMBERS, TeamFixture.person("Kasun"), salonOwner);

        noFreeSeat(refused);
        assertThat(seatsInTheDatabase(alpha)).isEqualTo(2);
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM staff_team_member WHERE tenant_id = ?", Long.class, alpha.id()))
                .isEqualTo(1);
    }

    @Test
    void creatingAnAccountCountsMembersWithNoLinkedAccount() {
        onPlanOf(alpha, 2);
        added("Nimali Perera");

        // F02's contract gives this endpoint a 201 and a default answer, so its 409 is not held to a declared status.
        Http.Exchange refused = settings.call(
                alpha,
                "POST",
                TeamFixture.IDENTITY_STAFF,
                TeamFixture.account(TeamFixture.email("late"), "MANAGER"),
                salonOwner);

        noFreeSeat(refused);
        assertThat(seatsInTheDatabase(alpha)).isEqualTo(2);
    }

    @Test
    void addingSomeoneLinkedToAFreeAccountNeedsNoSeat() {
        onPlanOf(alpha, 2);
        String accountId = account("RECEPTIONIST");
        assertThat(seatsInTheDatabase(alpha)).isEqualTo(2);

        Http.Exchange added =
                api.call(alpha, "POST", TeamFixture.MEMBERS, TeamFixture.linked("Linked", accountId), salonOwner);

        status(added, 201);
        assertThat(seatsInTheDatabase(alpha)).isEqualTo(2);
    }

    @Test
    void unlinkingAMemberWhoseAccountStaysNeedsAFreeSeat() {
        onPlanOf(alpha, 3);
        Map<String, Object> member = linkedMember("Linked");
        onPlanOf(alpha, 2);

        Http.Exchange refused = api.call(
                alpha, "PUT", TeamFixture.MEMBERS + "/" + member.get("id"), withoutAccount(member), salonOwner);

        noFreeSeat(refused);
        assertThat(api.body(api.call(alpha, "GET", TeamFixture.MEMBERS + "/" + member.get("id"), null, salonOwner))
                        .get("signInAccountId"))
                .isEqualTo(member.get("signInAccountId"));

        onPlanOf(alpha, 3);
        status(
                api.call(
                        alpha, "PUT", TeamFixture.MEMBERS + "/" + member.get("id"), withoutAccount(member), salonOwner),
                200);
        assertThat(seatsInTheDatabase(alpha)).isEqualTo(3);
    }

    @Test
    void linkingAMemberWithNoSignInToAFreeAccountFreesASeat() {
        onPlanOf(alpha, 3);
        Map<String, Object> member = added("Unlinked");
        String accountId = account("RECEPTIONIST");
        assertThat(seatsInTheDatabase(alpha)).isEqualTo(3);

        Map<String, Object> link = TeamFixture.saveOf(member);
        link.put("signInAccountId", accountId);
        status(api.call(alpha, "PUT", TeamFixture.MEMBERS + "/" + member.get("id"), link, salonOwner), 200);

        assertThat(seatsInTheDatabase(alpha)).isEqualTo(2);
        assertThat(usageSeats(alpha, salonOwner)).isEqualTo(2);
    }

    @Test
    void editingAMemberWithoutChangingTheLinkNeedsNoSeat() {
        onPlanOf(alpha, 2);
        Map<String, Object> member = added("Full Plan");
        assertThat(seatsInTheDatabase(alpha)).isEqualTo(2);

        Map<String, Object> rename = TeamFixture.saveOf(member);
        rename.put("name", "Still Full");

        status(api.call(alpha, "PUT", TeamFixture.MEMBERS + "/" + member.get("id"), rename, salonOwner), 200);
    }

    @Test
    void removingAMemberFreesTheirSeatAndAnAccountRemovedKeepsItsMembersSeat() {
        onPlanOf(alpha, 3);
        Map<String, Object> unlinked = added("Leaves");
        Map<String, Object> linked = linkedMember("Stays");
        assertThat(seatsInTheDatabase(alpha)).isEqualTo(3);

        // Removing the account leaves the member, who now takes the seat the account held.
        status(
                api.call(
                        alpha,
                        "DELETE",
                        TeamFixture.IDENTITY_STAFF + "/" + linked.get("signInAccountId"),
                        null,
                        salonOwner),
                204);
        assertThat(seatsInTheDatabase(alpha)).isEqualTo(3);
        assertThat(usageSeats(alpha, salonOwner)).isEqualTo(3);
        assertThat(api.body(api.call(alpha, "GET", TeamFixture.MEMBERS + "/" + linked.get("id"), null, salonOwner))
                        .get("signInAccountId"))
                .isNull();

        status(
                api.call(
                        alpha,
                        "DELETE",
                        TeamFixture.MEMBERS + "/" + unlinked.get("id") + "?version=" + unlinked.get("version"),
                        null,
                        salonOwner),
                204);
        assertThat(seatsInTheDatabase(alpha)).isEqualTo(2);
        assertThat(usageSeats(alpha, salonOwner)).isEqualTo(2);
    }

    @Test
    void aSalonWithoutAPlanIsNotLimited() {
        owner.update("UPDATE tenant SET plan_id = NULL WHERE id = ?", alpha.id());
        for (int i = 0; i < 5; i++) {
            added("Person " + i);
        }
        assertThat(seatsInTheDatabase(alpha)).isEqualTo(6);
    }

    @Test
    void anotherSalonsMembersAreNotCounted() {
        onPlanOf(alpha, 2);
        String betaOwner = settings.signedIn(beta, StaffRole.OWNER).token();
        onPlanOf(beta, 10);
        for (int i = 0; i < 3; i++) {
            status(api.call(beta, "POST", TeamFixture.MEMBERS, TeamFixture.person("Beta " + i), betaOwner), 201);
        }

        added("Alpha's second seat");

        assertThat(usageSeats(alpha, salonOwner)).isEqualTo(2);
        assertThat(usageSeats(beta, betaOwner)).isEqualTo(4);
    }

    // --- helpers --------------------------------------------------------------------------------------------------

    /** A member linked to a new account of their own. */
    private Map<String, Object> linkedMember(String name) {
        Http.Exchange added = api.call(
                alpha, "POST", TeamFixture.MEMBERS, TeamFixture.linked(name, account("RECEPTIONIST")), salonOwner);
        status(added, 201);
        return api.body(added);
    }

    private static Map<String, Object> withoutAccount(Map<String, Object> member) {
        Map<String, Object> save = TeamFixture.saveOf(member);
        save.put("signInAccountId", null);
        return save;
    }

    private Callable<Integer> unlinks(Map<String, Object> member) {
        return () -> settings.call(
                        alpha, "PUT", TeamFixture.MEMBERS + "/" + member.get("id"), withoutAccount(member), salonOwner)
                .status();
    }
}
