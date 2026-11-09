package com.salonplatform.staff;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.testing.Http;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The guards (F06-S01 AC-7, AC-8, decision 100): when {@link UpcomingMemberBookings} reports an upcoming booking, a
 * removal or a save that stops the person performing the service answers 409 {@code in_use} and nothing changes. Here
 * an implementation that reports what this test names stands in for {@code booking}'s (F07).
 */
class UpcomingMemberBookingsIntegrationTest extends TwoSalonTeam {

    /** How many upcoming bookings each member has. */
    static final Map<UUID, Long> BOOKINGS = new ConcurrentHashMap<>();

    /** The services each member is booked for. */
    static final Map<UUID, Set<UUID>> BOOKED_SERVICES = new ConcurrentHashMap<>();

    @TestConfiguration
    static class Overrides {
        @Bean
        @Primary
        UpcomingMemberBookings reportingBookings() {
            return new UpcomingMemberBookings() {
                @Override
                public long countFor(UUID memberId) {
                    return BOOKINGS.getOrDefault(memberId, 0L);
                }

                @Override
                public Set<UUID> servicesBookedFor(UUID memberId, Set<UUID> serviceIds) {
                    Set<UUID> booked = new HashSet<>(BOOKED_SERVICES.getOrDefault(memberId, Set.of()));
                    booked.retainAll(serviceIds);
                    return booked;
                }
            };
        }
    }

    @AfterEach
    void noBookings() {
        BOOKINGS.clear();
        BOOKED_SERVICES.clear();
    }

    private static List<Map<String, Object>> problems(Map<String, Object> body) {
        return maps(body.get("errors"));
    }

    @Test
    void removingSomeoneWithUpcomingBookingsIsRefusedSayingHowManyAndNothingChanges() {
        Map<String, Object> member = added("Booked Person");
        UUID id = UUID.fromString((String) member.get("id"));
        BOOKINGS.put(id, 3L);
        String path = TeamFixture.MEMBERS + "/" + id;

        Http.Exchange refused = api.call(alpha, "DELETE", path + "?version=0", null, salonOwner);

        refused(refused, 409, "in_use");
        assertThat(problems(api.body(refused))).containsExactly(Map.of("field", "upcomingBookings", "detail", "3"));
        assertThat(read(id)).containsEntry("version", 0).containsEntry("name", "Booked Person");

        BOOKINGS.clear();
        status(api.call(alpha, "DELETE", path + "?version=0", null, salonOwner), 204);
    }

    @Test
    void aSaveThatStopsThemPerformingABookedServiceIsRefusedNamingItAndNothingChanges() {
        UUID colour = category(alpha, salonOwner, "Colour");
        Map<String, Object> keep = service(alpha, salonOwner, "Kept Service", colour, true);
        Map<String, Object> booked = service(alpha, salonOwner, "Booked Service", colour, true);
        UUID keepId = UUID.fromString((String) keep.get("id"));
        UUID bookedId = UUID.fromString((String) booked.get("id"));
        Map<String, Object> person = TeamFixture.person("Performer");
        person.put("services", TeamFixture.services(List.of(colour), List.of()));
        Http.Exchange added = api.call(alpha, "POST", TeamFixture.MEMBERS, person, salonOwner);
        status(added, 201);
        Map<String, Object> member = api.body(added);
        UUID id = UUID.fromString((String) member.get("id"));
        BOOKED_SERVICES.put(id, Set.of(bookedId));

        Map<String, Object> narrow = TeamFixture.saveOf(member);
        narrow.put("services", TeamFixture.services(List.of(), List.of(keepId)));
        Http.Exchange refused = api.call(alpha, "PUT", TeamFixture.MEMBERS + "/" + id, narrow, salonOwner);

        refused(refused, 409, "in_use");
        assertThat(problems(api.body(refused)))
                .containsExactly(Map.of("field", "services", "detail", "Booked Service"));
        assertThat(map(read(id).get("services")).get("categoryIds")).isEqualTo(List.of(colour.toString()));

        // Narrowing to the booked service is not losing it, and the save goes through.
        narrow.put("services", TeamFixture.services(List.of(), List.of(bookedId)));
        status(api.call(alpha, "PUT", TeamFixture.MEMBERS + "/" + id, narrow, salonOwner), 200);
    }

    @Test
    void aStaleSaveIsRefusedForBeingStaleBeforeBookingsAreAsked() {
        Map<String, Object> member = added("Stale First");
        UUID id = UUID.fromString((String) member.get("id"));
        Map<String, Object> first = TeamFixture.saveOf(member);
        first.put("name", "Stale Second");
        status(api.call(alpha, "PUT", TeamFixture.MEMBERS + "/" + id, first, salonOwner), 200);
        BOOKINGS.put(id, 1L);

        refused(
                api.call(alpha, "DELETE", TeamFixture.MEMBERS + "/" + id + "?version=0", null, salonOwner),
                409,
                "conflict");
    }
}
