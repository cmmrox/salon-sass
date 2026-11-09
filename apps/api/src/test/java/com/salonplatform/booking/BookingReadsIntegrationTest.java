package com.salonplatform.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.identity.domain.StaffRole;
import com.salonplatform.testing.Http;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Reading bookings with {@code calendar.read} (F07-S01 AC-11): a page by start time over at most 31 local dates, the
 * filters and the search, one booking with its history and what the caller may do now, and a Professional's own
 * bookings.
 */
class BookingReadsIntegrationTest extends BookingTestBase {

    private static final String TODAY = "2086-06-15";

    private Http.Exchange list(Salon salon, String query, String token) {
        return call(salon, "GET", BOOKINGS + "?" + query, null, token);
    }

    private List<Map<String, Object>> items(Http.Exchange exchange) {
        status(exchange, 200);
        return maps(body(exchange).get("items"));
    }

    @Test
    void aListIsByStartTimeOverTheLocalDatesAndShowsTheMobileNationally() {
        Map<String, Object> late = held(alpha, hold(alpha, "15:00"));
        Map<String, Object> early = confirmed(alpha, hold(alpha, alpha.kasun(), TOMORROW, "09:00"));
        Map<String, Object> today = held(alpha, hold(alpha, alpha.nimali(), TODAY, "18:00"));
        held(alpha, hold(alpha, alpha.nimali(), "2086-06-18", "10:00"));

        List<Map<String, Object>> items = items(list(alpha, "from=" + TOMORROW + "&to=" + TOMORROW, alpha.owner()));

        assertThat(items).extracting(item -> item.get("id")).containsExactly(early.get("id"), late.get("id"));
        assertThat(map(items.getFirst().get("customer"))).containsEntry("mobile", "077 123 4567");
        assertThat(items(list(alpha, "from=" + TODAY + "&to=2086-06-17", alpha.owner())))
                .extracting(item -> item.get("id"))
                .containsExactly(today.get("id"), early.get("id"), late.get("id"));
        assertThat(body(list(alpha, "from=" + TODAY + "&to=2086-06-18", alpha.owner())))
                .containsEntry("total", 4);
    }

    @Test
    void aLocalDateIsTheSalonsFromItsMidnight() {
        Map<String, Object> lateTonight = held(alpha, hold(alpha, alpha.nimali(), TODAY, "19:00"));
        // 19:00 in Colombo is 13:30 UTC; the 15th by the salon's date.
        assertThat(items(list(alpha, "from=" + TODAY + "&to=" + TODAY, alpha.owner())))
                .extracting(item -> item.get("id"))
                .containsExactly(lateTonight.get("id"));
        assertThat(items(list(alpha, "from=" + TOMORROW + "&to=" + TOMORROW, alpha.owner())))
                .isEmpty();
    }

    @Test
    void theRangeIsAtMostThirtyOneDatesAndBothEndsAreRequired() {
        status(list(alpha, "from=2086-06-01&to=2086-07-01", alpha.owner()), 200);
        fieldErrors(list(alpha, "from=2086-06-01&to=2086-07-02", alpha.owner()), "to");
        fieldErrors(list(alpha, "from=2086-06-10&to=2086-06-09", alpha.owner()), "to");
        fieldErrors(list(alpha, "to=2086-06-09", alpha.owner()), "from");
        fieldErrors(list(alpha, "from=2086-6-1&to=2086-06-09", alpha.owner()), "from");
        fieldErrors(
                list(alpha, "from=2086-06-01&to=2086-06-09&state=gone&memberId=x&page=-1&size=101", alpha.owner()),
                "state",
                "memberId",
                "page",
                "size");
        fieldErrors(list(alpha, "from=2086-06-01&to=2086-06-09&state=released", alpha.owner()), "state");
        fieldErrors(list(alpha, "from=2086-06-01&to=2086-06-09&q=" + "x".repeat(61), alpha.owner()), "q");
    }

    @Test
    void stateAndProfessionalFilterTheList() {
        Map<String, Object> holdOnly = held(alpha, hold(alpha, "10:00"));
        Map<String, Object> booked = confirmed(alpha, hold(alpha, "12:00"));
        Map<String, Object> kasuns = confirmed(alpha, hold(alpha, alpha.kasun(), TOMORROW, "12:00"));
        String day = "from=" + TOMORROW + "&to=" + TOMORROW;

        assertThat(items(list(alpha, day + "&state=held", alpha.owner())))
                .extracting(item -> item.get("id"))
                .containsExactly(holdOnly.get("id"));
        assertThat(items(list(alpha, day + "&state=confirmed&state=held", alpha.owner())))
                .extracting(item -> item.get("id"))
                .containsExactly(holdOnly.get("id"), booked.get("id"), kasuns.get("id"));
        assertThat(items(list(alpha, day + "&memberId=" + alpha.kasun(), alpha.owner())))
                .extracting(item -> item.get("id"))
                .containsExactly(kasuns.get("id"));
        assertThat(items(list(alpha, day + "&memberId=" + beta.kasun(), alpha.owner())))
                .isEmpty();
    }

    @Test
    void theSearchFindsPartOfTheNameTheMobileInAnyCommonFormAndTheReference() {
        Map<String, Object> dilani = held(alpha, hold(alpha, "10:00"));
        Map<String, Object> other =
                held(alpha, with(hold(alpha, "12:00"), "customer", customer("Amaya Rajapaksa", "071 765 4321")));
        String day = "from=" + TOMORROW + "&to=" + TOMORROW + "&q=";
        String reference = (String) dilani.get("reference");

        for (String q : List.of(
                "dilani",
                "FERNANDO",
                "ani%20Fer",
                "0771234567",
                "%2B94771234567",
                "077%20123%204567",
                reference,
                reference.replace("-", "").toLowerCase(java.util.Locale.ROOT))) {
            assertThat(items(list(alpha, day + q, alpha.owner())))
                    .as(q)
                    .extracting(item -> item.get("id"))
                    .containsExactly(dilani.get("id"));
        }
        assertThat(items(list(alpha, day + "amaya", alpha.owner())))
                .extracting(item -> item.get("id"))
                .containsExactly(other.get("id"));
        assertThat(items(list(alpha, day + "%25", alpha.owner()))).isEmpty();
        assertThat(items(list(alpha, day + "_", alpha.owner()))).isEmpty();
    }

    @Test
    void releasedHoldsAndHoldsThatRanOutAreNeverListed() {
        Map<String, Object> released = held(alpha, hold(alpha, "10:00"));
        status(act(alpha, released, "release", alpha.owner()), 204);
        held(alpha, hold(alpha, "12:00"));
        Map<String, Object> booked = confirmed(alpha, hold(alpha, "14:00"));
        moveClock(Duration.ofMinutes(10));

        assertThat(items(list(alpha, "from=" + TOMORROW + "&to=" + TOMORROW, ownerNow(alpha))))
                .extracting(item -> item.get("id"))
                .containsExactly(booked.get("id"));
    }

    @Test
    void aListIsPaged() {
        for (String time : List.of("09:00", "10:00", "11:00", "12:00", "13:00")) {
            held(alpha, hold(alpha, time));
        }
        Http.Exchange second = list(alpha, "from=" + TOMORROW + "&to=" + TOMORROW + "&page=1&size=2", alpha.owner());

        assertThat(body(second))
                .containsEntry("page", 1)
                .containsEntry("size", 2)
                .containsEntry("total", 5);
        assertThat(items(second))
                .extracting(item -> item.get("startsAt"))
                .containsExactly("2086-06-16T11:00:00+05:30", "2086-06-16T12:00:00+05:30");
    }

    @Test
    void oneBookingComesWithItsHistoryAndWhatTheCallerMayDoNow() {
        Map<String, Object> placed = held(alpha, hold(alpha, alpha.nimali(), TODAY, "10:00"));
        Map<String, Object> detail = body(read(alpha, placed.get("id"), alpha.owner()));
        assertThat(map(detail.get("booking")))
                .containsEntry("id", placed.get("id"))
                .containsEntry("holdExpiresAt", "2086-06-15T08:10:00+05:30");
        assertThat(maps(detail.get("history"))).hasSize(1);
        assertThat(detail.get("allowedActions")).isEqualTo(List.of("confirm", "release"));

        status(act(alpha, placed, "confirm", alpha.owner()), 200);
        assertThat(body(read(alpha, placed.get("id"), alpha.owner())).get("allowedActions"))
                .isEqualTo(List.of("arrive", "cancel"));
        assertThat(body(read(alpha, placed.get("id"), token(alpha, StaffRole.ACCOUNTANT)))
                        .get("code"))
                .isEqualTo("permission_denied");
    }

    @Test
    void aProfessionalSeesOnlyTheBookingsOfTheMemberLinkedToTheirAccount() {
        Map<String, Object> nimalis = confirmed(alpha, hold(alpha, alpha.nimali(), TODAY, "10:00"));
        Map<String, Object> kasuns = confirmed(alpha, hold(alpha, alpha.kasun(), TODAY, "10:00"));
        String nimali = linkedProfessional(alpha, alpha.nimali());
        String day = "from=" + TODAY + "&to=" + TODAY;

        assertThat(items(list(alpha, day, nimali)))
                .extracting(item -> item.get("id"))
                .containsExactly(nimalis.get("id"));
        assertThat(items(list(alpha, day + "&memberId=" + alpha.kasun(), nimali)))
                .isEmpty();
        status(read(alpha, nimalis.get("id"), nimali), 200);
        refused(read(alpha, kasuns.get("id"), nimali), 404, "not_found");
        assertThat(body(read(alpha, nimalis.get("id"), nimali)).get("allowedActions"))
                .isEqualTo(List.of("arrive"));

        String unlinked = token(alpha, StaffRole.PROFESSIONAL);
        assertThat(items(list(alpha, day, unlinked))).isEmpty();
        refused(read(alpha, nimalis.get("id"), unlinked), 404, "not_found");
    }

    @Test
    void aProfessionalGrantedTheSalonsCalendarReadsEveryBookingButAttendsOnlyTheirOwn() {
        Map<String, Object> nimalis = confirmed(alpha, hold(alpha, alpha.nimali(), TODAY, "10:00"));
        Map<String, Object> kasuns = confirmed(alpha, hold(alpha, alpha.kasun(), TODAY, "10:00"));
        com.salonplatform.branding.SettingsTestSupport.Staff professional =
                settings.signedIn(alpha.salon(), StaffRole.PROFESSIONAL);
        owner.update(
                "UPDATE identity_staff SET professional_grant = true WHERE id = ?",
                professional.account().getId());
        owner.update(
                "UPDATE staff_team_member SET sign_in_account_id = ? WHERE id = ?",
                professional.account().getId(),
                alpha.nimali());

        assertThat(items(list(alpha, "from=" + TODAY + "&to=" + TODAY, professional.token())))
                .extracting(item -> item.get("id"))
                .containsExactlyInAnyOrder(nimalis.get("id"), kasuns.get("id"));
        assertThat(body(read(alpha, kasuns.get("id"), professional.token())).get("allowedActions"))
                .isEqualTo(List.of());
        refused(act(alpha, kasuns, "arrive", professional.token()), 404, "not_found");
        status(act(alpha, nimalis, "arrive", professional.token()), 200);
    }
}
