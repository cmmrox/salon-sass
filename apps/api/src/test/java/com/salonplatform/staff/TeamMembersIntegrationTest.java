package com.salonplatform.staff;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.identity.domain.StaffRole;
import com.salonplatform.testing.Http;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Adding, editing and removing a person, what they perform and their link (F06-S01 AC-1, 2, 3, 5, 7, 14, 15). */
class TeamMembersIntegrationTest extends TwoSalonTeam {

    private Http.Exchange post(Map<String, Object> body) {
        return api.call(alpha, "POST", TeamFixture.MEMBERS, body, salonOwner);
    }

    private Http.Exchange put(Object id, Map<String, Object> body) {
        return api.call(alpha, "PUT", TeamFixture.MEMBERS + "/" + id, body, salonOwner);
    }

    private static String ofLength(int length) {
        return "x".repeat(length);
    }

    private long auditEntries(Object memberId, String action) {
        return owner.queryForObject(
                "SELECT count(*) FROM audit_entry WHERE tenant_id = ? AND subject_id = ? AND action = ?",
                Long.class,
                alpha.id(),
                memberId.toString(),
                action);
    }

    // --- AC-1 -----------------------------------------------------------------------------------------------------

    @Test
    void aSalonStartsWithNoTeamMembers() {
        Http.Exchange list = api.call(alpha, "GET", TeamFixture.MEMBERS, null, salonOwner);

        status(list, 200);
        assertThat(maps(api.body(list).get("items"))).isEmpty();
    }

    @Test
    void addingAPersonTrimsTheNameKeepsEveryFieldAndIsShownOnlineByDefault() {
        Map<String, Object> body = TeamFixture.person("  Nimali Perera  ");
        body.put("title", " Senior stylist ");
        body.put("highlights", "Colour, bridal");
        body.put("bio", "Ten years of colour work.");

        Http.Exchange added = post(body);

        status(added, 201);
        assertThat(api.body(added))
                .containsEntry("name", "Nimali Perera")
                .containsEntry("title", "Senior stylist")
                .containsEntry("highlights", "Colour, bridal")
                .containsEntry("bio", "Ten years of colour work.")
                .containsEntry("shownOnline", true)
                .containsEntry("photoId", null)
                .containsEntry("photoUrl", null)
                .containsEntry("signInAccountId", null)
                .containsEntry("signInLabel", null)
                .containsEntry("version", 0);
        assertThat(map(api.body(added).get("services")))
                .containsEntry("categoryIds", List.of())
                .containsEntry("serviceIds", List.of());
    }

    @Test
    void twoPeopleMayShareANameAndTheListIsInNameOrder() {
        added("Zara Silva");
        added("Amal Fernando");
        added("Amal Fernando");

        Http.Exchange list = api.call(alpha, "GET", TeamFixture.MEMBERS, null, salonOwner);

        assertThat(maps(api.body(list).get("items")))
                .extracting(member -> member.get("name"))
                .containsExactly("Amal Fernando", "Amal Fernando", "Zara Silva");
    }

    @Test
    void everyFieldHasItsBoundaries() {
        fieldErrors(post(TeamFixture.person(ofLength(1))), "name");
        status(post(TeamFixture.person(ofLength(2))), 201);
        status(post(TeamFixture.person(ofLength(40))), 201);
        fieldErrors(post(TeamFixture.person(ofLength(41))), "name");
        fieldErrors(post(TeamFixture.person("   ")), "name");
        fieldErrors(post(new LinkedHashMap<>()), "name");

        for (Map.Entry<String, Integer> field :
                Map.of("title", 40, "highlights", 60, "bio", 140).entrySet()) {
            Map<String, Object> atLimit = TeamFixture.person("At Limit");
            atLimit.put(field.getKey(), ofLength(field.getValue()));
            status(post(atLimit), 201);
            Map<String, Object> over = TeamFixture.person("Over Limit");
            over.put(field.getKey(), ofLength(field.getValue() + 1));
            fieldErrors(post(over), field.getKey());
        }
    }

    @Test
    void anUnknownFieldOrAWrongTypeIsAFieldErrorAndNothingIsSaved() {
        Map<String, Object> unknown = TeamFixture.person("Unknown Field");
        unknown.put("price", 100);
        fieldErrors(post(unknown), "price");
        Map<String, Object> wrongType = TeamFixture.person("Wrong Type");
        wrongType.put("shownOnline", "yes");
        fieldErrors(post(wrongType), "shownOnline");

        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM staff_team_member WHERE tenant_id = ?", Long.class, alpha.id()))
                .isZero();
    }

    @Test
    void theTwoHundredAndFirstMemberIsRefused() {
        UUID branch = salons.defaultBranch(alpha);
        for (int i = 0; i < 200; i++) {
            owner.update(
                    "INSERT INTO staff_team_member (tenant_id, id, branch_id, name, shown_online, version, created_at,"
                            + " updated_at) VALUES (?, ?, ?, ?, true, 0, now(), now())",
                    alpha.id(),
                    UUID.randomUUID(),
                    branch,
                    "Person " + i);
        }
        assertThat(maps(api.body(api.call(alpha, "GET", TeamFixture.MEMBERS, null, salonOwner))
                        .get("items")))
                .hasSize(200);

        refused(post(TeamFixture.person("One Too Many")), 409, "limit_reached");

        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM staff_team_member WHERE tenant_id = ?", Long.class, alpha.id()))
                .isEqualTo(200);
    }

    // --- AC-2 -----------------------------------------------------------------------------------------------------

    @Test
    void aSaveChangesThePersonAndRaisesTheVersion() {
        Map<String, Object> member = added("Before Name");
        Map<String, Object> save = TeamFixture.saveOf(member);
        save.put("name", "After Name");
        save.put("shownOnline", false);

        Http.Exchange saved = put(member.get("id"), save);

        status(saved, 200);
        assertThat(api.body(saved))
                .containsEntry("name", "After Name")
                .containsEntry("shownOnline", false)
                .containsEntry("version", 1);
    }

    @Test
    void aSaveThatChangesNothingIsInvalidState() {
        Map<String, Object> member = added("Same Name");

        refused(put(member.get("id"), TeamFixture.saveOf(member)), 409, "invalid_state");

        assertThat(read(member.get("id"))).containsEntry("version", 0);
    }

    @Test
    void aStaleVersionIsAConflictAndChangesNothing() {
        Map<String, Object> member = added("First Name");
        Map<String, Object> first = TeamFixture.saveOf(member);
        first.put("name", "Second Name");
        status(put(member.get("id"), first), 200);
        Map<String, Object> stale = TeamFixture.saveOf(member);
        stale.put("name", "Stale Name");

        refused(put(member.get("id"), stale), 409, "conflict");

        assertThat(read(member.get("id"))).containsEntry("name", "Second Name");
    }

    @Test
    void ofTwoSavesOfTheSameVersionAtOnceExactlyOneSucceeds() throws Exception {
        for (int round = 0; round < 5; round++) {
            Map<String, Object> member = added("Racing " + round);
            Map<String, Object> left = TeamFixture.saveOf(member);
            left.put("name", "Left " + round);
            Map<String, Object> right = TeamFixture.saveOf(member);
            right.put("name", "Right " + round);

            List<Integer> statuses = together(
                    () -> settings.call(alpha, "PUT", TeamFixture.MEMBERS + "/" + member.get("id"), left, salonOwner)
                            .status(),
                    () -> settings.call(alpha, "PUT", TeamFixture.MEMBERS + "/" + member.get("id"), right, salonOwner)
                            .status());

            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
            assertThat(read(member.get("id"))).containsEntry("version", 1);
        }
    }

    @Test
    void aSaveOfSomeoneUnknownOrRemovedIsNotFound() {
        Map<String, Object> member = added("Gone Soon");
        refused(put(UUID.randomUUID(), TeamFixture.saveOf(member)), 404, "not_found");
        refused(api.call(alpha, "GET", TeamFixture.MEMBERS + "/not-a-uuid", null, salonOwner), 404, "not_found");
        status(
                api.call(
                        alpha, "DELETE", TeamFixture.MEMBERS + "/" + member.get("id") + "?version=0", null, salonOwner),
                204);

        refused(put(member.get("id"), TeamFixture.saveOf(member)), 404, "not_found");
    }

    // --- AC-3 -----------------------------------------------------------------------------------------------------

    @Test
    void aWholeCategoryCoversServicesAddedLaterAndSingleServicesAreKeptApart() {
        UUID colour = category(alpha, salonOwner, "Colour");
        UUID nails = category(alpha, salonOwner, "Nails");
        UUID first = UUID.fromString((String)
                service(alpha, salonOwner, "Root touch-up", nails, true).get("id"));
        service(alpha, salonOwner, "Gel polish", nails, true);
        Map<String, Object> body = TeamFixture.person("Performer");
        body.put("services", TeamFixture.services(List.of(colour), List.of(first)));

        Http.Exchange added = post(body);

        status(added, 201);
        Map<String, Object> services = map(api.body(added).get("services"));
        assertThat(services.get("categoryIds")).isEqualTo(List.of(colour.toString()));
        assertThat(services.get("serviceIds")).isEqualTo(List.of(first.toString()));
    }

    @Test
    void anIdOfNeitherTheSalonsCategoriesNorItsServicesIsRefusedOnServices() {
        String betaOwner = settings.signedIn(beta, StaffRole.OWNER).token();
        UUID betasCategory = category(beta, betaOwner, "Colour");
        UUID betasService = UUID.fromString((String)
                service(beta, betaOwner, "Beta cut", betasCategory, true).get("id"));

        for (Map<String, Object> services : List.of(
                TeamFixture.services(List.of(betasCategory), List.of()),
                TeamFixture.services(List.of(), List.of(betasService)),
                TeamFixture.services(List.of(UUID.randomUUID()), List.of()),
                TeamFixture.services(List.of(), List.of(UUID.randomUUID())))) {
            Map<String, Object> body = TeamFixture.person("Elsewhere");
            body.put("services", services);
            fieldErrors(post(body), "services");
        }
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM staff_team_member WHERE tenant_id = ?", Long.class, alpha.id()))
                .isZero();
    }

    @Test
    void aSingleServiceInsideACategoryAlsoSentWholeIsRefusedOnServices() {
        UUID colour = category(alpha, salonOwner, "Colour");
        UUID inside = UUID.fromString(
                (String) service(alpha, salonOwner, "Highlights", colour, true).get("id"));
        Map<String, Object> body = TeamFixture.person("Overlap");
        body.put("services", TeamFixture.services(List.of(colour), List.of(inside)));

        fieldErrors(post(body), "services");
    }

    @Test
    void aDeletedServiceDisappearsFromThePersonAndAStaleIdIsNotShown() {
        UUID colour = category(alpha, salonOwner, "Colour");
        Map<String, Object> service = service(alpha, salonOwner, "Short-lived", colour, true);
        Map<String, Object> body = TeamFixture.person("Stale");
        body.put("services", TeamFixture.services(List.of(), List.of(UUID.fromString((String) service.get("id")))));
        status(post(body), 201);
        Object id = maps(api.body(api.call(alpha, "GET", TeamFixture.MEMBERS, null, salonOwner))
                        .get("items"))
                .getFirst()
                .get("id");

        deleteService(alpha, salonOwner, service);

        assertThat(map(read(id).get("services")).get("serviceIds")).isEqualTo(List.of());
    }

    @Test
    void aMovedServiceFollowsTheCategoryRulesAtOnceAndWhatIsReadCanBeSentBack() {
        UUID colour = category(alpha, salonOwner, "Colour");
        UUID nails = category(alpha, salonOwner, "Nails");
        Map<String, Object> service = service(alpha, salonOwner, "Moving Service", nails, true);
        UUID movingId = UUID.fromString((String) service.get("id"));
        // A single service, in Nails; the person also performs Colour whole.
        Map<String, Object> body = TeamFixture.person("Follower");
        body.put("services", TeamFixture.services(List.of(colour), List.of(movingId)));
        Http.Exchange added = post(body);
        status(added, 201);
        Object id = api.body(added).get("id");

        moveService(alpha, salonOwner, service, colour);

        Map<String, Object> afterMove = read(id);
        assertThat(map(afterMove.get("services")).get("serviceIds")).isEqualTo(List.of());
        // What was read is acceptable as it stands, though it was stored overlapping.
        Map<String, Object> save = TeamFixture.saveOf(afterMove);
        save.put("name", "Follower Renamed");
        status(put(id, save), 200);
    }

    @Test
    void aSaveReplacesWhatIsPerformed() {
        UUID colour = category(alpha, salonOwner, "Colour");
        UUID nails = category(alpha, salonOwner, "Nails");
        Map<String, Object> body = TeamFixture.person("Changing Hands");
        body.put("services", TeamFixture.services(List.of(colour), List.of()));
        Http.Exchange added = post(body);
        status(added, 201);
        Map<String, Object> save = TeamFixture.saveOf(api.body(added));
        save.put("services", TeamFixture.services(List.of(nails), List.of()));

        Http.Exchange saved = put(api.body(added).get("id"), save);

        status(saved, 200);
        assertThat(map(api.body(saved).get("services")).get("categoryIds")).isEqualTo(List.of(nails.toString()));
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM staff_member_category WHERE tenant_id = ?", Long.class, alpha.id()))
                .isEqualTo(1);
    }

    // --- AC-5 -----------------------------------------------------------------------------------------------------

    @Test
    void aLinkedMemberNamesTheAccountAndItsLabel() {
        String accountId = account("RECEPTIONIST");

        Http.Exchange added = post(TeamFixture.linked("Linked Person", accountId));

        status(added, 201);
        assertThat(api.body(added)).containsEntry("signInAccountId", accountId);
        assertThat((String) api.body(added).get("signInLabel")).endsWith("@example.test");
    }

    @Test
    void anAccountOfAnotherSalonARemovedOneOrOneAlreadyLinkedIsRefusedOnTheLink() {
        Object betas = settings.signedIn(beta, StaffRole.MANAGER).account().getId();
        fieldErrors(post(TeamFixture.linked("Other Salon", betas)), "signInAccountId");
        fieldErrors(post(TeamFixture.linked("Unknown", UUID.randomUUID())), "signInAccountId");

        String removed = account("RECEPTIONIST");
        status(api.call(alpha, "DELETE", TeamFixture.IDENTITY_STAFF + "/" + removed, null, salonOwner), 204);
        fieldErrors(post(TeamFixture.linked("Removed Account", removed)), "signInAccountId");

        String taken = account("RECEPTIONIST");
        status(post(TeamFixture.linked("Holder", taken)), 201);
        fieldErrors(post(TeamFixture.linked("Second Holder", taken)), "signInAccountId");
    }

    @Test
    void twoSavesLinkingOneAccountAtOnceLeaveItLinkedOnce() throws Exception {
        onPlanOf(alpha, 50);
        for (int round = 0; round < 5; round++) {
            String accountId = account("RECEPTIONIST");

            List<Integer> statuses = together(
                    () -> settings.call(
                                    alpha,
                                    "POST",
                                    TeamFixture.MEMBERS,
                                    TeamFixture.linked("Left", accountId),
                                    salonOwner)
                            .status(),
                    () -> settings.call(
                                    alpha,
                                    "POST",
                                    TeamFixture.MEMBERS,
                                    TeamFixture.linked("Right", accountId),
                                    salonOwner)
                            .status());

            assertThat(statuses).containsExactlyInAnyOrder(201, 400);
            assertThat(owner.queryForObject(
                            "SELECT count(*) FROM staff_team_member WHERE tenant_id = ? AND sign_in_account_id = ?::uuid",
                            Long.class,
                            alpha.id(),
                            accountId))
                    .isEqualTo(1);
        }
    }

    @Test
    void theDatabaseItselfRefusesADoubleLink() {
        String accountId = account("RECEPTIONIST");
        status(post(TeamFixture.linked("Holder", accountId)), 201);

        org.assertj.core.api.Assertions.assertThatExceptionOfType(org.springframework.dao.DuplicateKeyException.class)
                .isThrownBy(() -> owner.update(
                        "INSERT INTO staff_team_member (tenant_id, id, branch_id, name, shown_online,"
                                + " sign_in_account_id, version, created_at, updated_at)"
                                + " VALUES (?, ?, ?, 'Second', true, ?::uuid, 0, now(), now())",
                        alpha.id(),
                        UUID.randomUUID(),
                        salons.defaultBranch(alpha),
                        accountId));
    }

    @Test
    void removingAMemberLeavesTheirAccountActiveAndFreeToLink() {
        String accountId = account("RECEPTIONIST");
        Http.Exchange added = post(TeamFixture.linked("Leaving", accountId));
        status(added, 201);

        status(
                api.call(
                        alpha,
                        "DELETE",
                        TeamFixture.MEMBERS + "/" + api.body(added).get("id") + "?version=0",
                        null,
                        salonOwner),
                204);

        assertThat(owner.queryForObject(
                        "SELECT active FROM identity_staff WHERE id = ?::uuid", Boolean.class, accountId))
                .isTrue();
        status(post(TeamFixture.linked("Next Holder", accountId)), 201);
    }

    @Test
    void removingAnAccountLeavesTheMemberWithNoSignInAndAuditsIt() {
        String accountId = account("RECEPTIONIST");
        Http.Exchange added = post(TeamFixture.linked("Staying", accountId));
        status(added, 201);
        Object id = api.body(added).get("id");

        status(api.call(alpha, "DELETE", TeamFixture.IDENTITY_STAFF + "/" + accountId, null, salonOwner), 204);

        assertThat(read(id)).containsEntry("signInAccountId", null).containsEntry("signInLabel", null);
        assertThat(auditEntries(id, "staff.team_member_link_cleared")).isEqualTo(1);
    }

    // --- AC-7 and AC-15 -------------------------------------------------------------------------------------------

    @Test
    void aRemovedMemberLeavesTheListAndKeepsTheirNameInTheRow() {
        Map<String, Object> member = added("Tombstone");

        status(
                api.call(
                        alpha,
                        "DELETE",
                        TeamFixture.MEMBERS + "/" + member.get("id") + "?version=" + member.get("version"),
                        null,
                        salonOwner),
                204);

        assertThat(maps(api.body(api.call(alpha, "GET", TeamFixture.MEMBERS, null, salonOwner))
                        .get("items")))
                .isEmpty();
        refused(
                api.call(alpha, "GET", TeamFixture.MEMBERS + "/" + member.get("id"), null, salonOwner),
                404,
                "not_found");
        assertThat(owner.queryForObject(
                        "SELECT name FROM staff_team_member WHERE tenant_id = ? AND id = ?::uuid",
                        String.class,
                        alpha.id(),
                        member.get("id")))
                .isEqualTo("Tombstone");
    }

    @Test
    void removingWithAStaleOrMissingVersionIsRefused() {
        Map<String, Object> member = added("Careful Removal");
        String path = TeamFixture.MEMBERS + "/" + member.get("id");

        refused(api.call(alpha, "DELETE", path + "?version=9", null, salonOwner), 409, "conflict");
        fieldErrors(api.call(alpha, "DELETE", path, null, salonOwner), "version");
        fieldErrors(api.call(alpha, "DELETE", path + "?version=-1", null, salonOwner), "version");

        assertThat(read(member.get("id"))).containsEntry("name", "Careful Removal");
    }

    @Test
    void everyChangeIsAuditedWithActorAndValuesBeforeAndAfter() {
        Map<String, Object> member = added("Audited Name");
        Map<String, Object> save = TeamFixture.saveOf(member);
        save.put("name", "Audited Again");
        status(put(member.get("id"), save), 200);
        status(
                api.call(
                        alpha, "DELETE", TeamFixture.MEMBERS + "/" + member.get("id") + "?version=1", null, salonOwner),
                204);

        assertThat(auditEntries(member.get("id"), "staff.team_member_added")).isEqualTo(1);
        assertThat(auditEntries(member.get("id"), "staff.team_member_changed")).isEqualTo(1);
        assertThat(auditEntries(member.get("id"), "staff.team_member_removed")).isEqualTo(1);
        Map<String, Object> changed = owner.queryForMap(
                "SELECT actor_kind, before_values::text AS before_values, after_values::text AS after_values"
                        + " FROM audit_entry WHERE tenant_id = ? AND subject_id = ? AND action = ?",
                alpha.id(),
                member.get("id").toString(),
                "staff.team_member_changed");
        assertThat(changed.get("actor_kind")).isEqualTo("staff");
        assertThat((String) changed.get("before_values")).contains("Audited Name");
        assertThat((String) changed.get("after_values")).contains("Audited Again");
    }

    // --- AC-14 ----------------------------------------------------------------------------------------------------

    @Test
    void anotherSalonsMemberIsNotFoundForEveryRequestAndStaysAsItWas() {
        String betaOwner = settings.signedIn(beta, StaffRole.OWNER).token();
        Http.Exchange created =
                api.call(beta, "POST", TeamFixture.MEMBERS, TeamFixture.person("Beta Person"), betaOwner);
        status(created, 201);
        Map<String, Object> betas = api.body(created);
        String path = TeamFixture.MEMBERS + "/" + betas.get("id");

        refused(api.call(alpha, "GET", path, null, salonOwner), 404, "not_found");
        refused(api.call(alpha, "PUT", path, TeamFixture.saveOf(betas), salonOwner), 404, "not_found");
        refused(api.call(alpha, "DELETE", path + "?version=0", null, salonOwner), 404, "not_found");
        refused(api.call(alpha, "DELETE", path + "/photo", null, salonOwner), 404, "not_found");

        assertThat(maps(api.body(api.call(alpha, "GET", TeamFixture.MEMBERS, null, salonOwner))
                        .get("items")))
                .isEmpty();
        assertThat(api.body(api.call(beta, "GET", path, null, betaOwner))).containsEntry("name", "Beta Person");
    }

    // --- helpers --------------------------------------------------------------------------------------------------

    private static List<Integer> together(Callable<Integer> first, Callable<Integer> second) throws Exception {
        List<CompletableFuture<Integer>> running = new ArrayList<>();
        for (Callable<Integer> call : List.of(first, second)) {
            running.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return call.call();
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            }));
        }
        List<Integer> statuses = new ArrayList<>();
        for (CompletableFuture<Integer> result : running) {
            statuses.add(result.get(60, TimeUnit.SECONDS));
        }
        return statuses;
    }
}
