package com.salonplatform.staff;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.identity.domain.StaffRole;
import com.salonplatform.testing.Http;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Who may do what (F06-S01 AC-13) and what anyone may read (AC-10, AC-14). */
class TeamAccessAndPublicIntegrationTest extends TwoSalonTeam {

    private List<Map<String, Object>> publicTeam(com.salonplatform.testing.TestSalons.TestSalon salon) {
        Http.Exchange read = api.call(salon, "GET", TeamFixture.PUBLIC, null, null);
        status(read, 200);
        return maps(api.body(read).get("items"));
    }

    private Map<String, Object> performing(String name, UUID categoryId) {
        Map<String, Object> person = TeamFixture.person(name);
        person.put("services", TeamFixture.services(List.of(categoryId), List.of()));
        Http.Exchange added = api.call(alpha, "POST", TeamFixture.MEMBERS, person, salonOwner);
        status(added, 201);
        return api.body(added);
    }

    // --- AC-13 ----------------------------------------------------------------------------------------------------

    @Test
    void anAccountantGetsPermissionDeniedFromEveryTeamEndpoint() {
        String accountant = settings.signedIn(alpha, StaffRole.ACCOUNTANT).token();
        Map<String, Object> member = added("Unreachable");
        String path = TeamFixture.MEMBERS + "/" + member.get("id");

        refused(api.call(alpha, "GET", TeamFixture.MEMBERS, null, accountant), 403, "permission_denied");
        refused(api.call(alpha, "GET", path, null, accountant), 403, "permission_denied");
        refused(api.call(alpha, "GET", TeamFixture.ACCOUNTS, null, accountant), 403, "permission_denied");
        refused(
                api.call(alpha, "POST", TeamFixture.MEMBERS, TeamFixture.person("No"), accountant),
                403,
                "permission_denied");
        refused(api.call(alpha, "PUT", path, TeamFixture.saveOf(member), accountant), 403, "permission_denied");
        refused(api.call(alpha, "DELETE", path + "?version=0", null, accountant), 403, "permission_denied");
        refused(api.call(alpha, "DELETE", path + "/photo", null, accountant), 403, "permission_denied");
        assertThat(read(member.get("id"))).containsEntry("name", "Unreachable").containsEntry("version", 0);
    }

    @Test
    void aReceptionistAndAProfessionalReadTheTeamWithoutSignInFieldsAndChangeNothing() {
        String accountId = account("MANAGER");
        Http.Exchange linked =
                api.call(alpha, "POST", TeamFixture.MEMBERS, TeamFixture.linked("Linked One", accountId), salonOwner);
        status(linked, 201);
        Map<String, Object> member = api.body(linked);
        String path = TeamFixture.MEMBERS + "/" + member.get("id");

        for (StaffRole role : List.of(StaffRole.RECEPTIONIST, StaffRole.PROFESSIONAL)) {
            String token = settings.signedIn(alpha, role).token();

            Http.Exchange list = api.call(alpha, "GET", TeamFixture.MEMBERS, null, token);
            status(list, 200);
            assertThat(maps(api.body(list).get("items")).getFirst())
                    .containsEntry("name", "Linked One")
                    .doesNotContainKeys("signInAccountId", "signInLabel");
            assertThat(api.body(api.call(alpha, "GET", path, null, token)))
                    .doesNotContainKeys("signInAccountId", "signInLabel");

            refused(api.call(alpha, "GET", TeamFixture.ACCOUNTS, null, token), 403, "permission_denied");
            refused(
                    api.call(alpha, "POST", TeamFixture.MEMBERS, TeamFixture.person("No"), token),
                    403,
                    "permission_denied");
            refused(api.call(alpha, "PUT", path, TeamFixture.saveOf(member), token), 403, "permission_denied");
            refused(api.call(alpha, "DELETE", path + "?version=0", null, token), 403, "permission_denied");
            refused(api.call(alpha, "DELETE", path + "/photo", null, token), 403, "permission_denied");
        }
    }

    @Test
    void aManagerManagesTheTeamAndSeesTheAccountsAndWhoIsLinked() {
        String manager = settings.signedIn(alpha, StaffRole.MANAGER).token();
        String accountId = account("RECEPTIONIST");

        Http.Exchange added =
                api.call(alpha, "POST", TeamFixture.MEMBERS, TeamFixture.linked("Linked Two", accountId), manager);
        status(added, 201);

        Http.Exchange accounts = api.call(alpha, "GET", TeamFixture.ACCOUNTS, null, manager);
        status(accounts, 200);
        Map<String, Object> linked = maps(api.body(accounts).get("items")).stream()
                .filter(account -> accountId.equals(account.get("id")))
                .findFirst()
                .orElseThrow();
        assertThat(linked).containsEntry("memberId", api.body(added).get("id")).containsEntry("role", "RECEPTIONIST");
        assertThat(maps(api.body(accounts).get("items")).stream()
                        .filter(account -> !accountId.equals(account.get("id"))))
                .allSatisfy(account -> assertThat(account.get("memberId")).isNull());
    }

    @Test
    void aSupportSessionReadsTheTeamAndIsRefusedEveryChange() {
        Map<String, Object> member = added("Seen By Support");
        String path = TeamFixture.MEMBERS + "/" + member.get("id");
        String support = settings.supportSession(alpha);

        Http.Exchange list = api.call(alpha, "GET", TeamFixture.MEMBERS, null, support);
        status(list, 200);
        // Support holds `staff.list` in its read set, so it sees that this person has no sign-in.
        assertThat(maps(api.body(list).get("items")).getFirst()).containsEntry("signInAccountId", null);

        refused(
                api.call(alpha, "POST", TeamFixture.MEMBERS, TeamFixture.person("No"), support),
                403,
                "support_read_only");
        refused(api.call(alpha, "PUT", path, TeamFixture.saveOf(member), support), 403, "support_read_only");
        refused(api.call(alpha, "DELETE", path + "?version=0", null, support), 403, "support_read_only");
        refused(api.call(alpha, "DELETE", path + "/photo", null, support), 403, "support_read_only");
    }

    @Test
    void noSessionIsRefusedAndASuspendedOrReadOnlySalonAnswersAsTheCatalogueDoes() {
        Map<String, Object> member = added("Salon States");
        refused(api.call(alpha, "GET", TeamFixture.MEMBERS, null, null), 401, "invalid_session");

        owner.update("UPDATE tenant SET access_state = 'read_only' WHERE id = ?", alpha.id());
        status(api.call(alpha, "GET", TeamFixture.MEMBERS, null, salonOwner), 200);
        refused(
                api.call(alpha, "POST", TeamFixture.MEMBERS, TeamFixture.person("No"), salonOwner),
                403,
                "salon_read_only");
        owner.update("UPDATE tenant SET access_state = 'full' WHERE id = ?", alpha.id());

        salons.suspend(alpha);
        refused(api.call(alpha, "GET", TeamFixture.MEMBERS, null, salonOwner), 403, "salon_suspended");
        refused(api.call(alpha, "GET", TeamFixture.PUBLIC, null, null), 403, "salon_suspended");
        salons.activate(alpha);
        assertThat(read(member.get("id"))).containsEntry("name", "Salon States");
    }

    // --- AC-10 ----------------------------------------------------------------------------------------------------

    @Test
    void thePublicTeamListsThoseShownWhoPerformABookableServiceInNameOrder() {
        UUID colour = category(alpha, salonOwner, "Colour");
        UUID nails = category(alpha, salonOwner, "Nails");
        UUID bookable = UUID.fromString(
                (String) service(alpha, salonOwner, "Bookable", colour, true).get("id"));
        service(alpha, salonOwner, "Not Bookable", colour, false);
        service(alpha, salonOwner, "Only Hidden", nails, false);
        performing("Zara Shown", colour);
        performing("Amal Shown", colour);
        performing("No Online Service", nails);
        added("Performs Nothing");
        Map<String, Object> hidden = performing("Hidden Person", colour);
        Map<String, Object> hide = TeamFixture.saveOf(hidden);
        hide.put("shownOnline", false);
        status(api.call(alpha, "PUT", TeamFixture.MEMBERS + "/" + hidden.get("id"), hide, salonOwner), 200);
        Map<String, Object> gone = performing("Removed Person", colour);
        status(
                api.call(alpha, "DELETE", TeamFixture.MEMBERS + "/" + gone.get("id") + "?version=0", null, salonOwner),
                204);

        List<Map<String, Object>> team = publicTeam(alpha);

        assertThat(team).extracting(person -> person.get("name")).containsExactly("Amal Shown", "Zara Shown");
        assertThat(team.getFirst().get("serviceIds")).isEqualTo(List.of(bookable.toString()));
        assertThat(team.getFirst()).containsEntry("photoUrl", null);
    }

    @Test
    void thePublicTeamNeverCarriesAnAccountAVersionOrAnotherSalonsPeople() {
        UUID colour = category(alpha, salonOwner, "Colour");
        service(alpha, salonOwner, "Bookable", colour, true);
        String accountId = account("RECEPTIONIST");
        Map<String, Object> person = TeamFixture.linked("Linked Person", accountId);
        person.put("services", TeamFixture.services(List.of(colour), List.of()));
        status(api.call(alpha, "POST", TeamFixture.MEMBERS, person, salonOwner), 201);

        Http.Exchange raw = api.call(alpha, "GET", TeamFixture.PUBLIC, null, null);

        status(raw, 200);
        assertThat(raw.body()).doesNotContain(accountId).doesNotContain("example.test");
        assertThat(maps(api.body(raw).get("items")).getFirst())
                .containsOnlyKeys("id", "name", "title", "highlights", "bio", "photoUrl", "serviceIds");
        assertThat(publicTeam(beta)).isEmpty();
    }
}
