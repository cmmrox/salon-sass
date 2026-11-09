package com.salonplatform.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.identity.domain.StaffRole;
import com.salonplatform.testing.Http;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Who may do what with bookings (F07-S01 AC-12, decision 108(b)): {@code booking.manage} for the front desk,
 * {@code booking.attend} for a Professional's own customers, no booking access for an Accountant, reads only for a
 * support session, and a suspended or read-only salon answering as the catalogue does.
 */
class BookingAccessIntegrationTest extends BookingTestBase {

    private static final String TODAY = "2086-06-15";

    @Test
    void ownerManagerAndReceptionistPlaceConfirmCancelAndMarkNoShows() {
        for (StaffRole role : List.of(StaffRole.OWNER, StaffRole.MANAGER, StaffRole.RECEPTIONIST)) {
            String token = token(alpha, role);
            String time = switch (role) {
                case OWNER -> "09:00";
                case MANAGER -> "11:00";
                default -> "13:00";
            };
            Http.Exchange placed = place(alpha, hold(alpha, time), token);
            status(placed, 201);
            status(act(alpha, body(placed), "confirm", token), 200);
            status(
                    call(
                            alpha,
                            "POST",
                            BOOKINGS + "/" + body(placed).get("id") + "/cancel",
                            Map.of("reason", "other"),
                            token),
                    200);
            Http.Exchange other = place(alpha, hold(alpha, alpha.kasun(), TODAY, time), token);
            status(other, 201);
            status(act(alpha, body(other), "release", token), 204);
        }
        assertThat(StaffRole.RECEPTIONIST.permissions(false))
                .contains("booking.manage", "booking.attend", "calendar.read");
        assertThat(StaffRole.PROFESSIONAL.permissions(true))
                .contains("booking.attend")
                .doesNotContain("booking.manage");
        assertThat(StaffRole.ACCOUNTANT.permissions(true))
                .doesNotContain("booking.manage", "booking.attend", "calendar.read");
    }

    @Test
    void aProfessionalNeitherPlacesNorConfirmsNorCancelsButAttendsTheirOwnCustomers() {
        Map<String, Object> nimalis = confirmed(alpha, hold(alpha, alpha.nimali(), TODAY, "10:00"));
        Map<String, Object> kasuns = confirmed(alpha, hold(alpha, alpha.kasun(), TODAY, "10:00"));
        Map<String, Object> aHold = held(alpha, hold(alpha, "10:00"));
        String professional = linkedProfessional(alpha, alpha.nimali());

        refused(place(alpha, hold(alpha, "12:00"), professional), 403, "permission_denied");
        refused(act(alpha, aHold, "confirm", professional), 403, "permission_denied");
        refused(act(alpha, aHold, "release", professional), 403, "permission_denied");
        refused(
                call(
                        alpha,
                        "POST",
                        BOOKINGS + "/" + nimalis.get("id") + "/cancel",
                        Map.of("reason", "other"),
                        professional),
                403,
                "permission_denied");
        refused(act(alpha, nimalis, "no-show", professional), 403, "permission_denied");
        refused(act(alpha, kasuns, "arrive", professional), 404, "not_found");

        status(act(alpha, nimalis, "arrive", professional), 200);
        status(act(alpha, nimalis, "start", professional), 200);
        assertThat(stateInTheDatabase(kasuns.get("id"))).isEqualTo("confirmed");
    }

    @Test
    void anAccountantGetsPermissionDeniedFromEveryBookingEndpoint() {
        Map<String, Object> booking = confirmed(alpha, hold(alpha, alpha.nimali(), TODAY, "10:00"));
        String accountant = token(alpha, StaffRole.ACCOUNTANT);

        everyEndpointRefused(booking, accountant, "permission_denied", true);
        assertThat(stateInTheDatabase(booking.get("id"))).isEqualTo("confirmed");
    }

    @Test
    void aSupportSessionReadsBookingsAndChangesNothing() {
        Map<String, Object> booking = confirmed(alpha, hold(alpha, alpha.nimali(), TODAY, "10:00"));
        String support = settings.supportSession(alpha.salon());

        status(call(alpha, "GET", BOOKINGS + "?from=" + TODAY + "&to=" + TODAY, null, support), 200);
        Http.Exchange read = read(alpha, booking.get("id"), support);
        status(read, 200);
        assertThat(body(read).get("allowedActions")).isEqualTo(List.of());
        everyEndpointRefused(booking, support, "support_read_only", false);
        assertThat(stateInTheDatabase(booking.get("id"))).isEqualTo("confirmed");
        assertThat(count(alpha, "booking_appointment")).isEqualTo(1);
    }

    @Test
    void aSuspendedSalonAnswersSuspendedToEveryBookingEndpoint() {
        Map<String, Object> booking = confirmed(alpha, hold(alpha, alpha.nimali(), TODAY, "10:00"));
        salons.suspend(alpha.salon());

        everyEndpointRefused(booking, alpha.owner(), "salon_suspended", true);
    }

    @Test
    void aReadOnlySalonReadsBookingsAndRefusesEveryChange() {
        Map<String, Object> booking = confirmed(alpha, hold(alpha, alpha.nimali(), TODAY, "10:00"));
        owner.update(
                "UPDATE tenant SET access_state = 'read_only' WHERE id = ?",
                alpha.salon().id());

        status(read(alpha, booking.get("id"), alpha.owner()), 200);
        everyEndpointRefused(booking, alpha.owner(), "salon_read_only", false);
        assertThat(stateInTheDatabase(booking.get("id"))).isEqualTo("confirmed");
    }

    @Test
    void withoutASessionEveryEndpointAsksToSignIn() {
        Map<String, Object> booking = confirmed(alpha, hold(alpha, alpha.nimali(), TODAY, "10:00"));
        refused(call(alpha, "GET", BOOKINGS + "?from=" + TODAY + "&to=" + TODAY, null, null), 401, "invalid_session");
        refused(read(alpha, booking.get("id"), null), 401, "invalid_session");
        refused(place(alpha, hold(alpha, "12:00"), null), 401, "invalid_session");
        refused(act(alpha, booking, "arrive", null), 401, "invalid_session");
    }

    /** Every change refused with this code, and the reads too when {@code reads} is true. */
    private void everyEndpointRefused(Map<String, Object> booking, String token, String code, boolean reads) {
        if (reads) {
            refused(call(alpha, "GET", BOOKINGS + "?from=" + TODAY + "&to=" + TODAY, null, token), 403, code);
            refused(read(alpha, booking.get("id"), token), 403, code);
        }
        refused(place(alpha, hold(alpha, "12:00"), token), 403, code);
        for (String action : List.of("confirm", "release", "arrive", "start", "no-show", "undo-no-show")) {
            refused(act(alpha, booking, action, token), 403, code);
        }
        refused(
                call(alpha, "POST", BOOKINGS + "/" + booking.get("id") + "/cancel", Map.of("reason", "other"), token),
                403,
                code);
    }
}
