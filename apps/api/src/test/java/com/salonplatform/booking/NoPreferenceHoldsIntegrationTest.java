package com.salonplatform.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.testing.Http;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;

/**
 * F07-S02 AC-5 and AC-7's hold length: a hold placed with no professional ("No preference") is assigned at that moment
 * by the salon's strategy, goes to the next free professional when the time was just taken, records that it was
 * assigned, and of simultaneous ones for one time with three free professionals exactly three succeed with three
 * different professionals. Every hold lasts the current rules' hold length; one already placed keeps its expiry.
 */
class NoPreferenceHoldsIntegrationTest extends BookingTestBase {

    static Map<String, Object> anyone(Salon salon, String time) {
        return with(hold(salon, time), "memberId", null);
    }

    static String memberOf(Map<String, Object> booking) {
        return (String) map(booking.get("member")).get("id");
    }

    boolean assigned(Object bookingId) {
        return Boolean.TRUE.equals(owner.queryForObject(
                "SELECT assigned FROM booking_appointment WHERE id = ?::uuid", Boolean.class, bookingId));
    }

    void saveRules(Salon salon, Map<String, Object> rules) {
        Http.Exchange read = settings.call(salon.salon(), "GET", BookingRulesFixture.SETTINGS, null, ownerNow(salon));
        long version = ((Number) body(read).get("version")).longValue();
        status(
                settings.call(
                        salon.salon(),
                        "PUT",
                        BookingRulesFixture.SETTINGS,
                        BookingRulesFixture.save(version, rules),
                        ownerNow(salon)),
                200);
    }

    @Test
    void aNoPreferenceHoldGoesToTheLeastBookedFreeProfessionalAndRecordsThatItWasAssigned() {
        Map<String, Object> named = held(alpha, hold(alpha, alpha.kasun(), TOMORROW, "09:00"));
        assertThat(assigned(named.get("id"))).isFalse();

        // Kasun has an hour booked that day and Nimali none.
        Map<String, Object> first = held(alpha, anyone(alpha, "12:00"));
        assertThat(memberOf(first)).isEqualTo(alpha.nimali().toString());
        assertThat(map(first.get("member"))).containsEntry("name", "Nimali Perera");
        assertThat(assigned(first.get("id"))).isTrue();

        // Now each has an hour: the tie goes to Kasun, listed first.
        Map<String, Object> second = held(alpha, anyone(alpha, "15:00"));
        assertThat(memberOf(second)).isEqualTo(alpha.kasun().toString());
    }

    @Test
    void withMostBookedTheBusiestFreeProfessionalTakesIt() {
        held(alpha, hold(alpha, alpha.nimali(), TOMORROW, "09:00"));
        saveRules(alpha, BookingRulesFixture.with("noPreference", "most_booked"));

        assertThat(memberOf(held(alpha, anyone(alpha, "12:00"))))
                .isEqualTo(alpha.nimali().toString());
        // Nimali is busy at 12:30, so Kasun takes it.
        assertThat(memberOf(held(alpha, anyone(alpha, "12:30"))))
                .isEqualTo(alpha.kasun().toString());
    }

    @Test
    void aTimeTakenBetweenTheSearchAndTheHoldGoesToAnotherFreeProfessional() {
        Http.Exchange search = call(
                alpha,
                "GET",
                BOOKINGS + "/availability?serviceId=" + alpha.service() + "&date=" + TOMORROW,
                null,
                alpha.owner());
        status(search, 200);
        Map<String, Object> offered = maps(body(search).get("times")).stream()
                .filter(time -> "10:00".equals(time.get("time")))
                .findFirst()
                .orElseThrow();
        assertThat(map(offered.get("member"))).containsEntry("id", alpha.kasun().toString());

        held(alpha, hold(alpha, alpha.kasun(), TOMORROW, "10:00"));

        Map<String, Object> placed = held(alpha, anyone(alpha, "10:00"));
        assertThat(memberOf(placed)).isEqualTo(alpha.nimali().toString());
        assertThat(placed).containsEntry("startsAt", "2086-06-16T10:00:00+05:30");
    }

    @Test
    void whenNobodyIsFreeItIsSlotTakenAndATimeNobodyCouldTakeIsSlotUnavailable() {
        held(alpha, hold(alpha, alpha.kasun(), TOMORROW, "10:00"));
        held(alpha, hold(alpha, alpha.nimali(), TOMORROW, "10:30"));
        long before = count(alpha, "booking_appointment");

        refused(place(alpha, anyone(alpha, "10:15"), alpha.owner()), 409, "slot_taken");
        refused(place(alpha, anyone(alpha, "19:30"), alpha.owner()), 409, "slot_unavailable");
        refused(place(alpha, anyone(alpha, "10:03"), alpha.owner()), 409, "slot_unavailable");
        UUID massage = service(alpha.salon(), alpha.owner(), alpha.category(), "Head massage", 2_000, 30, 0);
        Http.Exchange nobody =
                place(alpha, with(anyone(alpha, "11:00"), "serviceId", massage.toString()), alpha.owner());
        refused(nobody, 409, "slot_unavailable");
        assertThat(body(nobody)).containsEntry("detail", "Nobody on the team performs this service.");
        assertThat(count(alpha, "booking_appointment")).isEqualTo(before);

        // A memberId left out is "No preference" too.
        Map<String, Object> leftOut = hold(alpha, "16:00");
        leftOut.remove("memberId");
        assertThat(assigned(held(alpha, leftOut).get("id"))).isTrue();
    }

    @Test
    void ofSimultaneousNoPreferenceHoldsForOneTimeWithThreeFreeProfessionalsThreeSucceedWithThreeDifferentOnes()
            throws Exception {
        UUID ishara = member(alpha.salon(), alpha.owner(), "Ishara Fonseka", List.of(alpha.service()));
        int atOnce = 8;
        List<Callable<Http.Exchange>> requests = new ArrayList<>();
        for (int i = 0; i < atOnce; i++) {
            requests.add(() -> place(alpha, anyone(alpha, "10:00"), alpha.owner()));
        }

        List<Http.Exchange> answers = atOnce(requests);

        List<Http.Exchange> placed =
                answers.stream().filter(answer -> answer.status() == 201).toList();
        assertThat(placed).hasSize(3);
        assertThat(placed.stream().map(answer -> memberOf(body(answer))))
                .containsExactlyInAnyOrder(
                        ishara.toString(),
                        alpha.kasun().toString(),
                        alpha.nimali().toString());
        assertThat(answers.stream().filter(answer -> answer.status() != 201))
                .hasSize(atOnce - 3)
                .allSatisfy(answer -> refused(answer, 409, "slot_taken"));
        assertThat(owner.queryForObject(
                        "SELECT count(DISTINCT member_id) FROM booking_appointment WHERE tenant_id = ? AND assigned",
                        Long.class,
                        alpha.salon().id()))
                .isEqualTo(3);
    }

    @Test
    void everyHoldTakesItsExpiryFromTheCurrentRulesAndOneAlreadyPlacedKeepsIts() {
        Map<String, Object> tenMinutes = held(alpha, hold(alpha, "09:00"));
        assertThat(tenMinutes).containsEntry("holdExpiresAt", "2086-06-15T08:10:00+05:30");

        saveRules(alpha, BookingRulesFixture.with("holdMinutes", 5));
        Map<String, Object> fiveMinutes = held(alpha, hold(alpha, "11:00"));
        assertThat(fiveMinutes).containsEntry("holdExpiresAt", "2086-06-15T08:05:00+05:30");
        assertThat(map(body(read(alpha, tenMinutes.get("id"), alpha.owner())).get("booking")))
                .containsEntry("holdExpiresAt", "2086-06-15T08:10:00+05:30");

        saveRules(alpha, BookingRulesFixture.with("holdMinutes", 30));
        assertThat(held(alpha, anyone(alpha, "14:00"))).containsEntry("holdExpiresAt", "2086-06-15T08:30:00+05:30");

        // After six minutes the five-minute hold has run out and the ten-minute one has not.
        moveClock(Duration.ofMinutes(6));
        refused(act(alpha, fiveMinutes, "confirm", ownerNow(alpha)), 409, "hold_expired");
        status(act(alpha, tenMinutes, "confirm", ownerNow(alpha)), 200);
    }
}
