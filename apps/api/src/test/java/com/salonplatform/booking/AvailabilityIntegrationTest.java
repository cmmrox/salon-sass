package com.salonplatform.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.testing.Http;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * F07-S02 AC-1 to AC-4 and AC-6 through the endpoints: the free times are worked out live from the opening hours, the
 * professional's windows, leave, blocked time, bookings and holds, on the rules' interval, with the strategy's pick for
 * "No preference" and a reason when there are none; and the open days. Every answer is held to the committed contract.
 */
class AvailabilityIntegrationTest extends BookingTestBase {

    static final String AVAILABILITY = BOOKINGS + "/availability";
    static final String TODAY = "2086-06-15";

    Http.Exchange availability(Salon salon, String query, String token) {
        return call(salon, "GET", AVAILABILITY + "?" + query, null, token);
    }

    /** The answer for the salon's service on the date, for one professional or "No preference" (null). */
    Map<String, Object> answer(Salon salon, String date, UUID member) {
        Http.Exchange read = availability(
                salon,
                "serviceId=" + salon.service() + "&date=" + date + (member == null ? "" : "&memberId=" + member),
                ownerNow(salon));
        status(read, 200);
        return body(read);
    }

    static List<String> times(Map<String, Object> answer) {
        return maps(answer.get("times")).stream()
                .map(time -> (String) time.get("time"))
                .toList();
    }

    /** Who takes each time, by time. */
    static Map<String, String> takers(Map<String, Object> answer) {
        return maps(answer.get("times")).stream()
                .collect(Collectors.toMap(
                        time -> (String) time.get("time"),
                        time -> (String) map(time.get("member")).get("id"),
                        (a, b) -> a,
                        LinkedHashMap::new));
    }

    /** Every quarter hour from {@code first} to {@code last}, both included. */
    static List<String> quarters(String first, String last) {
        int from = minutes(first);
        int to = minutes(last);
        return IntStream.iterate(from, minute -> minute <= to, minute -> minute + 15)
                .mapToObj(minute -> String.format("%02d:%02d", minute / 60, minute % 60))
                .toList();
    }

    static int minutes(String time) {
        return Integer.parseInt(time.substring(0, 2)) * 60 + Integer.parseInt(time.substring(3));
    }

    String schedule(UUID member) {
        return "/api/v1/team/members/" + member + "/schedule";
    }

    void block(Salon salon, UUID member, String date, String starts, String finishes) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("date", date);
        block.put("starts", starts);
        block.put("finishes", finishes);
        block.put("label", "Training");
        status(settings.call(salon.salon(), "POST", schedule(member) + "/blocked-time", block, salon.owner()), 201);
    }

    void leave(Salon salon, UUID member, String date) {
        Map<String, Object> leave = new LinkedHashMap<>();
        leave.put("firstDay", date);
        leave.put("lastDay", date);
        leave.put("type", "annual");
        status(settings.call(salon.salon(), "POST", schedule(member) + "/leave", leave, salon.owner()), 201);
    }

    void close(Salon salon, String date) {
        Map<String, Object> closure = new LinkedHashMap<>();
        closure.put("date", date);
        status(settings.call(salon.salon(), "POST", "/api/v1/salon/closures", closure, salon.owner()), 201);
    }

    void saveRules(Salon salon, String rule, Object value) {
        Http.Exchange read = settings.call(salon.salon(), "GET", BookingRulesFixture.SETTINGS, null, ownerNow(salon));
        long version = ((Number) body(read).get("version")).longValue();
        status(
                settings.call(
                        salon.salon(),
                        "PUT",
                        BookingRulesFixture.SETTINGS,
                        BookingRulesFixture.save(version, BookingRulesFixture.with(rule, value)),
                        ownerNow(salon)),
                200);
    }

    @Test
    void theTimesAreEveryQuarterHourInWhichTheWholeBookingFitsTheOpeningHoursAndTheirDay() {
        Map<String, Object> answer = answer(alpha, TOMORROW, alpha.nimali());

        assertThat(answer).containsEntry("date", TOMORROW).containsEntry("reason", null);
        // Open 08:00 to 20:00; the booking takes an hour with its buffer, so the last start is 19:00.
        assertThat(times(answer)).isEqualTo(quarters("08:00", "19:00"));
        Map<String, Object> first = maps(answer.get("times")).getFirst();
        assertThat(first).containsEntry("startsAt", "2086-06-16T08:00:00+05:30");
        assertThat(map(first.get("member")))
                .containsEntry("id", alpha.nimali().toString())
                .containsEntry("name", "Nimali Perera");
    }

    @Test
    void aHoldItsBufferIncludedTakesItsTimeAtOnceAndReleasingItGivesTheTimeBack() {
        Map<String, Object> hold = held(alpha, hold(alpha, "10:00"));

        List<String> during = times(answer(alpha, TOMORROW, alpha.nimali()));
        assertThat(during)
                .contains("09:00", "11:00")
                .doesNotContain("09:15", "09:30", "09:45", "10:00", "10:15", "10:30", "10:45");
        // Kasun's day is his own.
        assertThat(times(answer(alpha, TOMORROW, alpha.kasun()))).contains("10:00");

        status(act(alpha, hold, "release", alpha.owner()), 204);
        assertThat(times(answer(alpha, TOMORROW, alpha.nimali()))).isEqualTo(quarters("08:00", "19:00"));
    }

    @Test
    void aHoldThatRunsOutFreesItsTimeBeforeTheJobAndACancelledBookingOccupiesNothing() {
        held(alpha, hold(alpha, "10:00"));
        Map<String, Object> booked = confirmed(alpha, hold(alpha, "14:00"));
        assertThat(times(answer(alpha, TOMORROW, alpha.nimali()))).doesNotContain("10:00", "14:00");

        moveClock(Duration.ofMinutes(10).plusSeconds(1));
        assertThat(times(answer(alpha, TOMORROW, alpha.nimali())))
                .contains("10:00")
                .doesNotContain("14:00");

        Map<String, Object> reason = Map.of("reason", "customer_asked");
        status(call(alpha, "POST", BOOKINGS + "/" + booked.get("id") + "/cancel", reason, ownerNow(alpha)), 200);
        assertThat(times(answer(alpha, TOMORROW, alpha.nimali()))).isEqualTo(quarters("08:00", "19:00"));
    }

    @Test
    void blockedTimeAndLeaveTakeTheirTimeOut() {
        block(alpha, alpha.nimali(), TOMORROW, "12:00", "13:00");
        assertThat(times(answer(alpha, TOMORROW, alpha.nimali())))
                .contains("11:00", "13:00")
                .doesNotContain("11:15", "11:45", "12:00", "12:45");

        leave(alpha, alpha.nimali(), TOMORROW);
        Map<String, Object> away = answer(alpha, TOMORROW, alpha.nimali());
        assertThat(times(away)).isEmpty();
        assertThat(away).containsEntry("reason", "not_working");
        assertThat(times(answer(alpha, TOMORROW, alpha.kasun()))).isEqualTo(quarters("08:00", "19:00"));
    }

    @Test
    void aClosedDateIsClosedAndANewIntervalAppliesAtOnce() {
        close(alpha, TOMORROW);
        Map<String, Object> closed = answer(alpha, TOMORROW, null);
        assertThat(times(closed)).isEmpty();
        assertThat(closed).containsEntry("reason", "closed");

        saveRules(alpha, "slotIntervalMinutes", 30);
        String dayAfter = LocalDate.parse(TOMORROW).plusDays(1).toString();
        assertThat(times(answer(alpha, dayAfter, alpha.nimali())))
                .startsWith("08:00", "08:30", "09:00")
                .endsWith("18:30", "19:00")
                .hasSize(23);
    }

    @Test
    void todaysTimesStartAfterTheCurrentTimeWithNoCustomerNoticeAndTheDateMustBeTodayToAYearAhead() {
        // 08:00 now; the rules ask customers for an hour's notice, which staff do not need.
        assertThat(times(answer(alpha, TODAY, alpha.nimali()))).startsWith("08:15", "08:30");

        moveClock(Duration.ofMinutes(37));
        assertThat(times(answer(alpha, TODAY, alpha.nimali()))).startsWith("08:45");

        moveClock(Duration.ofHours(11));
        Map<String, Object> late = answer(alpha, TODAY, alpha.nimali());
        assertThat(times(late)).isEmpty();
        assertThat(late).containsEntry("reason", "fully_booked");

        String query = "serviceId=" + alpha.service() + "&date=";
        fieldErrors(availability(alpha, query + "2086-06-14", ownerNow(alpha)), "date");
        status(availability(alpha, query + "2087-06-15", ownerNow(alpha)), 200);
        fieldErrors(availability(alpha, query + "2087-06-16", ownerNow(alpha)), "date");
        fieldErrors(availability(alpha, query + "16-06-2086", ownerNow(alpha)), "date");
        fieldErrors(availability(alpha, "date=" + TOMORROW, ownerNow(alpha)), "serviceId");
        fieldErrors(
                availability(alpha, query + TOMORROW + "&memberId=x&variantId=y", ownerNow(alpha)),
                "memberId",
                "variantId");
    }

    @Test
    void withAProfessionalTheTimesAreTheirsAndOneWhoDoesNotPerformTheServiceIsRefused() {
        UUID massage = service(alpha.salon(), alpha.owner(), alpha.category(), "Head massage", 2_000, 30, 0);
        UUID ishara = member(alpha.salon(), alpha.owner(), "Ishara Fonseka", List.of(massage));
        held(alpha, hold(alpha, alpha.kasun(), TOMORROW, "09:00"));

        assertThat(takers(answer(alpha, TOMORROW, alpha.kasun())).values())
                .containsOnly(alpha.kasun().toString());
        assertThat(times(answer(alpha, TOMORROW, alpha.kasun()))).doesNotContain("09:00");

        Http.Exchange notTheirs = availability(
                alpha, "serviceId=" + alpha.service() + "&date=" + TOMORROW + "&memberId=" + ishara, alpha.owner());
        refused(notTheirs, 409, "slot_unavailable");
        refused(
                availability(
                        alpha,
                        "serviceId=" + alpha.service() + "&date=" + TOMORROW + "&memberId=" + UUID.randomUUID(),
                        alpha.owner()),
                404,
                "not_found");
        refused(
                availability(alpha, "serviceId=" + UUID.randomUUID() + "&date=" + TOMORROW, alpha.owner()),
                404,
                "not_found");
    }

    @Test
    void aRemovedProfessionalIsNotFoundAndLeavesNoPreference() {
        UUID ishara = member(alpha.salon(), alpha.owner(), "Ishara Fonseka", List.of(alpha.service()));
        assertThat(takers(answer(alpha, TOMORROW, null)).values()).containsOnly(ishara.toString());

        Http.Exchange read = settings.call(alpha.salon(), "GET", "/api/v1/team/members/" + ishara, null, alpha.owner());
        status(
                settings.call(
                        alpha.salon(),
                        "DELETE",
                        "/api/v1/team/members/" + ishara + "?version="
                                + body(read).get("version"),
                        null,
                        alpha.owner()),
                204);

        refused(
                availability(
                        alpha,
                        "serviceId=" + alpha.service() + "&date=" + TOMORROW + "&memberId=" + ishara,
                        alpha.owner()),
                404,
                "not_found");
        assertThat(takers(answer(alpha, TOMORROW, null)).values()).doesNotContain(ishara.toString());
    }

    @Test
    void noPreferenceOffersEachTimeOnceWithTheLeastBookedProfessionalAndTiesInTheTeamsOrder() {
        // Kasun Silva is listed before Nimali Perera on the Team page.
        Map<String, Object> empty = answer(alpha, TOMORROW, null);
        assertThat(times(empty)).isEqualTo(quarters("08:00", "19:00"));
        assertThat(takers(empty).values()).containsOnly(alpha.kasun().toString());

        held(alpha, hold(alpha, alpha.kasun(), TOMORROW, "15:00"));
        Map<String, String> least = takers(answer(alpha, TOMORROW, null));
        assertThat(least.keySet()).isEqualTo(new java.util.LinkedHashSet<>(quarters("08:00", "19:00")));
        assertThat(least.values()).containsOnly(alpha.nimali().toString());

        saveRules(alpha, "noPreference", "most_booked");
        Map<String, String> most = takers(answer(alpha, TOMORROW, null));
        assertThat(most.get("12:00")).isEqualTo(alpha.kasun().toString());
        assertThat(most.get("16:00")).isEqualTo(alpha.kasun().toString());
        // While Kasun is busy, the time goes to Nimali.
        assertThat(most.get("14:15")).isEqualTo(alpha.nimali().toString());
        assertThat(most.get("15:00")).isEqualTo(alpha.nimali().toString());
        assertThat(most.get("15:45")).isEqualTo(alpha.nimali().toString());
    }

    @Test
    void anEmptyAnswerSaysNobodyPerformsTheServiceOrNobodyWorksThatDay() {
        UUID massage = service(alpha.salon(), alpha.owner(), alpha.category(), "Head massage", 2_000, 30, 0);
        Http.Exchange nobody = availability(alpha, "serviceId=" + massage + "&date=" + TOMORROW, alpha.owner());
        status(nobody, 200);
        assertThat(body(nobody)).containsEntry("reason", "nobody_performs").containsEntry("times", List.of());

        leave(alpha, alpha.nimali(), TOMORROW);
        leave(alpha, alpha.kasun(), TOMORROW);
        assertThat(answer(alpha, TOMORROW, null)).containsEntry("reason", "not_working");
    }

    @Test
    void aServiceWithVariantsNeedsOneAndTakesItsTime() {
        Map<String, Object> service = new LinkedHashMap<>();
        service.put("name", "Colour");
        service.put("categoryId", alpha.category().toString());
        service.put("description", null);
        service.put("bufferMinutes", 0);
        service.put("bookableOnline", true);
        service.put(
                "variants",
                Map.of(
                        "name",
                        "Hair length",
                        "items",
                        List.of(
                                Map.of("name", "Short", "priceMinor", 400_000, "durationMinutes", 60),
                                Map.of("name", "Long", "priceMinor", 600_000, "durationMinutes", 180))));
        Http.Exchange created =
                settings.call(alpha.salon(), "POST", "/api/v1/catalogue/services", service, alpha.owner());
        status(created, 201);
        UUID colour = UUID.fromString((String) body(created).get("id"));
        String longHair = (String) maps(map(body(created).get("terms")).get("variants")).stream()
                .filter(variant -> "Long".equals(variant.get("name")))
                .findFirst()
                .orElseThrow()
                .get("id");
        member(alpha.salon(), alpha.owner(), "Ruwan Jayasuriya", List.of(colour));

        fieldErrors(availability(alpha, "serviceId=" + colour + "&date=" + TOMORROW, alpha.owner()), "variantId");
        Http.Exchange longTimes = availability(
                alpha, "serviceId=" + colour + "&variantId=" + longHair + "&date=" + TOMORROW, alpha.owner());
        status(longTimes, 200);
        assertThat(times(body(longTimes))).isEqualTo(quarters("08:00", "17:00"));
    }

    @Test
    void theOpenDaysAreTheDatesTheSalonIsOpenWithin400DaysFromToday() {
        close(alpha, TOMORROW);
        Http.Exchange week =
                call(alpha, "GET", BOOKINGS + "/open-days?from=" + TODAY + "&to=2086-06-21", null, alpha.owner());
        status(week, 200);
        assertThat(body(week))
                .containsEntry(
                        "days",
                        List.of("2086-06-15", "2086-06-17", "2086-06-18", "2086-06-19", "2086-06-20", "2086-06-21"));

        String path = BOOKINGS + "/open-days?from=";
        status(call(alpha, "GET", path + TODAY + "&to=2087-07-19", null, alpha.owner()), 200);
        fieldErrors(call(alpha, "GET", path + TODAY + "&to=2087-07-20", null, alpha.owner()), "to");
        fieldErrors(call(alpha, "GET", path + "2086-06-14&to=2086-06-20", null, alpha.owner()), "from");
        fieldErrors(call(alpha, "GET", path + TODAY + "&to=2086-06-14", null, alpha.owner()), "to");
        fieldErrors(call(alpha, "GET", BOOKINGS + "/open-days", null, alpha.owner()), "from", "to");
    }
}
