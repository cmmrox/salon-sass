package com.salonplatform.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.catalogue.inprocess.UpcomingServiceBookings;
import com.salonplatform.shared.persistence.TransactionLocks;
import com.salonplatform.staff.inprocess.UpcomingMemberBookings;
import com.salonplatform.testing.Http;
import com.salonplatform.testing.TestPostgres;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * What must happen once, whatever arrives at the same moment (F07-S01 AC-3, AC-7, AC-13; NFR-05). Each test fires its
 * requests at once and asserts exactly one winner. The guard races are made deterministic: the test itself holds the
 * guard's transaction lock while both requests start, waits until both have passed their checks before any
 * transaction and are waiting on the lock, then lets them go, so that the order the window allowed is the one tried.
 */
class BookingConcurrencyIntegrationTest extends BookingTestBase {

    static final int AT_ONCE = 10;

    @Test
    void ofTenSimultaneousHoldsForOverlappingTimesOfOneProfessionalExactlyOneIsPlaced() throws Exception {
        List<Callable<Http.Exchange>> requests = new ArrayList<>();
        for (int i = 0; i < AT_ONCE; i++) {
            String time = String.format("10:%02d", i * 5);
            requests.add(() -> place(alpha, hold(alpha, time), alpha.owner()));
        }

        List<Http.Exchange> answers = atOnce(requests);

        assertThat(answers.stream().filter(answer -> answer.status() == 201)).hasSize(1);
        assertThat(answers.stream().filter(answer -> answer.status() != 201))
                .hasSize(AT_ONCE - 1)
                .allSatisfy(answer -> refused(answer, 409, "slot_taken"));
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM booking_appointment WHERE tenant_id = ? AND member_id = ?",
                        Long.class,
                        alpha.salon().id(),
                        alpha.nimali()))
                .isEqualTo(1);
    }

    @Test
    void simultaneousHoldsAtOneTimeForDifferentProfessionalsAllSucceed() throws Exception {
        List<UUID> team = new ArrayList<>(List.of(alpha.nimali(), alpha.kasun()));
        String[] names = {"Sanduni", "Tharindu", "Ishara", "Chamari", "Dinesh", "Malsha", "Ruwan", "Kavindi"};
        for (String name : names) {
            team.add(member(alpha.salon(), alpha.owner(), name + " Perera", List.of(alpha.service())));
        }
        List<Callable<Http.Exchange>> requests = new ArrayList<>();
        for (UUID member : team) {
            requests.add(() -> place(alpha, hold(alpha, member, TOMORROW, "10:00"), alpha.owner()));
        }

        List<Http.Exchange> answers = atOnce(requests);

        assertThat(answers).hasSize(AT_ONCE).allSatisfy(answer -> status(answer, 201));
    }

    @Test
    void ofTheSameRequestSentTwiceAtOnceWithOneKeyOneBookingIsPlacedAndBothAnswerIt() throws Exception {
        String key = UUID.randomUUID().toString();
        List<Http.Exchange> answers = atOnce(List.of(
                () -> send(alpha, "POST", BOOKINGS, hold(alpha, "10:00"), alpha.owner(), key),
                () -> send(alpha, "POST", BOOKINGS, hold(alpha, "10:00"), alpha.owner(), key)));

        assertThat(answers).allSatisfy(answer -> status(answer, 201));
        assertThat(body(answers.get(0)).get("id"))
                .isEqualTo(body(answers.get(1)).get("id"));
        assertThat(count(alpha, "booking_appointment")).isEqualTo(1);
    }

    @Test
    void ofTwoChangesToOneBookingAtOnceOneAppliesAndTheOtherIsJudgedAgainstItsResult() throws Exception {
        Map<String, Object> booking = confirmed(alpha, hold(alpha, alpha.nimali(), "2086-06-15", "10:00"));

        List<Http.Exchange> answers = atOnce(List.of(
                () -> act(alpha, booking, "arrive", alpha.owner()),
                () -> call(
                        alpha,
                        "POST",
                        BOOKINGS + "/" + booking.get("id") + "/cancel",
                        Map.of("reason", "customer_asked"),
                        alpha.owner())));

        assertThat(answers.stream().filter(answer -> answer.status() == 200)).hasSize(1);
        Http.Exchange loser = answers.stream()
                .filter(answer -> answer.status() != 200)
                .findFirst()
                .orElseThrow();
        refused(loser, 409, "invalid_state");
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM booking_state_change WHERE booking_id = ?::uuid",
                        Long.class,
                        booking.get("id")))
                .isEqualTo(3);
    }

    @Test
    void ofTwoConfirmationsOfOneHoldAtOnceOneConfirmsAndTheOtherFindsItConfirmed() throws Exception {
        Map<String, Object> booking = held(alpha, hold(alpha, "10:00"));

        List<Http.Exchange> answers = atOnce(List.of(
                () -> act(alpha, booking, "confirm", alpha.owner()),
                () -> act(alpha, booking, "confirm", alpha.owner())));

        assertThat(answers.stream().filter(answer -> answer.status() == 200)).hasSize(1);
        assertThat(answers.stream().filter(answer -> answer.status() == 409)).hasSize(1);
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM outbox_message WHERE tenant_id = ? AND event_type = 'booking.BookingConfirmed.v1'",
                        Long.class,
                        alpha.salon().id()))
                .isEqualTo(1);
    }

    @Test
    void aNoShowCannotBeUndoneOnceItsTimeHasBeenBookedAndStaysANoShow() {
        Map<String, Object> missed = confirmed(alpha, hold(alpha, alpha.nimali(), "2086-06-15", "08:30"));
        moveClock(Duration.ofMinutes(35));
        status(act(alpha, missed, "no-show", ownerNow(alpha)), 200);
        status(place(alpha, hold(alpha, alpha.nimali(), "2086-06-15", "09:00"), ownerNow(alpha)), 201);

        Http.Exchange undo = act(alpha, missed, "undo-no-show", ownerNow(alpha));

        refused(undo, 409, "slot_taken");
        assertThat(stateInTheDatabase(missed.get("id"))).isEqualTo("no_show");
    }

    @Test
    void ofAServiceDeleteAndAHoldForItRacingNeverBothSucceed() throws Exception {
        UUID service = service(alpha.salon(), alpha.owner(), alpha.category(), "Head massage", 2_000, 30, 0);
        UUID member = member(alpha.salon(), alpha.owner(), "Ishara Fonseka", List.of(service));
        Map<String, Object> massage = with(hold(alpha, member, TOMORROW, "10:00"), "serviceId", service.toString());

        List<Http.Exchange> answers = racing(
                TransactionLocks.key(
                        UpcomingServiceBookings.GUARD_LOCK, alpha.salon().id(), service),
                () -> place(alpha, massage, alpha.owner()),
                () -> settings.call(
                        alpha.salon(),
                        "DELETE",
                        "/api/v1/catalogue/services/" + service + "?version=0",
                        null,
                        alpha.owner()));

        Http.Exchange placed = answers.get(0);
        Http.Exchange deleted = answers.get(1);
        boolean holdWon = placed.status() == 201;
        if (holdWon) {
            refused(deleted, 409, "in_use");
        } else {
            status(deleted, 204);
            refused(placed, 404, "not_found");
        }
        boolean serviceDeleted = owner.queryForObject(
                "SELECT deleted_at IS NOT NULL FROM catalogue_service WHERE id = ?", Boolean.class, service);
        long liveBookings = owner.queryForObject(
                "SELECT count(*) FROM booking_appointment WHERE service_id = ? AND state = 'held'",
                Long.class,
                service);
        assertThat(serviceDeleted).isNotEqualTo(liveBookings == 1);
    }

    @Test
    void ofAMemberRemovalAndAHoldForThemRacingNeverBothSucceed() throws Exception {
        UUID member = member(alpha.salon(), alpha.owner(), "Chamari Atapattu", List.of(alpha.service()));
        Http.Exchange read = settings.call(alpha.salon(), "GET", "/api/v1/team/members/" + member, null, alpha.owner());
        Object version = body(read).get("version");

        List<Http.Exchange> answers = racing(
                TransactionLocks.key(
                        UpcomingMemberBookings.GUARD_LOCK, alpha.salon().id(), member),
                () -> place(alpha, hold(alpha, member, TOMORROW, "10:00"), alpha.owner()),
                () -> settings.call(
                        alpha.salon(),
                        "DELETE",
                        "/api/v1/team/members/" + member + "?version=" + version,
                        null,
                        alpha.owner()));

        Http.Exchange placed = answers.get(0);
        Http.Exchange removed = answers.get(1);
        if (placed.status() == 201) {
            refused(removed, 409, "in_use");
        } else {
            status(removed, 204);
            refused(placed, 404, "not_found");
        }
        boolean memberRemoved = owner.queryForObject(
                "SELECT removed_at IS NOT NULL FROM staff_team_member WHERE id = ?", Boolean.class, member);
        long liveBookings = owner.queryForObject(
                "SELECT count(*) FROM booking_appointment WHERE member_id = ? AND state = 'held'", Long.class, member);
        assertThat(memberRemoved).isNotEqualTo(liveBookings == 1);
    }

    @Test
    void ofTakingAServiceFromAPersonAndAHoldForItRacingNeverBothSucceed() throws Exception {
        UUID member = member(alpha.salon(), alpha.owner(), "Malsha Gunawardena", List.of(alpha.service()));
        Map<String, Object> read =
                body(settings.call(alpha.salon(), "GET", "/api/v1/team/members/" + member, null, alpha.owner()));
        Map<String, Object> save = new java.util.LinkedHashMap<>();
        for (String field :
                List.of("name", "title", "highlights", "bio", "shownOnline", "signInAccountId", "version")) {
            save.put(field, read.get(field));
        }
        save.put("services", Map.of("categoryIds", List.of(), "serviceIds", List.of()));

        List<Http.Exchange> answers = racing(
                TransactionLocks.key(
                        UpcomingMemberBookings.GUARD_LOCK, alpha.salon().id(), member),
                () -> place(alpha, hold(alpha, member, TOMORROW, "10:00"), alpha.owner()),
                () -> settings.call(alpha.salon(), "PUT", "/api/v1/team/members/" + member, save, alpha.owner()));

        Http.Exchange placed = answers.get(0);
        Http.Exchange saved = answers.get(1);
        if (placed.status() == 201) {
            refused(saved, 409, "in_use");
        } else {
            status(saved, 200);
            refused(placed, 409, "slot_unavailable");
        }
    }

    /**
     * Holds the lock, starts both requests, waits until both are waiting for it (each has passed every check made
     * before its transaction), then releases it. Their answers, in the order given.
     */
    private List<Http.Exchange> racing(String lock, Callable<Http.Exchange> hold, Callable<Http.Exchange> change)
            throws Exception {
        try (Connection holder = DriverManager.getConnection(
                        TestPostgres.shared().getJdbcUrl(),
                        TestPostgres.shared().getUsername(),
                        TestPostgres.shared().getPassword());
                ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            holder.setAutoCommit(false);
            long key = advisoryKey(holder, lock);
            try (PreparedStatement take = holder.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                take.setLong(1, key);
                take.execute();
            }
            Future<Http.Exchange> first = threads.submit(hold);
            Future<Http.Exchange> second = threads.submit(change);
            Instant until = Instant.now().plusSeconds(20);
            while (waitersOn(holder, key) < 2) {
                if (Instant.now().isAfter(until)) {
                    throw new AssertionError("both requests never waited on the guard lock");
                }
                Thread.sleep(20);
            }
            holder.commit();
            return List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        }
    }

    private static long advisoryKey(Connection connection, String lock) throws Exception {
        try (PreparedStatement hash = connection.prepareStatement("SELECT hashtextextended(?, 0)")) {
            hash.setString(1, lock);
            try (ResultSet row = hash.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static int waitersOn(Connection connection, long key) throws Exception {
        try (PreparedStatement waiting =
                connection.prepareStatement("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted"
                        + " AND classid::bigint = ((? >> 32) & 4294967295) AND objid::bigint = (? & 4294967295)")) {
            waiting.setLong(1, key);
            waiting.setLong(2, key);
            try (ResultSet row = waiting.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        }
    }
}
