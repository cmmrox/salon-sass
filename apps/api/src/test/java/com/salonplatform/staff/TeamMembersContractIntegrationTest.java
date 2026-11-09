package com.salonplatform.staff;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.identity.domain.StaffRole;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.testing.Http;
import com.salonplatform.testing.TestSalons.TestSalon;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** {@link TeamMembers}, the contract F07 and F10 use (F06-S01 AC-4), for the caller's salon only. */
class TeamMembersContractIntegrationTest extends TwoSalonTeam {

    @Autowired
    TeamMembers team;

    private <T> T as(TestSalon salon, Callable<T> work) throws Exception {
        return TenantContext.callAs(salons.asCurrent(salon), work);
    }

    private UUID added(Map<String, Object> body) {
        Http.Exchange created = api.call(alpha, "POST", TeamFixture.MEMBERS, body, salonOwner);
        status(created, 201);
        return UUID.fromString((String) api.body(created).get("id"));
    }

    @Test
    void aMemberIsReturnedByIdARemovedOneIncludedForTheirName() throws Exception {
        UUID id = added(TeamFixture.person("Past Person"));

        assertThat(as(alpha, () -> team.member(id))).contains(new TeamMember(id, "Past Person", false, true));

        status(api.call(alpha, "DELETE", TeamFixture.MEMBERS + "/" + id + "?version=0", null, salonOwner), 204);

        assertThat(as(alpha, () -> team.member(id))).contains(new TeamMember(id, "Past Person", true, false));
        assertThat(as(alpha, () -> team.servicesOf(id))).isEmpty();
    }

    @Test
    void whoPerformsAServiceAndWhatAMemberPerformsAreWorkedOutWhenRead() throws Exception {
        UUID colour = category(alpha, salonOwner, "Colour");
        UUID nails = category(alpha, salonOwner, "Nails");
        UUID first = UUID.fromString(
                (String) service(alpha, salonOwner, "First", colour, true).get("id"));
        Map<String, Object> single = service(alpha, salonOwner, "Single", nails, true);
        UUID singleId = UUID.fromString((String) single.get("id"));
        Map<String, Object> wholeBody = TeamFixture.person("Whole Category");
        wholeBody.put("services", TeamFixture.services(List.of(colour), List.of()));
        UUID whole = added(wholeBody);
        Map<String, Object> singleBody = TeamFixture.person("Single Service");
        singleBody.put("services", TeamFixture.services(List.of(), List.of(singleId)));
        UUID justOne = added(singleBody);

        assertThat(as(alpha, () -> team.performing(first)))
                .extracting(TeamMember::id)
                .containsExactly(whole);
        assertThat(as(alpha, () -> team.performing(singleId)))
                .extracting(TeamMember::id)
                .containsExactly(justOne);
        assertThat(as(alpha, () -> team.servicesOf(whole))).containsExactly(first);

        // A service added to the whole category later is performed at once.
        UUID later = UUID.fromString(
                (String) service(alpha, salonOwner, "Later", colour, true).get("id"));
        assertThat(as(alpha, () -> team.servicesOf(whole))).containsExactlyInAnyOrder(first, later);

        // A single service moved into the whole category is performed by both.
        moveService(alpha, salonOwner, single, colour);
        assertThat(as(alpha, () -> team.performing(singleId)))
                .extracting(TeamMember::id)
                .containsExactlyInAnyOrder(whole, justOne);
    }

    @Test
    void theMemberLinkedToAnAccountIsFoundAndNoneOnceUnlinked() throws Exception {
        UUID account = UUID.fromString(account("RECEPTIONIST"));
        UUID id = added(TeamFixture.linked("Linked Person", account));

        assertThat(as(alpha, () -> team.linkedTo(account))).map(TeamMember::id).contains(id);

        status(api.call(alpha, "DELETE", TeamFixture.IDENTITY_STAFF + "/" + account, null, salonOwner), 204);

        assertThat(as(alpha, () -> team.linkedTo(account))).isEmpty();
        assertThat(as(alpha, () -> team.member(id))).map(TeamMember::removed).contains(false);
    }

    @Test
    void aMemberWithNoAccountIsAnsweredTheSame() throws Exception {
        UUID id = added(TeamFixture.person("No Sign In"));

        assertThat(as(alpha, () -> team.member(id))).map(TeamMember::name).contains("No Sign In");
    }

    @Test
    void anotherSalonsIdsAreNotFoundOrAbsent() throws Exception {
        UUID colour = category(alpha, salonOwner, "Colour");
        UUID service = UUID.fromString(
                (String) service(alpha, salonOwner, "Alpha Only", colour, true).get("id"));
        UUID account = UUID.fromString(account("RECEPTIONIST"));
        Map<String, Object> body = TeamFixture.linked("Alpha Person", account);
        body.put("services", TeamFixture.services(List.of(colour), List.of()));
        UUID alphas = added(body);
        settings.signedIn(beta, StaffRole.OWNER);

        assertThat(as(beta, () -> team.member(alphas))).isEmpty();
        assertThat(as(beta, () -> team.servicesOf(alphas))).isEmpty();
        assertThat(as(beta, () -> team.performing(service))).isEmpty();
        assertThat(as(beta, () -> team.linkedTo(account))).isEmpty();
    }
}
