package com.salonplatform.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.salonplatform.booking.domain.BookingChannel;
import com.salonplatform.booking.entity.Booking;
import com.salonplatform.booking.repository.BookingRepository;
import com.salonplatform.catalogue.SegmentKind;
import com.salonplatform.catalogue.ServiceSegment;
import com.salonplatform.identity.domain.StaffRole;
import com.salonplatform.testing.Http;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Two live holds or bookings of one professional never overlap (F07-S01 AC-3, AC-4), and the database is what refuses
 * them: here the endpoint's answer, and the constraint on its own when a row is written through the domain with no
 * check before it. What occupies nothing: cancelled and no-show bookings, released holds, and holds that have run out,
 * even before the job has ended them.
 */
class BookingOverlapIntegrationTest extends BookingTestBase {

    @Autowired
    BookingRepository bookings;

    @Test
    void aHoldOverlappingAnotherOfTheSameProfessionalIsRefusedAsTaken() {
        held(alpha, hold(alpha, "10:00"));

        refused(place(alpha, hold(alpha, "10:30"), alpha.owner()), 409, "slot_taken");
        refused(place(alpha, hold(alpha, "09:05"), alpha.owner()), 409, "slot_taken");
        refused(place(alpha, hold(alpha, "10:00"), alpha.owner()), 409, "slot_taken");
        assertThat(count(alpha, "booking_appointment")).isEqualTo(1);
    }

    @Test
    void bookingsThatOnlyTouchAreBothKeptAndAnotherProfessionalsTimeIsTheirOwn() {
        held(alpha, hold(alpha, "10:00"));

        status(place(alpha, hold(alpha, "11:00"), alpha.owner()), 201);
        status(place(alpha, hold(alpha, "09:00"), alpha.owner()), 201);
        status(place(alpha, hold(alpha, alpha.kasun(), TOMORROW, "10:30"), alpha.owner()), 201);
        // The buffer occupies the professional too: 11:55 is inside 11:00's buffer, from 11:45 to 12:00.
        refused(place(alpha, hold(alpha, "11:55"), alpha.owner()), 409, "slot_taken");
    }

    @Test
    void aCancelledBookingOccupiesNothing() {
        Map<String, Object> booking = confirmed(alpha, hold(alpha, "10:00"));
        Http.Exchange cancel = call(
                alpha,
                "POST",
                BOOKINGS + "/" + booking.get("id") + "/cancel",
                Map.of("reason", "customer_asked"),
                alpha.owner());
        status(cancel, 200);

        status(place(alpha, hold(alpha, "10:30"), alpha.owner()), 201);
    }

    @Test
    void aReleasedHoldOccupiesNothing() {
        Map<String, Object> booking = held(alpha, hold(alpha, "10:00"));
        status(act(alpha, booking, "release", alpha.owner()), 204);

        status(place(alpha, hold(alpha, "10:00"), alpha.owner()), 201);
    }

    @Test
    void aNoShowOccupiesNothing() {
        Map<String, Object> booking = confirmed(alpha, hold(alpha, alpha.nimali(), "2086-06-15", "08:30"));
        moveClock(Duration.ofMinutes(35));
        status(act(alpha, booking, "no-show", ownerNow(alpha)), 200);

        status(place(alpha, hold(alpha, alpha.nimali(), "2086-06-15", "08:45"), ownerNow(alpha)), 201);
    }

    @Test
    void aHoldThatRanOutBlocksNothingBeforeTheJobAndIsEndedByThePlacementThatNeedsItsTime() {
        Map<String, Object> first = held(alpha, hold(alpha, "10:00"));
        moveClock(Duration.ofMinutes(10));
        assertThat(stateInTheDatabase(first.get("id"))).isEqualTo("held");

        Http.Exchange second = place(alpha, hold(alpha, "10:15"), ownerNow(alpha));

        status(second, 201);
        assertThat(stateInTheDatabase(first.get("id"))).isEqualTo("expired");
        assertThat(owner.queryForList(
                        "SELECT from_state, to_state, actor_kind FROM booking_state_change WHERE booking_id = ?::uuid"
                                + " ORDER BY position",
                        first.get("id")))
                .extracting(row -> row.get("from_state") + ">" + row.get("to_state") + " by " + row.get("actor_kind"))
                .containsExactly("null>held by staff", "held>expired by system");
    }

    @Test
    void aHoldIsLiveUntilTheSecondBeforeItsExpiry() {
        held(alpha, hold(alpha, "10:00"));
        moveClock(Duration.ofMinutes(10).minusSeconds(1));

        refused(place(alpha, hold(alpha, "10:00"), ownerNow(alpha)), 409, "slot_taken");
    }

    @Test
    void theExclusionConstraintAloneRefusesAnOverlapWrittenWithNoCheckBeforeIt() {
        Instant ten = Instant.parse("2086-06-16T04:30:00Z");
        inSalon(alpha, () -> bookings.saveAndFlush(direct(alpha, alpha.kasun(), ten, "A2B3C4", ACTIVE_HOUR)));

        assertThatThrownBy(() -> inSalon(
                        alpha,
                        () -> bookings.saveAndFlush(
                                direct(alpha, alpha.kasun(), ten.plus(Duration.ofMinutes(30)), "D5E6F7", ACTIVE_HOUR))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(refused -> assertThat(sqlState(refused)).isEqualTo("23P01"))
                .hasMessageContaining("booking_appointment_no_overlap");
        // The same time for beta's professional of the same id is beta's own: the constraint is per salon.
        inSalon(beta, () -> bookings.saveAndFlush(direct(beta, alpha.kasun(), ten, "A2B3C4", ACTIVE_HOUR)));
    }

    @Test
    void aProcessingSegmentOccupiesNoProfessionalSoAnotherBookingFitsInsideIt() {
        Instant two = Instant.parse("2086-06-16T08:30:00Z"); // 14:00 in Colombo.
        List<ServiceSegment> colour = List.of(
                new ServiceSegment(SegmentKind.ACTIVE, 30),
                new ServiceSegment(SegmentKind.PROCESSING, 40),
                new ServiceSegment(SegmentKind.ACTIVE, 20),
                new ServiceSegment(SegmentKind.BUFFER, 10));
        inSalon(alpha, () -> bookings.saveAndFlush(direct(alpha, alpha.kasun(), two, "H8J9K2", colour)));

        // 14:30 to 15:10 is the processing time: a 40-minute booking fits, touching both active parts.
        inSalon(
                alpha,
                () -> bookings.saveAndFlush(direct(
                        alpha,
                        alpha.kasun(),
                        two.plus(Duration.ofMinutes(30)),
                        "M3N4P5",
                        List.of(new ServiceSegment(SegmentKind.ACTIVE, 40)))));
        // Through the endpoint, 15:00 to 16:00 overlaps the second active part (15:10 to 15:40).
        refused(place(alpha, hold(alpha, alpha.kasun(), TOMORROW, "15:00"), alpha.owner()), 409, "slot_taken");
        assertThatThrownBy(() -> inSalon(
                        alpha,
                        () -> bookings.saveAndFlush(direct(
                                alpha,
                                alpha.kasun(),
                                two.plus(Duration.ofMinutes(20)),
                                "Q6R7S8",
                                List.of(new ServiceSegment(SegmentKind.ACTIVE, 15))))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aProfessionalSeesTheTakenAnswerTooForAHoldPlacedByTheFrontDesk() {
        held(alpha, hold(alpha, "10:00"));
        Http.Exchange receptionist = place(alpha, hold(alpha, "10:15"), token(alpha, StaffRole.RECEPTIONIST));

        refused(receptionist, 409, "slot_taken");
        assertThat(body(receptionist).get("detail")).isEqualTo("This time has just been taken. Choose another time.");
    }

    private static final List<ServiceSegment> ACTIVE_HOUR = List.of(new ServiceSegment(SegmentKind.ACTIVE, 60));

    /** A hold written through the domain, with nothing checked before it. */
    private Booking direct(
            Salon salon, UUID member, Instant startsAt, String reference, List<ServiceSegment> segments) {
        return Booking.hold(
                salons.defaultBranch(salon.salon()),
                reference,
                new Booking.Terms(
                        member,
                        "Kasun Silva",
                        salon.service(),
                        UUID.randomUUID(),
                        "Colour",
                        null,
                        null,
                        UUID.randomUUID(),
                        segments,
                        500_000,
                        "LKR"),
                new Booking.Request(
                        "Dilani Fernando",
                        "+94771234567",
                        BookingChannel.FRONT_DESK,
                        UUID.randomUUID(),
                        "direct",
                        UUID.randomUUID()),
                startsAt,
                CLOCK.instant());
    }

    private static String sqlState(Throwable refused) {
        for (Throwable cause = refused; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
