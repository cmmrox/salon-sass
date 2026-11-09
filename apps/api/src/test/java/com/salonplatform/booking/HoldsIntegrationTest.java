package com.salonplatform.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.testing.Http;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Placing a hold (F07-S01 AC-1, AC-2, AC-10): what it answers and keeps, what is refused with nothing stored, the
 * reference, and a repeated request.
 */
class HoldsIntegrationTest extends BookingTestBase {

    @Test
    void aHoldIsPlacedForTenMinutesWithAReferenceTheChannelAndItsTimesInTheSalonsZone() {
        Http.Exchange placed = place(alpha, hold(alpha, "10:00"), alpha.owner());

        status(placed, 201);
        Map<String, Object> booking = body(placed);
        assertThat(booking)
                .containsEntry("state", "held")
                .containsEntry("startsAt", "2086-06-16T10:00:00+05:30")
                .containsEntry("endsAt", "2086-06-16T11:00:00+05:30")
                .containsEntry("holdExpiresAt", "2086-06-15T08:10:00+05:30")
                .containsEntry("createdAt", "2086-06-15T08:00:00+05:30")
                .containsEntry("channel", "phone")
                .containsEntry("priceMinor", 350_000)
                .containsEntry("currency", "LKR");
        assertThat((String) booking.get("reference"))
                .matches("[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{3}-[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{3}");
        assertThat(map(booking.get("member")))
                .containsEntry("id", alpha.nimali().toString())
                .containsEntry("name", "Nimali Perera");
        assertThat(map(booking.get("service")))
                .containsEntry("id", alpha.service().toString())
                .containsEntry("name", "Cut and finish")
                .containsEntry("variantId", null)
                .containsEntry("variantName", null);
        assertThat(map(booking.get("customer")))
                .containsEntry("name", "Dilani Fernando")
                .containsEntry("mobile", "077 123 4567");
    }

    @Test
    void theBookingKeepsTheTermsItWasMadeOnAndTheRulesVersionWhateverChangesAfterwards() {
        Map<String, Object> booking = held(alpha, hold(alpha, "10:00"));
        Map<String, Object> kept = owner.queryForMap(
                "SELECT service_terms_id, rules_version_id, segment_kinds::text AS kinds, segment_minutes::text AS minutes,"
                        + " price_minor, customer_mobile, occupied = '{[2086-06-16T04:30:00Z,2086-06-16T05:30:00Z)}'::tstzmultirange"
                        + " AS occupied FROM booking_appointment WHERE id = ?::uuid",
                booking.get("id"));
        UUID terms = owner.queryForObject(
                "SELECT current_terms_id FROM catalogue_service WHERE id = ?", UUID.class, alpha.service());
        UUID rules = owner.queryForObject(
                "SELECT id FROM booking_rules_version WHERE tenant_id = ? ORDER BY version DESC LIMIT 1",
                UUID.class,
                alpha.salon().id());
        assertThat(kept)
                .containsEntry("service_terms_id", terms)
                .containsEntry("rules_version_id", rules)
                .containsEntry("kinds", "{active,buffer}")
                .containsEntry("minutes", "{45,15}")
                .containsEntry("price_minor", 350_000L)
                .containsEntry("customer_mobile", "+94771234567")
                .containsEntry("occupied", true);

        // A new price and new rules later change nothing the booking keeps (CAT-04, decision 089).
        owner.update("UPDATE catalogue_service SET name = 'Renamed' WHERE id = ?", alpha.service());
        assertThat(owner.queryForObject(
                        "SELECT service_terms_id FROM booking_appointment WHERE id = ?::uuid",
                        UUID.class,
                        booking.get("id")))
                .isEqualTo(terms);
    }

    @Test
    void aServiceWithVariantsIsBookedOnOneOfItsOwnAtItsPriceAndTime() {
        Map<String, Object> service = new LinkedHashMap<>();
        service.put("name", "Colour");
        service.put("categoryId", alpha.category().toString());
        service.put("description", null);
        service.put("bufferMinutes", 10);
        service.put("bookableOnline", true);
        service.put(
                "variants",
                Map.of(
                        "name",
                        "Hair length",
                        "items",
                        List.of(
                                Map.of("name", "Short", "priceMinor", 400_000, "durationMinutes", 60),
                                Map.of("name", "Long", "priceMinor", 600_000, "durationMinutes", 90))));
        Http.Exchange created =
                settings.call(alpha.salon(), "POST", "/api/v1/catalogue/services", service, alpha.owner());
        status(created, 201);
        UUID colour = UUID.fromString((String) body(created).get("id"));
        List<Map<String, Object>> variants =
                maps(map(body(created).get("terms")).get("variants"));
        String longHair = (String) variants.stream()
                .filter(variant -> "Long".equals(variant.get("name")))
                .findFirst()
                .orElseThrow()
                .get("id");
        UUID ruwan = member(alpha.salon(), alpha.owner(), "Ruwan Jayasuriya", List.of(colour));

        Map<String, Object> colourHold = with(hold(alpha, ruwan, TOMORROW, "10:00"), "serviceId", colour.toString());
        fieldErrors(place(alpha, colourHold, alpha.owner()), "variantId");
        // A variant of another of the salon's services is a field error too.
        Map<String, Object> other = with(colourHold, "variantId", longHair);
        fieldErrors(place(alpha, with(hold(alpha, "10:00"), "variantId", longHair), alpha.owner()), "variantId");

        refused(
                place(alpha, with(colourHold, "variantId", UUID.randomUUID().toString()), alpha.owner()),
                404,
                "not_found");
        Http.Exchange placed = place(alpha, other, alpha.owner());
        status(placed, 201);
        assertThat(map(body(placed).get("service")))
                .containsEntry("variantId", longHair)
                .containsEntry("variantName", "Long");
        assertThat(body(placed))
                .containsEntry("priceMinor", 600_000)
                .containsEntry("endsAt", "2086-06-16T11:40:00+05:30");
    }

    @Test
    void theSameKeyAndBodyAnswersTheSameBookingAndPlacesNothingNew() {
        String key = UUID.randomUUID().toString();
        Http.Exchange first = send(alpha, "POST", BOOKINGS, hold(alpha, "10:00"), alpha.owner(), key);
        Http.Exchange again = send(alpha, "POST", BOOKINGS, hold(alpha, "10:00"), alpha.owner(), key);

        status(first, 201);
        status(again, 201);
        assertThat(body(again).get("id")).isEqualTo(body(first).get("id"));
        assertThat(body(again).get("reference")).isEqualTo(body(first).get("reference"));
        assertThat(count(alpha, "booking_appointment")).isEqualTo(1);
        assertThat(count(alpha, "booking_state_change")).isEqualTo(1);

        Http.Exchange otherBody = send(alpha, "POST", BOOKINGS, hold(alpha, "11:00"), alpha.owner(), key);
        refused(otherBody, 422, "request_rejected");
        assertThat(count(alpha, "booking_appointment")).isEqualTo(1);
    }

    @Test
    void theKeyIsRequiredAndAUuid() {
        fieldErrors(send(alpha, "POST", BOOKINGS, hold(alpha, "10:00"), alpha.owner(), null), "Idempotency-Key");
        fieldErrors(send(alpha, "POST", BOOKINGS, hold(alpha, "10:00"), alpha.owner(), "key-1"), "Idempotency-Key");
        assertThat(count(alpha, "booking_appointment")).isZero();
    }

    @Test
    void aNameOfTwoToSixtyCharactersIsKeptAndOneOrSixtyOneIsRefused() {
        fieldErrors(
                place(alpha, with(hold(alpha, "10:00"), "customer", customer(" A ", "0771234567")), alpha.owner()),
                "customer.name");
        fieldErrors(
                place(
                        alpha,
                        with(hold(alpha, "10:00"), "customer", customer("x".repeat(61), "0771234567")),
                        alpha.owner()),
                "customer.name");
        status(place(alpha, with(hold(alpha, "10:00"), "customer", customer("Al", "0771234567")), alpha.owner()), 201);
        status(
                place(
                        alpha,
                        with(hold(alpha, "11:00"), "customer", customer("x".repeat(60), "+94771234567")),
                        alpha.owner()),
                201);
    }

    @Test
    void aLandlineAForeignNumberAndAMissingOneAreNotMobiles() {
        for (String mobile : new String[] {"011 234 5678", "+44 7700 900123", "", "call me"}) {
            fieldErrors(
                    place(
                            alpha,
                            with(hold(alpha, "10:00"), "customer", customer("Dilani Fernando", mobile)),
                            alpha.owner()),
                    "customer.mobile");
        }
        fieldErrors(
                place(alpha, with(hold(alpha, "10:00"), "customer", null), alpha.owner()),
                "customer.name",
                "customer.mobile");
        assertThat(count(alpha, "booking_appointment")).isZero();
    }

    @Test
    void onlyAStaffChannelIsAccepted() {
        for (String channel :
                List.of("phone", "whatsapp", "front_desk", "walk_in", "instagram", "google", "staff_web")) {
            String time = "1"
                    + (List.of("phone", "whatsapp", "front_desk", "walk_in", "instagram", "google", "staff_web")
                            .indexOf(channel))
                    + ":00";
            status(place(alpha, with(hold(alpha, time), "channel", channel), alpha.owner()), 201);
        }
        for (String channel : new String[] {"customer_app", "shared_app_link", "email", "PHONE"}) {
            fieldErrors(place(alpha, with(hold(alpha, "18:00"), "channel", channel), alpha.owner()), "channel");
        }
    }

    @Test
    void everyFieldInTheWrongFormIsNamedInOneAnswerAndAnUnknownFieldToo() {
        Map<String, Object> wrong = new LinkedHashMap<>();
        wrong.put("memberId", "nobody");
        wrong.put("serviceId", null);
        wrong.put("date", "16/06/2086");
        wrong.put("time", "10:00:00");
        wrong.put("customer", customer("A", "1"));
        wrong.put("channel", "fax");
        fieldErrors(
                place(alpha, wrong, alpha.owner()),
                "memberId",
                "serviceId",
                "date",
                "time",
                "customer.name",
                "customer.mobile",
                "channel");
        fieldErrors(place(alpha, with(hold(alpha, "10:00"), "price", 1), alpha.owner()), "price");
        fieldErrors(place(alpha, with(hold(alpha, "10:00"), "time", 1000), alpha.owner()), "time");
    }

    @Test
    void anotherSalonsRemovedDeletedOrUnknownProfessionalServiceOrVariantIsNotFoundAlike() {
        Http.Exchange unknown = place(
                alpha, with(hold(alpha, "10:00"), "memberId", UUID.randomUUID().toString()), alpha.owner());
        refused(unknown, 404, "not_found");
        Map<String, Object> notFound = body(unknown);
        refused(
                place(
                        alpha,
                        with(hold(alpha, "10:00"), "memberId", beta.nimali().toString()),
                        alpha.owner()),
                404,
                "not_found");
        refused(
                place(
                        alpha,
                        with(hold(alpha, "10:00"), "serviceId", beta.service().toString()),
                        alpha.owner()),
                404,
                "not_found");
        refused(
                place(
                        alpha,
                        with(
                                hold(alpha, "10:00"),
                                "serviceId",
                                UUID.randomUUID().toString()),
                        alpha.owner()),
                404,
                "not_found");

        UUID removed = member(alpha.salon(), alpha.owner(), "Sanduni Wickramasinghe", List.of(alpha.service()));
        Http.Exchange read =
                settings.call(alpha.salon(), "GET", "/api/v1/team/members/" + removed, null, alpha.owner());
        status(
                settings.call(
                        alpha.salon(),
                        "DELETE",
                        "/api/v1/team/members/" + removed + "?version="
                                + body(read).get("version"),
                        null,
                        alpha.owner()),
                204);
        refused(place(alpha, hold(alpha, removed, TOMORROW, "10:00"), alpha.owner()), 404, "not_found");

        UUID deleted = service(alpha.salon(), alpha.owner(), alpha.category(), "Old treatment", 1_000, 30, 0);
        status(
                settings.call(
                        alpha.salon(),
                        "DELETE",
                        "/api/v1/catalogue/services/" + deleted + "?version=0",
                        null,
                        alpha.owner()),
                204);
        refused(
                place(alpha, with(hold(alpha, "10:00"), "serviceId", deleted.toString()), alpha.owner()),
                404,
                "not_found");

        refused(
                place(
                        alpha,
                        with(
                                hold(alpha, "10:00"),
                                "variantId",
                                UUID.randomUUID().toString()),
                        alpha.owner()),
                400,
                "bad_request");
        assertThat(notFound.keySet()).doesNotContain("errors");
        assertThat(count(alpha, "booking_appointment")).isZero();
    }

    @Test
    void aTimeThatCannotBeBookedIsRefusedSayingWhy() {
        assertUnavailable(hold(alpha, "10:03"), "A booking starts on a five-minute step.");
        assertUnavailable(hold(alpha, alpha.nimali(), "2086-06-15", "07:55"), "The start time has passed.");
        assertUnavailable(
                hold(alpha, alpha.nimali(), "2087-06-17", "10:00"), "A booking can be made up to one year ahead.");
        assertUnavailable(hold(alpha, "07:30"), "The booking falls outside the salon's opening hours.");
        // 19:05 to 20:05 ends after closing at 20:00; 19:00 ends exactly at closing.
        assertUnavailable(hold(alpha, "19:05"), "The booking falls outside the salon's opening hours.");
        status(place(alpha, hold(alpha, "19:00"), alpha.owner()), 201);

        UUID hiruni = member(alpha.salon(), alpha.owner(), "Hiruni Senanayake", List.of());
        assertUnavailable(hold(alpha, hiruni, TOMORROW, "10:00"), "The professional does not perform this service.");
        assertThat(count(alpha, "booking_appointment")).isEqualTo(1);
    }

    @Test
    void nowAndExactlyAYearAheadCanBeBooked() {
        status(place(alpha, hold(alpha, alpha.nimali(), "2086-06-15", "08:00"), alpha.owner()), 201);
        status(place(alpha, hold(alpha, alpha.nimali(), "2087-06-15", "10:00"), alpha.owner()), 201);
    }

    @Test
    void aBookingOutsideTheProfessionalsWorkingTimeIsRefused() {
        Http.Exchange schedule = settings.call(
                alpha.salon(), "GET", "/api/v1/team/members/" + alpha.kasun() + "/schedule", null, alpha.owner());
        status(schedule, 200);
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("date", TOMORROW);
        block.put("starts", "12:00");
        block.put("finishes", "13:00");
        block.put("label", "Training");
        status(
                settings.call(
                        alpha.salon(),
                        "POST",
                        "/api/v1/team/members/" + alpha.kasun() + "/schedule/blocked-time",
                        block,
                        alpha.owner()),
                201);

        assertUnavailable(
                hold(alpha, alpha.kasun(), TOMORROW, "11:30"),
                "The booking falls outside the professional's working time on that date.");
        status(place(alpha, hold(alpha, alpha.kasun(), TOMORROW, "11:00"), alpha.owner()), 201);
        status(place(alpha, hold(alpha, alpha.kasun(), TOMORROW, "13:00"), alpha.owner()), 201);
    }

    @Test
    void aSalonClosedThatDayRefusesTheBooking() {
        Map<String, Object> closure = new LinkedHashMap<>();
        closure.put("date", TOMORROW);
        closure.put("reason", "Staff day out");
        status(settings.call(alpha.salon(), "POST", "/api/v1/salon/closures", closure, alpha.owner()), 201);
        assertUnavailable(hold(alpha, "10:00"), "The salon is closed on that date.");
    }

    @Test
    void aSalonThatNeverSavedItsBookingRulesCannotBook() {
        owner.update(
                "DELETE FROM booking_rules_version WHERE tenant_id = ?",
                alpha.salon().id());
        refused(place(alpha, hold(alpha, "10:00"), alpha.owner()), 409, "rules_not_set");
        assertThat(count(alpha, "booking_appointment")).isZero();
    }

    @Test
    void aReferenceAlreadyTakenInTheSalonIsDrawnAgainAndAnotherSalonMayHoldIt() {
        RANDOM.next("K7M4QX");
        assertThat(held(alpha, hold(alpha, "10:00")).get("reference")).isEqualTo("K7M-4QX");
        RANDOM.next("K7M4QX");
        assertThat(held(beta, hold(beta, "10:00")).get("reference")).isEqualTo("K7M-4QX");
        RANDOM.next("K7M4QX");
        RANDOM.next("P2Q3RS");
        assertThat(held(alpha, hold(alpha, "11:00")).get("reference")).isEqualTo("P2Q-3RS");
    }

    @Test
    void theDatabaseRefusesASecondUseOfAReferenceInOneSalon() {
        Map<String, Object> first = held(alpha, hold(alpha, "10:00"));
        String stored = ((String) first.get("reference")).replace("-", "");
        Http.Exchange second = place(alpha, hold(alpha, "11:00"), alpha.owner());
        status(second, 201);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> owner.update(
                        "UPDATE booking_appointment SET reference = ? WHERE id = ?::uuid",
                        stored,
                        body(second).get("id")))
                .hasMessageContaining("booking_appointment_reference");
    }

    @Test
    void aHoldsExpiryIsTenMinutesAfterItWasPlacedByTheApplicationsClock() {
        moveClock(Duration.ofMinutes(7).plusSeconds(13));
        Map<String, Object> booking = held(alpha, hold(alpha, "10:00"));
        assertThat(booking.get("holdExpiresAt")).isEqualTo("2086-06-15T08:17:13+05:30");
    }

    private void assertUnavailable(Map<String, Object> hold, String detail) {
        Http.Exchange refused = place(alpha, hold, alpha.owner());
        refused(refused, 409, "slot_unavailable");
        assertThat(body(refused).get("detail")).isEqualTo(detail);
    }
}
