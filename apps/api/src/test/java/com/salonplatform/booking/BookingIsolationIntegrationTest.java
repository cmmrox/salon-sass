package com.salonplatform.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.catalogue.ServiceBookingCounts;
import com.salonplatform.catalogue.UpcomingServiceBookings;
import com.salonplatform.staff.BookingsOnDate;
import com.salonplatform.staff.MemberBookingsAffected;
import com.salonplatform.staff.MemberPeriod;
import com.salonplatform.staff.UpcomingMemberBookings;
import com.salonplatform.testing.Http;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A request can only ever see its own salon (F07-S01 AC-14): another salon's booking, professional and service answer
 * as unknown ones through every endpoint, its bookings never reach this salon's lists, searches or contracts, and two
 * salons may hold the same reference. The tables on their own are proved by
 * {@link BookingRowLevelSecurityAloneIntegrationTest} and {@link BookingHibernateFilterAloneIntegrationTest}.
 */
class BookingIsolationIntegrationTest extends BookingTestBase {

    @Autowired
    UpcomingMemberBookings upcomingMembers;

    @Autowired
    UpcomingServiceBookings upcomingServices;

    @Autowired
    ServiceBookingCounts counts;

    @Autowired
    BookingsOnDate onDate;

    @Autowired
    MemberBookingsAffected affected;

    @Test
    void anotherSalonsBookingAnswersAsAnUnknownOneThroughEveryEndpointAndIsNotChanged() {
        Map<String, Object> betas = confirmed(beta, hold(beta, beta.nimali(), "2086-06-15", "10:00"));
        Map<String, Object> unknown = Map.of("id", UUID.randomUUID().toString());

        for (Map<String, Object> booking : List.of(betas, unknown)) {
            Http.Exchange read = read(alpha, booking.get("id"), alpha.owner());
            refused(read, 404, "not_found");
            for (String action : List.of("confirm", "release", "arrive", "start", "no-show", "undo-no-show")) {
                refused(act(alpha, booking, action, alpha.owner()), 404, "not_found");
            }
            refused(
                    call(
                            alpha,
                            "POST",
                            BOOKINGS + "/" + booking.get("id") + "/cancel",
                            Map.of("reason", "other"),
                            alpha.owner()),
                    404,
                    "not_found");
        }
        refused(read(alpha, "not-a-uuid", alpha.owner()), 404, "not_found");
        assertThat(stateInTheDatabase(betas.get("id"))).isEqualTo("confirmed");
        assertThat(count(beta, "booking_state_change")).isEqualTo(2);
    }

    @Test
    void aHoldCannotNameAnotherSalonsProfessionalOrService() {
        refused(place(alpha, hold(alpha, beta.nimali(), TOMORROW, "10:00"), alpha.owner()), 404, "not_found");
        refused(
                place(
                        alpha,
                        with(hold(alpha, "10:00"), "serviceId", beta.service().toString()),
                        alpha.owner()),
                404,
                "not_found");
        assertThat(count(alpha, "booking_appointment") + count(beta, "booking_appointment"))
                .isZero();
    }

    @Test
    void twoSalonsHoldTheSameReferenceAndEachSearchFindsOnlyItsOwn() {
        RANDOM.next("K7M4QX");
        Map<String, Object> alphas = held(alpha, hold(alpha, "10:00"));
        RANDOM.next("K7M4QX");
        Map<String, Object> betas = held(beta, hold(beta, "10:00"));
        assertThat(alphas.get("reference")).isEqualTo(betas.get("reference"));

        for (String q : List.of("K7M-4QX", "Dilani", "0771234567")) {
            Http.Exchange found = call(
                    alpha, "GET", BOOKINGS + "?from=" + TOMORROW + "&to=" + TOMORROW + "&q=" + q, null, alpha.owner());
            status(found, 200);
            assertThat(maps(body(found).get("items")))
                    .extracting(item -> item.get("id"))
                    .containsExactly(alphas.get("id"));
        }
    }

    @Test
    void anotherSalonsBookingsOfTheSameTimeTakeNothingFromThisSalon() {
        held(beta, hold(beta, "10:00"));
        status(place(alpha, hold(alpha, "10:00"), alpha.owner()), 201);
    }

    @Test
    void theContractsAnswerForTheRequestsSalonOnly() {
        Map<String, Object> betas = confirmed(beta, hold(beta, beta.kasun(), TOMORROW, "10:00"));
        assertThat(betas).isNotNull();
        LocalDate tomorrow = LocalDate.parse(TOMORROW);
        UUID alphasBranch = salons.defaultBranch(alpha.salon());
        UUID betasBranch = salons.defaultBranch(beta.salon());

        inSalon(alpha, () -> {
            assertThat(upcomingMembers.countFor(beta.kasun())).isZero();
            assertThat(upcomingMembers.servicesBookedFor(beta.kasun(), Set.of(beta.service())))
                    .isEmpty();
            assertThat(upcomingServices.anyFor(beta.service())).isFalse();
            assertThat(counts.countsSince(Instant.EPOCH)).isEmpty();
            assertThat(onDate.on(alphasBranch, tomorrow)).isEmpty();
            assertThat(onDate.on(betasBranch, tomorrow)).isEmpty();
            assertThat(affected.inPeriods(beta.kasun(), Set.of(MemberPeriod.days(tomorrow, tomorrow))))
                    .isEmpty();
            assertThat(affected.outsideDate(beta.kasun(), tomorrow, List.of())).isEmpty();
            return null;
        });
        inSalon(beta, () -> {
            assertThat(upcomingMembers.countFor(beta.kasun())).isEqualTo(1);
            assertThat(upcomingServices.anyFor(beta.service())).isTrue();
            assertThat(counts.countsSince(Instant.EPOCH)).containsExactly(Map.entry(beta.service(), 1L));
            assertThat(onDate.on(betasBranch, tomorrow)).hasSize(1);
            assertThat(affected.outsideDate(beta.kasun(), tomorrow, List.of())).hasSize(1);
            return null;
        });
    }

    @Test
    void theContractsNeedASalon() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> upcomingMembers.countFor(alpha.kasun()))
                .isInstanceOf(com.salonplatform.shared.tenancy.TenantNotFoundException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> upcomingServices.anyFor(alpha.service()))
                .isInstanceOf(com.salonplatform.shared.tenancy.TenantNotFoundException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> counts.countsSince(Instant.EPOCH))
                .isInstanceOf(com.salonplatform.shared.tenancy.TenantNotFoundException.class);
    }
}
