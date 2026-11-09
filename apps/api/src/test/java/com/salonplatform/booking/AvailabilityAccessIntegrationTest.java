package com.salonplatform.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.identity.domain.StaffRole;
import com.salonplatform.testing.Http;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * F07-S02 AC-9 and AC-10: the availability and open-days reads need {@code booking.manage}; and no answer is ever worked
 * out from another salon's hours, team, leave, bookings, holds or rules, its ids answering as unknown ones.
 */
class AvailabilityAccessIntegrationTest extends BookingTestBase {

    String availability(Salon salon) {
        return BOOKINGS + "/availability?serviceId=" + salon.service() + "&date=" + TOMORROW;
    }

    String openDays() {
        return BOOKINGS + "/open-days?from=2086-06-15&to=2086-06-21";
    }

    List<String> times(Salon salon, UUID member) {
        Http.Exchange read = call(
                salon, "GET", availability(salon) + (member == null ? "" : "&memberId=" + member), null, salon.owner());
        status(read, 200);
        return maps(body(read).get("times")).stream()
                .map(time -> time.get("time") + " " + map(time.get("member")).get("name"))
                .toList();
    }

    @Test
    void ownerManagerAndReceptionistReadThemAndAProfessionalAnAccountantAndASupportSessionAreRefused() {
        for (StaffRole role : List.of(StaffRole.OWNER, StaffRole.MANAGER, StaffRole.RECEPTIONIST)) {
            String token = token(alpha, role);
            status(call(alpha, "GET", availability(alpha), null, token), 200);
            status(call(alpha, "GET", openDays(), null, token), 200);
        }
        String professional = linkedProfessional(alpha, alpha.nimali());
        String accountant = token(alpha, StaffRole.ACCOUNTANT);
        String support = settings.supportSession(alpha.salon());
        for (String token : List.of(professional, accountant, support)) {
            refused(call(alpha, "GET", availability(alpha), null, token), 403, "permission_denied");
            refused(call(alpha, "GET", openDays(), null, token), 403, "permission_denied");
        }
        refused(call(alpha, "GET", availability(alpha), null, null), 401, "invalid_session");
        // The permission is checked before the query: a bad query from a Professional is still 403.
        refused(call(alpha, "GET", BOOKINGS + "/availability", null, professional), 403, "permission_denied");
    }

    @Test
    void anotherSalonsServiceVariantOrProfessionalAnswersAsAnUnknownOne() {
        refused(
                call(
                        alpha,
                        "GET",
                        BOOKINGS + "/availability?serviceId=" + beta.service() + "&date=" + TOMORROW,
                        null,
                        alpha.owner()),
                404,
                "not_found");
        refused(
                call(alpha, "GET", availability(alpha) + "&memberId=" + beta.nimali(), null, alpha.owner()),
                404,
                "not_found");
        refused(
                call(alpha, "GET", availability(alpha) + "&variantId=" + UUID.randomUUID(), null, alpha.owner()),
                400,
                "bad_request");
        // A "No preference" hold for another salon's service places nothing.
        Map<String, Object> hold = with(
                with(hold(alpha, "10:00"), "memberId", null),
                "serviceId",
                beta.service().toString());
        refused(place(alpha, hold, alpha.owner()), 404, "not_found");
    }

    @Test
    void anotherSalonsBookingsLeaveCloseAndRulesChangeNothingHere() {
        List<String> before = times(alpha, null);
        List<String> nimaliBefore = times(alpha, alpha.nimali());

        // Beta books its own Nimali and Kasun at 10:00, sends Kasun on leave, closes the day and halves its interval.
        held(beta, hold(beta, beta.nimali(), TOMORROW, "10:00"));
        held(beta, hold(beta, beta.kasun(), TOMORROW, "10:00"));
        Map<String, Object> leave = new LinkedHashMap<>();
        leave.put("firstDay", TOMORROW);
        leave.put("lastDay", TOMORROW);
        leave.put("type", "sick");
        status(
                settings.call(
                        beta.salon(),
                        "POST",
                        "/api/v1/team/members/" + beta.nimali() + "/schedule/leave",
                        leave,
                        beta.owner()),
                201);
        status(
                settings.call(beta.salon(), "POST", "/api/v1/salon/closures", Map.of("date", TOMORROW), beta.owner()),
                201);
        Http.Exchange read = settings.call(beta.salon(), "GET", BookingRulesFixture.SETTINGS, null, beta.owner());
        status(
                settings.call(
                        beta.salon(),
                        "PUT",
                        BookingRulesFixture.SETTINGS,
                        BookingRulesFixture.save(
                                ((Number) body(read).get("version")).longValue(),
                                BookingRulesFixture.with("slotIntervalMinutes", 30)),
                        beta.owner()),
                200);

        assertThat(times(alpha, null)).isEqualTo(before);
        assertThat(times(alpha, alpha.nimali())).isEqualTo(nimaliBefore);
        assertThat(before).contains("10:00 Kasun Silva", "10:15 Kasun Silva");

        Http.Exchange closed = call(beta, "GET", availability(beta), null, beta.owner());
        assertThat(body(closed)).containsEntry("reason", "closed");
        Http.Exchange openHere =
                call(alpha, "GET", BOOKINGS + "/open-days?from=" + TOMORROW + "&to=" + TOMORROW, null, alpha.owner());
        assertThat(body(openHere)).containsEntry("days", List.of(TOMORROW));
    }
}
