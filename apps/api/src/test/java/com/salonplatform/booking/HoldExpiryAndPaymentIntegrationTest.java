package com.salonplatform.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.salonplatform.booking.inprocess.BookingCheckout;
import com.salonplatform.booking.inprocess.BookingPayments;
import com.salonplatform.shared.jobs.TenantJob;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The hold expiry job (F07-S01 AC-6) and the in-process contracts F08 and F10 will call (AC-8), proved through the
 * domain: the job runs here as the scheduler runs it, for one salon in a transaction with that salon set.
 */
class HoldExpiryAndPaymentIntegrationTest extends BookingTestBase {

    private static final String TODAY = "2086-06-15";

    @Autowired
    List<TenantJob> jobs;

    @Autowired
    BookingPayments payments;

    @Autowired
    BookingCheckout checkout;

    private TenantJob expiryJob() {
        return jobs.stream()
                .filter(job -> job.name().equals("booking-hold-expiry"))
                .findFirst()
                .orElseThrow();
    }

    private void runJobFor(Salon salon) {
        inSalon(salon, () -> {
            expiryJob().runFor(TenantContext.require(), CLOCK.instant());
            return null;
        });
    }

    @Test
    void theJobRunsEveryMinute() {
        assertThat(expiryJob().every()).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void theJobEndsTheSalonsRunOutHoldsAsTheSystemAndLeavesAnotherSalonsAndLiveOnesAlone() {
        Map<String, Object> ranOut = held(alpha, hold(alpha, "10:00"));
        Map<String, Object> betas = held(beta, hold(beta, "10:00"));
        moveClock(Duration.ofMinutes(5));
        Map<String, Object> stillLive = held(alpha, hold(alpha, "12:00"));
        moveClock(Duration.ofMinutes(5));

        runJobFor(alpha);

        assertThat(stateInTheDatabase(ranOut.get("id"))).isEqualTo("expired");
        assertThat(stateInTheDatabase(stillLive.get("id"))).isEqualTo("held");
        assertThat(stateInTheDatabase(betas.get("id"))).isEqualTo("held");
        assertThat(owner.queryForMap(
                        "SELECT from_state, to_state, actor_kind, actor_id, changed_at FROM booking_state_change"
                                + " WHERE booking_id = ?::uuid AND position = 2",
                        ranOut.get("id")))
                .containsEntry("from_state", "held")
                .containsEntry("to_state", "expired")
                .containsEntry("actor_kind", "system")
                .containsEntry("actor_id", null);
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM audit_entry WHERE subject_id = ? AND action = 'booking.hold_expired'"
                                + " AND actor_kind = 'system'",
                        Long.class,
                        String.valueOf(ranOut.get("id"))))
                .isEqualTo(1);

        runJobFor(alpha);
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM booking_state_change WHERE booking_id = ?::uuid",
                        Long.class,
                        ranOut.get("id")))
                .isEqualTo(2);
    }

    @Test
    void aHoldWaitingForItsPaymentIsNeverEndedByTheJob() {
        Map<String, Object> paying = held(alpha, hold(alpha, "10:00"));
        UUID id = UUID.fromString((String) paying.get("id"));
        inSalon(alpha, () -> {
            payments.begin(id, null);
            return null;
        });
        moveClock(Duration.ofHours(2));

        runJobFor(alpha);

        assertThat(stateInTheDatabase(id)).isEqualTo("pending_payment");
        Map<String, Object> read = body(read(alpha, id, ownerNow(alpha)));
        assertThat(map(read.get("booking")))
                .containsEntry("state", "pending_payment")
                .containsEntry("holdExpiresAt", null);
        refused(place(alpha, hold(alpha, "10:00"), ownerNow(alpha)), 409, "slot_taken");
    }

    @Test
    void aPaymentConfirmsItsHoldAndWritesTheConfirmedEventInTheSameTransaction() {
        UUID id = UUID.fromString((String) held(alpha, hold(alpha, "10:00")).get("id"));
        inSalon(alpha, () -> {
            payments.begin(id, alpha.kasun());
            payments.settle(id, null);
            return null;
        });

        assertThat(stateInTheDatabase(id)).isEqualTo("confirmed");
        Map<String, Object> event = owner.queryForMap(
                "SELECT kind, channel, payload::text AS payload FROM outbox_message WHERE tenant_id = ?"
                        + " AND event_type = 'booking.BookingConfirmed.v1'",
                alpha.salon().id());
        assertThat(event).containsEntry("kind", "event").containsEntry("channel", "event");
        assertThat((String) event.get("payload"))
                .contains(
                        id.toString(),
                        alpha.nimali().toString(),
                        alpha.service().toString())
                .doesNotContain("Dilani", "771234567");
        assertThat(owner.queryForList(
                        "SELECT coalesce(from_state || '>', '') || to_state FROM booking_state_change WHERE booking_id = ?"
                                + " ORDER BY position",
                        String.class,
                        id))
                .containsExactly("held", "held>pending_payment", "pending_payment>confirmed")
                .hasSize(3);
    }

    @Test
    void aConfirmationThatRollsBackLeavesNoEventAndNoChange() {
        UUID id = UUID.fromString((String) held(alpha, hold(alpha, "10:00")).get("id"));

        assertThatThrownBy(() -> inSalon(alpha, () -> {
                    payments.begin(id, null);
                    payments.settle(id, null);
                    throw new IllegalStateException("the payment's own record failed");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(stateInTheDatabase(id)).isEqualTo("held");
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM outbox_message WHERE tenant_id = ? AND kind = 'event'",
                        Long.class,
                        alpha.salon().id()))
                .isZero();
    }

    @Test
    void aPaymentThatDoesNotGoThroughMakesItAHoldAgainWithItsExpiry() {
        Map<String, Object> placed = held(alpha, hold(alpha, "10:00"));
        UUID id = UUID.fromString((String) placed.get("id"));
        inSalon(alpha, () -> {
            payments.begin(id, null);
            payments.abandon(id, null);
            return null;
        });

        Map<String, Object> read = map(body(read(alpha, id, alpha.owner())).get("booking"));
        assertThat(read).containsEntry("state", "held").containsEntry("holdExpiresAt", placed.get("holdExpiresAt"));
    }

    @Test
    void confirmingThroughTheEndpointWritesTheConfirmedEventToo() {
        confirmed(alpha, hold(alpha, "10:00"));
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM outbox_message WHERE tenant_id = ? AND event_type = 'booking.BookingConfirmed.v1'",
                        Long.class,
                        alpha.salon().id()))
                .isEqualTo(1);
    }

    @Test
    void aHoldThatRanOutCannotWaitForAPaymentAndOnlyAHoldCan() {
        UUID ranOut = UUID.fromString((String) held(alpha, hold(alpha, "10:00")).get("id"));
        UUID booked =
                UUID.fromString((String) confirmed(alpha, hold(alpha, "12:00")).get("id"));
        moveClock(Duration.ofMinutes(10));

        assertThatThrownBy(() -> inSalon(alpha, () -> {
                    payments.begin(ranOut, null);
                    return null;
                }))
                .isInstanceOfSatisfying(
                        ApplicationProblemException.class,
                        refused -> assertThat(refused.code()).isEqualTo(ErrorCode.HOLD_EXPIRED));
        assertThatThrownBy(() -> inSalon(alpha, () -> {
                    payments.begin(booked, null);
                    return null;
                }))
                .isInstanceOfSatisfying(
                        ApplicationProblemException.class,
                        refused -> assertThat(refused.code()).isEqualTo(ErrorCode.INVALID_STATE));
    }

    @Test
    void checkoutCompletesAnArrivedOrInServiceBookingAndNothingElse() {
        Map<String, Object> arrived = confirmed(alpha, hold(alpha, alpha.nimali(), TODAY, "10:00"));
        status(act(alpha, arrived, "arrive", alpha.owner()), 200);
        Map<String, Object> inService = confirmed(alpha, hold(alpha, alpha.kasun(), TODAY, "10:00"));
        status(act(alpha, inService, "arrive", alpha.owner()), 200);
        status(act(alpha, inService, "start", alpha.owner()), 200);
        Map<String, Object> confirmed = confirmed(alpha, hold(alpha, "10:00"));

        inSalon(alpha, () -> {
            checkout.complete(UUID.fromString((String) arrived.get("id")), null);
            checkout.complete(UUID.fromString((String) inService.get("id")), null);
            return null;
        });

        assertThat(stateInTheDatabase(arrived.get("id"))).isEqualTo("completed");
        assertThat(stateInTheDatabase(inService.get("id"))).isEqualTo("completed");
        assertThatThrownBy(() -> inSalon(alpha, () -> {
                    checkout.complete(UUID.fromString((String) confirmed.get("id")), null);
                    return null;
                }))
                .isInstanceOfSatisfying(
                        ApplicationProblemException.class,
                        refused -> assertThat(refused.code()).isEqualTo(ErrorCode.INVALID_STATE));
        // A completed booking still occupies its time.
        refused(place(alpha, hold(alpha, alpha.nimali(), TODAY, "10:30"), alpha.owner()), 409, "slot_taken");
    }

    @Test
    void theContractsRefuseToRunOutsideTheCallersTransactionAndAnotherSalonsBookingIsNotFound() {
        UUID alphas = UUID.fromString((String) held(alpha, hold(alpha, "10:00")).get("id"));
        assertThatIllegalStateException()
                .isThrownBy(() -> TenantContext.callAs(salons.asCurrent(alpha.salon()), () -> {
                    payments.begin(alphas, null);
                    return null;
                }));
        assertThatThrownBy(() -> inSalon(beta, () -> {
                    payments.begin(alphas, null);
                    return null;
                }))
                .isInstanceOf(com.salonplatform.shared.web.NotFoundException.class);
        assertThat(stateInTheDatabase(alphas)).isEqualTo("held");
    }
}
