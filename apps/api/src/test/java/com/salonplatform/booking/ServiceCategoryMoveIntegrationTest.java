package com.salonplatform.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.testing.Http;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * BUG-F06-002, F06-S01 AC-8: moving a service to another category stops everyone who performs the old category whole
 * from performing it. The move is refused as a team save is, 409 {@code in_use} naming each person with an upcoming
 * booking for the service, while it would do that; the catalogue asks {@code staff} through
 * {@code catalogue.ServiceMoveBookings}, and {@code staff} asks {@code booking}.
 */
class ServiceCategoryMoveIntegrationTest extends BookingTestBase {

    private static final String SERVICES = "/api/v1/catalogue/services/";

    /** A request on another module's path, its answer held to the committed contract's operation. */
    private Http.Exchange other(Salon salon, String method, String path, String template, Object request) {
        Http.Exchange exchange = settings.call(salon.salon(), method, path, request, salon.owner());
        contract.assertMatches(method, template, exchange);
        return exchange;
    }

    private UUID newCategory(Salon salon, String name) {
        String path = "/api/v1/catalogue/categories";
        Http.Exchange added = other(salon, "POST", path, path, Map.of("name", name));
        status(added, 201);
        return UUID.fromString((String) body(added).get("id"));
    }

    /** A team member who performs these categories whole, with the salon's hours as their week. */
    private UUID performingWhole(Salon salon, String name, List<UUID> categories) {
        Map<String, Object> person = new LinkedHashMap<>();
        person.put("name", name);
        person.put(
                "services",
                Map.of("categoryIds", categories.stream().map(UUID::toString).toList(), "serviceIds", List.of()));
        Http.Exchange added = other(salon, "POST", "/api/v1/team/members", "/api/v1/team/members", person);
        status(added, 201);
        return UUID.fromString((String) body(added).get("id"));
    }

    private Map<String, Object> serviceOf(Salon salon) {
        Http.Exchange read = other(salon, "GET", SERVICES + salon.service(), "/api/v1/catalogue/services/{id}", null);
        status(read, 200);
        return body(read);
    }

    /** A save of the service as read, moved to this category. */
    private Http.Exchange move(Salon salon, UUID category) {
        Map<String, Object> service = serviceOf(salon);
        Map<String, Object> terms = map(service.get("terms"));
        Map<String, Object> save = new LinkedHashMap<>();
        save.put("name", service.get("name"));
        save.put("categoryId", category.toString());
        save.put("description", service.get("description"));
        save.put("priceMinor", terms.get("priceMinor"));
        save.put("durationMinutes", terms.get("durationMinutes"));
        save.put("bufferMinutes", terms.get("bufferMinutes"));
        save.put("bookableOnline", service.get("bookableOnline"));
        save.put("version", service.get("version"));
        return other(salon, "PUT", SERVICES + salon.service(), "/api/v1/catalogue/services/{id}", save);
    }

    @Test
    void aMoveThatWouldStopSomeoneBookedPerformingTheServiceIsRefusedNamingThemAndNothingChanges() {
        UUID treatments = newCategory(alpha, "Treatments");
        UUID nimal = performingWhole(alpha, "Nimal Fonseka", List.of(alpha.category()));
        UUID both = performingWhole(alpha, "Ruwan Both", List.of(alpha.category(), treatments));
        confirmed(alpha, hold(alpha, nimal, TOMORROW, "10:00"));
        confirmed(alpha, hold(alpha, both, TOMORROW, "11:00"));
        // Kasun performs the service on its own, so a move leaves him performing it.
        confirmed(alpha, hold(alpha, alpha.kasun(), TOMORROW, "12:00"));

        Http.Exchange refused = move(alpha, treatments);

        refused(refused, 409, "in_use");
        assertThat(maps(body(refused).get("errors")))
                .containsExactly(Map.of("field", "categoryId", "detail", "Nimal Fonseka"));
        assertThat(serviceOf(alpha))
                .containsEntry("categoryId", alpha.category().toString())
                .containsEntry("version", 0);
    }

    @Test
    void aMoveThatStopsNobodyBookedGoesThrough() {
        UUID treatments = newCategory(alpha, "Treatments");
        UUID nimal = performingWhole(alpha, "Nimal Fonseka", List.of(alpha.category()));
        Map<String, Object> booking = confirmed(alpha, hold(alpha, nimal, TOMORROW, "10:00"));
        refused(move(alpha, treatments), 409, "in_use");

        Http.Exchange cancelled = call(
                alpha,
                "POST",
                BOOKINGS + "/" + booking.get("id") + "/cancel",
                Map.of("reason", "customer_asked"),
                alpha.owner());
        status(cancelled, 200);

        Http.Exchange moved = move(alpha, treatments);
        status(moved, 200);
        assertThat(body(moved)).containsEntry("categoryId", treatments.toString());
    }

    @Test
    void anotherSalonsBookingsAreNotAskedAbout() {
        UUID betaTreatments = newCategory(beta, "Treatments");
        UUID alphaNimal = performingWhole(alpha, "Nimal Fonseka", List.of(alpha.category()));
        confirmed(alpha, hold(alpha, alphaNimal, TOMORROW, "10:00"));
        performingWhole(beta, "Nimal Fonseka", List.of(beta.category()));

        status(move(beta, betaTreatments), 200);
    }
}
