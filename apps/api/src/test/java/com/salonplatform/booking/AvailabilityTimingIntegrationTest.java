package com.salonplatform.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.testing.Http;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * F07-S02 AC-11 (NFR-03), on the developer's machine: with 20 eligible professionals, each with two breaks, a block and
 * 15 bookings on the day, the "No preference" availability read answers under 500 ms at the 95th percentile over 200
 * sequential calls through the endpoint. It prints its percentiles. This is not a load test: one caller, one after
 * another; the release suite measures it under load when the owner asks.
 */
class AvailabilityTimingIntegrationTest extends BookingTestBase {

    static final int PROFESSIONALS = 20;
    static final int BOOKINGS_EACH = 15;
    static final int CALLS = 200;
    static final int WARM_UP = 10;

    /** 15 starts for a 20-minute service, around the breaks (12:00, 15:30) and the block (17:00). */
    static final List<String> STARTS = List.of(
            "08:00", "08:30", "09:00", "09:30", "10:00", "10:30", "11:00", "11:30", "12:30", "13:00", "13:30", "14:00",
            "14:30", "15:00", "16:00");

    @Test
    void theNoPreferenceReadOfABusyTeamAnswersUnder500MillisecondsAtThe95thPercentile() {
        UUID quick = service(alpha.salon(), alpha.owner(), alpha.category(), "Fringe trim", 1_000, 20, 0);
        List<UUID> team = new ArrayList<>(List.of(alpha.nimali(), alpha.kasun()));
        for (int i = team.size(); i < PROFESSIONALS; i++) {
            team.add(member(
                    alpha.salon(), alpha.owner(), "Professional " + (char) ('A' + i), List.of(alpha.service(), quick)));
        }
        giveServices(alpha.nimali(), List.of(alpha.service(), quick));
        giveServices(alpha.kasun(), List.of(alpha.service(), quick));
        for (UUID member : team) {
            weekWithTwoBreaks(member);
            block(member);
            for (String start : STARTS) {
                status(
                        place(
                                alpha,
                                with(hold(alpha, member, TOMORROW, start), "serviceId", quick.toString()),
                                ownerNow(alpha)),
                        201);
            }
        }
        assertThat(count(alpha, "booking_appointment")).isEqualTo((long) PROFESSIONALS * BOOKINGS_EACH);

        String path = BOOKINGS + "/availability?serviceId=" + alpha.service() + "&date=" + TOMORROW;
        String token = ownerNow(alpha);
        Map<String, Object> answer = null;
        for (int i = 0; i < WARM_UP; i++) {
            Http.Exchange read = call(alpha, "GET", path, null, token);
            status(read, 200);
            answer = body(read);
        }
        // Every hour-long time left lies after the block: 17:30, 17:45 ... 19:00.
        assertThat(maps(answer.get("times")).stream().map(time -> time.get("time")))
                .containsExactly("17:30", "17:45", "18:00", "18:15", "18:30", "18:45", "19:00");

        long[] millis = new long[CALLS];
        for (int i = 0; i < CALLS; i++) {
            long started = System.nanoTime();
            Http.Exchange read = http().sendOnHost(
                            "GET", alpha.salon().host(port()), path, Map.of("Authorization", "Bearer " + token), null);
            millis[i] = (System.nanoTime() - started) / 1_000_000;
            status(read, 200);
        }
        Arrays.sort(millis);
        long p50 = percentile(millis, 50);
        long p95 = percentile(millis, 95);
        long p99 = percentile(millis, 99);
        System.out.printf(
                Locale.ROOT,
                "AC-11 availability, %d professionals x %d bookings, %d sequential calls: p50 %d ms, p95 %d ms,"
                        + " p99 %d ms, max %d ms%n",
                PROFESSIONALS,
                BOOKINGS_EACH,
                CALLS,
                p50,
                p95,
                p99,
                millis[CALLS - 1]);
        assertThat(p95).as("p95 in ms").isLessThan(500);
    }

    /** The nearest-rank percentile of sorted values. */
    static long percentile(long[] sorted, int percent) {
        int rank = (int) Math.ceil(percent / 100.0 * sorted.length);
        return sorted[Math.max(0, rank - 1)];
    }

    void giveServices(UUID member, List<UUID> services) {
        Http.Exchange read =
                settings.call(alpha.salon(), "GET", "/api/v1/team/members/" + member, null, ownerNow(alpha));
        status(read, 200);
        Map<String, Object> save = new LinkedHashMap<>();
        Map<String, Object> current = body(read);
        for (String field :
                List.of("name", "title", "highlights", "bio", "shownOnline", "signInAccountId", "version")) {
            save.put(field, current.get(field));
        }
        save.put(
                "services",
                Map.of(
                        "categoryIds",
                        List.of(),
                        "serviceIds",
                        services.stream().map(UUID::toString).toList()));
        Http.Exchange saved =
                settings.call(alpha.salon(), "PUT", "/api/v1/team/members/" + member, save, ownerNow(alpha));
        status(saved, 200);
    }

    void weekWithTwoBreaks(UUID member) {
        String schedule = "/api/v1/team/members/" + member + "/schedule";
        Http.Exchange read = settings.call(alpha.salon(), "GET", schedule, null, ownerNow(alpha));
        status(read, 200);
        List<Map<String, Object>> week = new ArrayList<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("day", day.name().toLowerCase(Locale.ROOT));
            entry.put("working", true);
            entry.put("starts", "08:00");
            entry.put("finishes", "20:00");
            entry.put(
                    "breaks",
                    List.of(
                            Map.of("starts", "12:00", "finishes", "12:30"),
                            Map.of("starts", "15:30", "finishes", "16:00")));
            week.add(entry);
        }
        Http.Exchange saved = settings.call(
                alpha.salon(),
                "PUT",
                schedule + "/weekly",
                Map.of("version", body(read).get("version"), "weekly", week),
                ownerNow(alpha));
        status(saved, 200);
    }

    void block(UUID member) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("date", LocalDate.parse(TOMORROW).toString());
        block.put("starts", "17:00");
        block.put("finishes", "17:30");
        block.put("label", "Training");
        status(
                settings.call(
                        alpha.salon(),
                        "POST",
                        "/api/v1/team/members/" + member + "/schedule/blocked-time",
                        block,
                        ownerNow(alpha)),
                201);
    }
}
