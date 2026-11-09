package com.salonplatform.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.testing.Http;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The questions {@code staff} and {@code catalogue} declared about bookings now answer from real ones (F07-S01 AC-13),
 * proved through those modules' own endpoints: the delete and removal guards, what a closure, an hours change, a
 * person's week, leave and blocked time list, and "Most booked". Holds and pending payments count as upcoming;
 * cancelled and no-show bookings count for nothing.
 */
class BookingContractsIntegrationTest extends BookingTestBase {

    private static final String TODAY = "2086-06-15";

    private Http.Exchange deleteService(UUID service) {
        return settings.call(
                alpha.salon(), "DELETE", "/api/v1/catalogue/services/" + service + "?version=0", null, ownerNow(alpha));
    }

    private Http.Exchange removeMember(UUID member) {
        Http.Exchange read =
                settings.call(alpha.salon(), "GET", "/api/v1/team/members/" + member, null, ownerNow(alpha));
        return settings.call(
                alpha.salon(),
                "DELETE",
                "/api/v1/team/members/" + member + "?version=" + body(read).get("version"),
                null,
                ownerNow(alpha));
    }

    private Http.Exchange cancel(Map<String, Object> booking) {
        return call(
                alpha,
                "POST",
                BOOKINGS + "/" + booking.get("id") + "/cancel",
                Map.of("reason", "other"),
                ownerNow(alpha));
    }

    @Test
    void aServiceWithAnUpcomingHoldOrBookingIsNotDeletedAndOneWithOnlyCancelledOnesIs() {
        UUID massage = service(alpha.salon(), alpha.owner(), alpha.category(), "Head massage", 2_000, 30, 0);
        UUID ishara = member(alpha.salon(), alpha.owner(), "Ishara Fonseka", List.of(massage));
        Map<String, Object> booking =
                held(alpha, with(hold(alpha, ishara, TOMORROW, "10:00"), "serviceId", massage.toString()));

        refused(deleteService(massage), 409, "in_use");
        status(act(alpha, booking, "confirm", alpha.owner()), 200);
        refused(deleteService(massage), 409, "in_use");
        status(cancel(booking), 200);

        status(deleteService(massage), 204);
    }

    @Test
    void aServiceWhoseOnlyBookingsArePastOrNoShowsIsDeleted() {
        UUID massage = service(alpha.salon(), alpha.owner(), alpha.category(), "Head massage", 2_000, 30, 0);
        UUID ishara = member(alpha.salon(), alpha.owner(), "Ishara Fonseka", List.of(massage));
        Map<String, Object> missed =
                confirmed(alpha, with(hold(alpha, ishara, TODAY, "08:30"), "serviceId", massage.toString()));
        Map<String, Object> past =
                confirmed(alpha, with(hold(alpha, ishara, TODAY, "09:00"), "serviceId", massage.toString()));
        moveClock(Duration.ofMinutes(35));
        status(act(alpha, missed, "no-show", ownerNow(alpha)), 200);
        refused(deleteService(massage), 409, "in_use");
        // 09:00 to 09:30 has passed at 09:31.
        moveClock(Duration.ofMinutes(56));
        assertThat(stateInTheDatabase(past.get("id"))).isEqualTo("confirmed");

        status(deleteService(massage), 204);
    }

    @Test
    void aMemberWithAnUpcomingBookingIsNotRemovedNorTakenOffItsServiceAndIsOnceItIsCancelled() {
        Map<String, Object> booking = confirmed(alpha, hold(alpha, alpha.kasun(), TOMORROW, "10:00"));

        Http.Exchange removal = removeMember(alpha.kasun());
        refused(removal, 409, "in_use");
        assertThat(maps(body(removal).get("errors")))
                .containsExactly(Map.of("field", "upcomingBookings", "detail", "1"));

        Map<String, Object> read =
                body(settings.call(alpha.salon(), "GET", "/api/v1/team/members/" + alpha.kasun(), null, alpha.owner()));
        Map<String, Object> save = new LinkedHashMap<>();
        for (String field :
                List.of("name", "title", "highlights", "bio", "shownOnline", "signInAccountId", "version")) {
            save.put(field, read.get(field));
        }
        save.put("services", Map.of("categoryIds", List.of(), "serviceIds", List.of()));
        Http.Exchange takenOff =
                settings.call(alpha.salon(), "PUT", "/api/v1/team/members/" + alpha.kasun(), save, alpha.owner());
        refused(takenOff, 409, "in_use");
        assertThat(maps(body(takenOff).get("errors")))
                .containsExactly(Map.of("field", "services", "detail", "Cut and finish"));

        status(cancel(booking), 200);
        status(removeMember(alpha.kasun()), 204);
    }

    @Test
    void aHoldThatRanOutIsNotUpcoming() {
        held(alpha, hold(alpha, alpha.kasun(), TOMORROW, "10:00"));
        moveClock(Duration.ofMinutes(10));

        status(removeMember(alpha.kasun()), 204);
    }

    @Test
    void aClosureAndAnHoursChangeListTheBookingsTheyAffect() {
        Map<String, Object> morning = held(alpha, hold(alpha, "09:00"));
        Map<String, Object> afternoon = confirmed(alpha, hold(alpha, alpha.kasun(), TOMORROW, "15:00"));
        Map<String, Object> cancelled = confirmed(alpha, hold(alpha, alpha.kasun(), TOMORROW, "17:00"));
        status(cancel(cancelled), 200);
        assertThat(morning).isNotNull();

        Http.Exchange impact = settings.call(
                alpha.salon(), "GET", "/api/v1/salon/closures/impact?date=" + TOMORROW, null, alpha.owner());
        status(impact, 200);
        assertThat(maps(body(impact).get("bookings")))
                .extracting(booking ->
                        booking.get("time") + " " + booking.get("customerName") + " " + booking.get("serviceName"))
                .containsExactly("09:00 Dilani Fernando Cut and finish", "15:00 Dilani Fernando Cut and finish");

        Http.Exchange hours =
                settings.call(alpha.salon(), "GET", "/api/v1/salon/opening-hours/settings", null, alpha.owner());
        Http.Exchange shorter = settings.call(
                alpha.salon(),
                "PUT",
                "/api/v1/salon/opening-hours/settings",
                Map.of("version", body(hours).get("version"), "weekly", openEveryDay("08:00", "14:00")),
                alpha.owner());
        status(shorter, 200);
        assertThat(maps(body(shorter).get("outsideHours")))
                .extracting(booking -> booking.get("time") + " " + booking.get("customerName"))
                .containsExactly("15:00 Dilani Fernando");
        assertThat(afternoon).isNotNull();
    }

    @Test
    void aPersonsWeekLeaveAndBlockedTimeListTheirBookingsAndCountThem() {
        confirmed(alpha, hold(alpha, alpha.kasun(), TOMORROW, "10:00"));
        String schedule = "/api/v1/team/members/" + alpha.kasun() + "/schedule";

        Http.Exchange leave = settings.call(
                alpha.salon(),
                "GET",
                schedule + "/leave/affected?firstDay=" + TOMORROW + "&lastDay=" + TOMORROW,
                null,
                alpha.owner());
        status(leave, 200);
        assertThat(maps(body(leave).get("affected")))
                .extracting(booking -> booking.get("time"))
                .containsExactly("10:00");

        Http.Exchange overlapping = settings.call(
                alpha.salon(),
                "GET",
                schedule + "/blocked-time/affected?date=" + TOMORROW + "&starts=10:45&finishes=12:00",
                null,
                alpha.owner());
        assertThat(maps(body(overlapping).get("affected"))).hasSize(1);
        Http.Exchange touching = settings.call(
                alpha.salon(),
                "GET",
                schedule + "/blocked-time/affected?date=" + TOMORROW + "&starts=11:00&finishes=12:00",
                null,
                alpha.owner());
        assertThat(maps(body(touching).get("affected"))).isEmpty();

        Map<String, Object> block = new LinkedHashMap<>();
        block.put("date", TOMORROW);
        block.put("starts", "10:00");
        block.put("finishes", "10:30");
        block.put("label", "Training");
        Http.Exchange blocked = settings.call(alpha.salon(), "POST", schedule + "/blocked-time", block, alpha.owner());
        status(blocked, 201);
        assertThat(maps(body(blocked).get("affected"))).hasSize(1);
        Http.Exchange read = settings.call(alpha.salon(), "GET", schedule, null, alpha.owner());
        assertThat(maps(body(read).get("blockedTime")).getFirst()).containsEntry("bookingsToResolve", 1);

        DayOfWeek off = LocalDate.parse(TOMORROW).getDayOfWeek();
        List<Map<String, Object>> week = new ArrayList<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("day", day.name().toLowerCase(Locale.ROOT));
            entry.put("working", day != off);
            entry.put("starts", day != off ? "08:00" : null);
            entry.put("finishes", day != off ? "20:00" : null);
            entry.put("breaks", List.of());
            week.add(entry);
        }
        Http.Exchange saved = settings.call(
                alpha.salon(),
                "PUT",
                schedule + "/weekly",
                Map.of("version", body(read).get("version"), "weekly", week),
                alpha.owner());
        status(saved, 200);
        assertThat(maps(body(saved).get("affected")))
                .extracting(booking -> booking.get("date"))
                .containsExactly(TOMORROW);
    }

    @Test
    void mostBookedRanksServicesByTheirBookingsAndHoldsAndCancellationsCountForNothing() {
        UUID massage = service(alpha.salon(), alpha.owner(), alpha.category(), "Head massage", 2_000, 30, 0);
        UUID ishara = member(alpha.salon(), alpha.owner(), "Ishara Fonseka", List.of(massage, alpha.service()));
        confirmed(alpha, with(hold(alpha, ishara, TOMORROW, "09:00"), "serviceId", massage.toString()));
        confirmed(alpha, with(hold(alpha, ishara, TOMORROW, "10:00"), "serviceId", massage.toString()));
        confirmed(alpha, hold(alpha, "10:00"));
        held(alpha, hold(alpha, "12:00"));
        held(alpha, hold(alpha, alpha.kasun(), TOMORROW, "12:00"));
        status(cancel(confirmed(alpha, hold(alpha, alpha.kasun(), TOMORROW, "14:00"))), 200);
        // Tomorrow at 15:00: every one of them has started, so each counts by its state alone.
        moveClock(Duration.ofHours(31));

        assertThat(mostBooked()).containsExactly(massage.toString(), alpha.service().toString());
    }

    @Test
    void mostBookedCountsTheLastNinetyDaysUpToNowAndNotBookingsStillToCome() {
        // Decisions 092 and 095 (c): the last 90 days, which end at now by the application's clock (BUG-F07-005).
        UUID massage = service(alpha.salon(), alpha.owner(), alpha.category(), "Head massage", 2_000, 30, 0);
        UUID ishara = member(alpha.salon(), alpha.owner(), "Ishara Fonseka", List.of(massage, alpha.service()));
        String inFiveDays = LocalDate.parse(TODAY).plusDays(5).toString();
        confirmed(alpha, hold(alpha, alpha.nimali(), TODAY, "09:00"));
        confirmed(alpha, with(hold(alpha, ishara, inFiveDays, "10:00"), "serviceId", massage.toString()));
        confirmed(alpha, with(hold(alpha, ishara, inFiveDays, "11:00"), "serviceId", massage.toString()));
        // 10:30: the 09:00 booking has taken place; the two in five days are still to come.
        moveClock(Duration.ofMinutes(150));

        assertThat(mostBooked()).containsExactly(alpha.service().toString());
    }

    /** Switches the Popular card to "Most booked" and reads the services it ranks, as the menu shows them. */
    private List<Object> mostBooked() {
        Http.Exchange setting =
                settings.call(alpha.salon(), "GET", "/api/v1/catalogue/popular-setting", null, ownerNow(alpha));
        Http.Exchange switched = settings.call(
                alpha.salon(),
                "PUT",
                "/api/v1/catalogue/popular-setting",
                Map.of("mode", "most_booked", "version", body(setting).get("version")),
                ownerNow(alpha));
        status(switched, 200);
        Http.Exchange menu = settings.call(alpha.salon(), "GET", "/api/v1/catalogue/menu", null, null);
        status(menu, 200);
        return ((List<?>) body(menu).get("popular"))
                .stream()
                .map(service -> service instanceof Map<?, ?> each ? each.get("id") : service)
                .toList();
    }

    @Test
    void anotherSalonsBookingsNeverReachThisSalonsGuardsOrLists() {
        confirmed(beta, hold(beta, beta.kasun(), TOMORROW, "10:00"));
        Http.Exchange impact = settings.call(
                alpha.salon(), "GET", "/api/v1/salon/closures/impact?date=" + TOMORROW, null, alpha.owner());
        assertThat(maps(body(impact).get("bookings"))).isEmpty();
        status(removeMember(alpha.kasun()), 204);
    }
}
