package com.salonplatform.booking;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.salonplatform.testing.CapturedLogs;
import com.salonplatform.testing.Http;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A booking moves only as BKG-06 allows (F07-S01 AC-5, AC-7), each change recorded with who, when and why (AC-9).
 * Today is 15 June 2086, 08:00 in Colombo.
 */
class BookingStatesIntegrationTest extends BookingTestBase {

    private static final String TODAY = "2086-06-15";

    @Test
    void aHoldIsConfirmedAndConfirmingItAgainOrAnythingElseIsInvalid() {
        Map<String, Object> booking = held(alpha, hold(alpha, "10:00"));

        Http.Exchange confirmed = act(alpha, booking, "confirm", alpha.owner());
        status(confirmed, 200);
        assertThat(body(confirmed)).containsEntry("state", "confirmed").containsEntry("holdExpiresAt", null);

        Http.Exchange again = act(alpha, booking, "confirm", alpha.owner());
        refused(again, 409, "invalid_state");
        assertThat(body(again).get("detail")).isEqualTo("Only a hold can be confirmed.");
    }

    @Test
    void aHoldThatRanOutIsNotConfirmedAndItsTimeIsFreeForOthers() {
        Map<String, Object> booking = held(alpha, hold(alpha, "10:00"));
        moveClock(Duration.ofMinutes(10));

        Http.Exchange late = act(alpha, booking, "confirm", ownerNow(alpha));

        refused(late, 409, "hold_expired");
        status(place(alpha, hold(alpha, "10:00"), ownerNow(alpha)), 201);
        // After the job (or a placement) has ended it, confirming still says it ran out.
        assertThat(stateInTheDatabase(booking.get("id"))).isEqualTo("expired");
        refused(act(alpha, booking, "confirm", ownerNow(alpha)), 409, "hold_expired");
    }

    @Test
    void aHoldIsReleasedAtOnceAndIsThenNotFound() {
        Map<String, Object> booking = held(alpha, hold(alpha, "10:00"));

        Http.Exchange released = act(alpha, booking, "release", alpha.owner());

        status(released, 204);
        assertThat(released.body()).isEmpty();
        refused(act(alpha, booking, "release", alpha.owner()), 404, "not_found");
        refused(read(alpha, booking.get("id"), alpha.owner()), 404, "not_found");
        refused(act(alpha, booking, "confirm", alpha.owner()), 404, "not_found");
        status(place(alpha, hold(alpha, "10:00"), alpha.owner()), 201);
    }

    @Test
    void onlyAHoldIsReleased() {
        Map<String, Object> booking = confirmed(alpha, hold(alpha, "10:00"));

        Http.Exchange release = act(alpha, booking, "release", alpha.owner());

        refused(release, 409, "invalid_state");
        assertThat(body(release).get("detail")).isEqualTo("Only a hold can be released.");
        assertThat(stateInTheDatabase(booking.get("id"))).isEqualTo("confirmed");
    }

    @Test
    void aHoldThatRanOutIsNotFoundForReleaseNorRead() {
        Map<String, Object> booking = held(alpha, hold(alpha, "10:00"));
        moveClock(Duration.ofMinutes(10));

        refused(act(alpha, booking, "release", ownerNow(alpha)), 404, "not_found");
        refused(read(alpha, booking.get("id"), ownerNow(alpha)), 404, "not_found");
        assertThat(stateInTheDatabase(booking.get("id"))).isEqualTo("held");
    }

    @Test
    void aCustomerArrivesOnlyOnTheBookingsDateAndThenStartsTheirService() {
        Map<String, Object> tomorrow = confirmed(alpha, hold(alpha, "10:00"));
        Http.Exchange early = act(alpha, tomorrow, "arrive", alpha.owner());
        refused(early, 409, "invalid_state");
        assertThat(body(early).get("detail"))
                .isEqualTo("A customer can be marked arrived only on the day of the booking.");
        refused(act(alpha, tomorrow, "start", alpha.owner()), 409, "invalid_state");

        Map<String, Object> today = confirmed(alpha, hold(alpha, alpha.nimali(), TODAY, "10:00"));
        Http.Exchange arrived = act(alpha, today, "arrive", alpha.owner());
        status(arrived, 200);
        assertThat(body(arrived)).containsEntry("state", "arrived");
        Http.Exchange started = act(alpha, today, "start", alpha.owner());
        status(started, 200);
        assertThat(body(started)).containsEntry("state", "in_service");
        refused(act(alpha, today, "start", alpha.owner()), 409, "invalid_state");
    }

    @Test
    void aConfirmedBookingIsCancelledBeforeItStartsWithAReasonAndANote() {
        Map<String, Object> booking = confirmed(alpha, hold(alpha, "10:00"));
        fieldErrors(cancel(alpha, booking, Map.of()), "reason");
        fieldErrors(cancel(alpha, booking, Map.of("reason", "changed_mind")), "reason");
        fieldErrors(cancel(alpha, booking, Map.of("reason", "other", "note", "x".repeat(201))), "note");
        fieldErrors(cancel(alpha, booking, Map.of("reason", "other", "extra", true)), "extra");

        Http.Exchange cancelled = cancel(alpha, booking, Map.of("reason", "customer_asked", "note", "x".repeat(200)));

        status(cancelled, 200);
        assertThat(body(cancelled)).containsEntry("state", "cancelled");
        Map<String, Object> last = maps(body(read(alpha, booking.get("id"), alpha.owner()))
                        .get("history"))
                .getLast();
        assertThat(last)
                .containsEntry("from", "confirmed")
                .containsEntry("to", "cancelled")
                .containsEntry("reason", "customer_asked")
                .containsEntry("note", "x".repeat(200));
        refused(cancel(alpha, booking, Map.of("reason", "other")), 409, "invalid_state");
    }

    @Test
    void aBookingIsNotCancelledOnceItsStartTimeHasComeNorAHold() {
        Map<String, Object> soon = confirmed(alpha, hold(alpha, alpha.nimali(), TODAY, "08:30"));
        refused(cancel(alpha, held(alpha, hold(alpha, "10:00")), Map.of("reason", "other")), 409, "invalid_state");
        moveClock(Duration.ofMinutes(30));

        Http.Exchange late = cancel(alpha, soon, Map.of("reason", "salon_unable"));

        refused(late, 409, "invalid_state");
        assertThat(body(late).get("detail")).isEqualTo("A booking can be cancelled only before it starts.");
        assertThat(stateInTheDatabase(soon.get("id"))).isEqualTo("confirmed");
    }

    @Test
    void aNoShowIsMarkedFromTheStartMinuteAndUndoneTheSameDayOnly() {
        Map<String, Object> booking = confirmed(alpha, hold(alpha, alpha.nimali(), TODAY, "08:30"));
        moveClock(Duration.ofMinutes(29).plusSeconds(59));
        refused(act(alpha, booking, "no-show", ownerNow(alpha)), 409, "invalid_state");
        moveClock(Duration.ofSeconds(1));

        status(act(alpha, booking, "no-show", ownerNow(alpha)), 200);
        Http.Exchange undone = act(alpha, booking, "undo-no-show", ownerNow(alpha));
        status(undone, 200);
        assertThat(body(undone)).containsEntry("state", "confirmed");

        status(act(alpha, booking, "no-show", ownerNow(alpha)), 200);
        // 00:00 on the 16th in Colombo: the next day.
        CLOCK.set(java.time.Instant.parse("2086-06-15T18:30:00Z"));
        Http.Exchange tooLate = act(alpha, booking, "undo-no-show", ownerNow(alpha));
        refused(tooLate, 409, "invalid_state");
        assertThat(body(tooLate).get("detail")).isEqualTo("A no-show can be undone only on the day of the booking.");
        assertThat(stateInTheDatabase(booking.get("id"))).isEqualTo("no_show");
    }

    @Test
    void noEndpointSetsPendingPaymentOrCompleted() {
        Map<String, Object> booking = confirmed(alpha, hold(alpha, alpha.nimali(), TODAY, "10:00"));
        for (String action : List.of("complete", "pending-payment", "pay", "checkout")) {
            Http.Exchange answer = settings.call(
                    alpha.salon(), "POST", BOOKINGS + "/" + booking.get("id") + "/" + action, null, alpha.owner());
            assertThat(answer.status()).isIn(404, 405);
        }
        assertThat(stateInTheDatabase(booking.get("id"))).isEqualTo("confirmed");
    }

    @Test
    void everyChangeIsInTheHistoryWithWhoWhenAndWhyAndInTheAuditTrail() {
        Map<String, Object> booking = confirmed(alpha, hold(alpha, alpha.nimali(), TODAY, "10:00"));
        moveClock(Duration.ofMinutes(5));
        status(act(alpha, booking, "arrive", ownerNow(alpha)), 200);

        Map<String, Object> detail = body(read(alpha, booking.get("id"), ownerNow(alpha)));
        List<Map<String, Object>> history = maps(detail.get("history"));
        assertThat(history)
                .extracting(entry -> entry.get("from") + ">" + entry.get("to") + " at " + entry.get("at"))
                .containsExactly(
                        "null>held at 2086-06-15T08:00:00+05:30",
                        "held>confirmed at 2086-06-15T08:00:00+05:30",
                        "confirmed>arrived at 2086-06-15T08:05:00+05:30");
        assertThat(history).allSatisfy(entry -> {
            assertThat(map(entry.get("by"))).containsEntry("kind", "staff");
            assertThat((String) map(entry.get("by")).get("label")).endsWith("@example.test");
            assertThat(entry).containsEntry("reason", null).containsEntry("note", null);
        });

        List<Map<String, Object>> audit = owner.queryForList(
                "SELECT actor_kind, action, before_values::text AS before, after_values::text AS after FROM audit_entry"
                        + " WHERE tenant_id = ? AND subject_kind = 'booking_appointment' AND subject_id = ?"
                        + " ORDER BY created_at, action",
                alpha.salon().id(),
                booking.get("id"));
        assertThat(audit)
                .extracting(row -> row.get("actor_kind") + " " + row.get("action"))
                .containsExactlyInAnyOrder(
                        "staff booking.hold_placed", "staff booking.confirmed", "staff booking.arrived");
        assertThat(audit).allSatisfy(row -> {
            assertThat(String.valueOf(row.get("after"))).doesNotContain("+94771234567", "077", "Dilani");
            assertThat(String.valueOf(row.get("before"))).doesNotContain("+94771234567", "Dilani");
        });
        assertThat(audit.stream()
                        .filter(row -> "booking.arrived".equals(row.get("action")))
                        .findFirst()
                        .orElseThrow())
                .containsEntry("before", "{\"state\": \"confirmed\"}")
                .containsEntry("after", "{\"state\": \"arrived\"}");
    }

    @Test
    void releasingAndExpiringAreInTheHistoryTooBySystemForAnExpiry() {
        Map<String, Object> released = held(alpha, hold(alpha, "10:00"));
        status(act(alpha, released, "release", alpha.owner()), 204);
        Map<String, Object> ranOut = held(alpha, hold(alpha, "12:00"));
        moveClock(Duration.ofMinutes(10));
        status(place(alpha, hold(alpha, "12:00"), ownerNow(alpha)), 201);

        assertThat(historyOf(released)).containsExactly("null>held by staff", "held>released by staff");
        assertThat(historyOf(ranOut)).containsExactly("null>held by staff", "held>expired by system");
        assertThat(owner.queryForList(
                        "SELECT actor_kind || ' ' || action FROM audit_entry WHERE subject_id = ?",
                        String.class,
                        String.valueOf(ranOut.get("id"))))
                .containsExactlyInAnyOrder("staff booking.hold_placed", "system booking.hold_expired");
    }

    @Test
    void aCustomersMobileNumberNeverAppearsInALogLine() {
        try (CapturedLogs logs = CapturedLogs.ofApplication()) {
            Map<String, Object> booking = confirmed(alpha, hold(alpha, "10:00"));
            refused(place(alpha, hold(alpha, "10:15"), alpha.owner()), 409, "slot_taken");
            fieldErrors(
                    place(
                            alpha,
                            with(hold(alpha, "11:00"), "customer", customer("Dilani Fernando", "011 234 5678")),
                            alpha.owner()),
                    "customer.mobile");
            status(read(alpha, booking.get("id"), alpha.owner()), 200);
            status(
                    call(alpha, "GET", BOOKINGS + "?from=2086-06-16&to=2086-06-16&q=0771234567", null, alpha.owner()),
                    200);

            assertThat(logs.events()).isNotEmpty();
            for (ILoggingEvent event : logs.events()) {
                String line =
                        event.getFormattedMessage() + " " + event.getKeyValuePairs() + " " + event.getMDCPropertyMap();
                assertThat(line).doesNotContain("771234567", "0112345678", "112345678");
            }
        }
    }

    private Http.Exchange cancel(Salon salon, Map<String, Object> booking, Map<String, Object> body) {
        return call(
                salon,
                "POST",
                BOOKINGS + "/" + booking.get("id") + "/cancel",
                new LinkedHashMap<>(body),
                ownerNow(salon));
    }

    private List<String> historyOf(Map<String, Object> booking) {
        return owner.queryForList(
                "SELECT coalesce(from_state, 'null') || '>' || to_state || ' by ' || actor_kind FROM booking_state_change"
                        + " WHERE booking_id = ?::uuid ORDER BY position",
                String.class,
                booking.get("id"));
    }
}
