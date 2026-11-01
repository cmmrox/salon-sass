package com.salonplatform.catalogue;

import static com.salonplatform.catalogue.CatalogueFixture.MENU;
import static com.salonplatform.catalogue.CatalogueFixture.POPULAR_SETTING;
import static com.salonplatform.catalogue.CatalogueFixture.SERVICES;
import static com.salonplatform.catalogue.CatalogueFixture.save;
import static com.salonplatform.catalogue.CatalogueFixture.service;
import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.testing.Http;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Popular (F05-S02 AC-5, AC-6, CAT-06): at most six chosen, a seventh {@code limit_reached}, of two choices of the
 * sixth at once exactly one kept, no terms version for a choice; the setting saved at once with its version; the
 * menu's list in category then name order, among services bookable online. "Most booked" ranks by
 * {@link ServiceBookingCounts}, which an implementation here reports for the services a test names, standing in for
 * {@code booking}'s (F07).
 */
class CataloguePopularIntegrationTest extends TwoSalonCatalogue {

    /** The bookings each service has, set per test; empty means none, as before F07. */
    static final Map<UUID, Long> BOOKED = new ConcurrentHashMap<>();

    /** Every moment the catalogue asked the counts from. */
    static final List<Instant> ASKED = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class Counts {
        @Bean
        @Primary
        ServiceBookingCounts reportedCounts() {
            return from -> {
                ASKED.add(from);
                return Map.copyOf(BOOKED);
            };
        }
    }

    @AfterEach
    void noBookings() {
        BOOKED.clear();
        ASKED.clear();
    }

    private Map<String, Object> created(UUID category, String name) {
        return api.created(alpha, manager, service(category, name, 2500, 45, 0));
    }

    private Http.Exchange choose(Map<String, Object> service, boolean popular) {
        return api.call(
                alpha,
                "PUT",
                SERVICES + "/" + service.get("id") + "/popular",
                Map.of("popular", popular, "version", service.get("version")),
                manager);
    }

    private Map<String, Object> chosen(Map<String, Object> service) {
        Http.Exchange exchange = choose(service, true);
        assertThat(exchange.status()).withFailMessage("%s", exchange.body()).isEqualTo(200);
        return api.body(exchange);
    }

    private Map<String, Object> setting() {
        return api.body(api.call(alpha, "GET", POPULAR_SETTING, null, manager));
    }

    private Http.Exchange switchTo(String mode, Object version) {
        return api.call(alpha, "PUT", POPULAR_SETTING, Map.of("mode", mode, "version", version), manager);
    }

    private List<Object> menuPopular() {
        return List.copyOf(
                (List<?>) api.body(api.call(alpha, "GET", MENU, null, null)).get("popular"));
    }

    private static UUID id(Map<String, Object> service) {
        return UUID.fromString((String) service.get("id"));
    }

    @Test
    void choosingIsNotATermsChangeAndASeventhIsRefused() {
        Map<String, Object> first = created(colour, "Balayage");
        Map<String, Object> chosenFirst = chosen(first);

        assertThat(chosenFirst).containsEntry("popular", true);
        assertThat(((Number) chosenFirst.get("version")).longValue())
                .isEqualTo(((Number) first.get("version")).longValue() + 1);
        assertThat(terms(chosenFirst)).containsEntry("versionId", terms(first).get("versionId"));
        refused(choose(chosenFirst, true), 409, "invalid_state");
        refused(choose(first, false), 409, "conflict");
        for (int i = 2; i <= 6; i++) {
            chosen(created(nails, "Nails " + i));
        }
        Map<String, Object> seventh = created(nails, "Nails 7");
        List<Map<String, Object>> before = snapshot();

        refused(choose(seventh, true), 409, "limit_reached");

        assertThat(snapshot()).isEqualTo(before);
        // Unchoosing one frees a place.
        assertThat(choose(chosenFirst, false).status()).isEqualTo(200);
        assertThat(choose(seventh, true).status()).isEqualTo(200);
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM audit_entry WHERE tenant_id = ? AND action ="
                                + " 'catalogue.service_popular_changed'",
                        Long.class,
                        alpha.id()))
                .isEqualTo(8);
    }

    @Test
    void ofTwoChoicesOfTheSixthAtOnceExactlyOneIsKept() {
        for (int i = 1; i <= 5; i++) {
            chosen(created(nails, "Nails " + i));
        }
        Map<String, Object> one = created(colour, "Balayage");
        Map<String, Object> two = created(colour, "Highlights");

        List<Integer> statuses = CatalogueFixture.together(
                () -> choose(one, true).status(), () -> choose(two, true).status());

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(count("SELECT count(*) FROM catalogue_service WHERE tenant_id = ? AND popular"))
                .isEqualTo(6);
    }

    @Test
    void theMenusPopularIsTheChosenServicesBookableOnlineInCategoryThenNameOrder() {
        Map<String, Object> polish = chosen(created(nails, "Gel polish"));
        Map<String, Object> acrylic = chosen(created(nails, "acrylic set"));
        Map<String, Object> balayage = chosen(created(colour, "Balayage"));
        Map<String, Object> hidden = chosen(created(colour, "Hidden one"));
        created(colour, "Not chosen");
        assertThat(api.call(
                                alpha,
                                "PUT",
                                SERVICES + "/" + hidden.get("id") + "/online",
                                Map.of("bookableOnline", false, "version", hidden.get("version")),
                                manager)
                        .status())
                .isEqualTo(200);
        Map<String, Object> deleted = chosen(created(colour, "Deleted one"));
        assertThat(api.call(
                                alpha,
                                "DELETE",
                                SERVICES + "/" + deleted.get("id") + "?version=" + deleted.get("version"),
                                null,
                                manager)
                        .status())
                .isEqualTo(204);

        // Colour comes before Nails in the salon's order; names within a category regardless of case.
        List<Object> expected = List.of(balayage.get("id"), acrylic.get("id"), polish.get("id"));
        assertThat(menuPopular()).isEqualTo(expected);
        assertThat(setting())
                .containsEntry("mode", "chosen")
                .containsEntry("version", 0)
                .containsEntry("popular", expected);
        // A deleted service is chosen by no one; the hidden one still counts toward six.
        assertThat(owner.queryForObject(
                        "SELECT popular FROM catalogue_service WHERE id = ?::uuid", Boolean.class, deleted.get("id")))
                .isFalse();
        assertThat(count("SELECT count(*) FROM catalogue_service WHERE tenant_id = ? AND popular"))
                .isEqualTo(4);
        assertThat(ASKED).isEmpty();
    }

    @Test
    void theSettingIsSavedAtOnceWithItsVersionAndAudited() {
        assertThat(setting()).containsEntry("mode", "chosen").containsEntry("version", 0);

        Map<String, Object> switched = api.body(switchTo("most_booked", 0));
        assertThat(switched).containsEntry("mode", "most_booked").containsEntry("version", 1);
        assertThat(setting()).containsEntry("mode", "most_booked").containsEntry("version", 1);
        refused(switchTo("chosen", 0), 409, "conflict");
        refused(switchTo("most_booked", 1), 409, "invalid_state");
        fieldErrors(switchTo("popular", 1), "mode");
        fieldErrors(api.call(alpha, "PUT", POPULAR_SETTING, Map.of("mode", "chosen"), manager), "version");
        assertThat(api.body(switchTo("chosen", 1))).containsEntry("version", 2);

        assertThat(owner.queryForList(
                        "SELECT before_values->>'mode' || '>' || (after_values->>'mode') FROM audit_entry WHERE"
                                + " tenant_id = ? AND action = 'catalogue.popular_setting_changed' ORDER BY created_at",
                        String.class,
                        alpha.id()))
                .containsExactly("chosen>most_booked", "most_booked>chosen");
        // Beta's setting is its own.
        String betasOwner = settings.signedIn(beta, com.salonplatform.identity.domain.StaffRole.OWNER)
                .token();
        assertThat(api.body(api.call(beta, "GET", POPULAR_SETTING, null, betasOwner)))
                .containsEntry("mode", "chosen")
                .containsEntry("version", 0);
    }

    @Test
    void mostBookedWithNoBookingsShowsTheChosenServices() {
        Map<String, Object> balayage = chosen(created(colour, "Balayage"));
        created(colour, "Highlights");
        switchTo("most_booked", 0);

        assertThat(menuPopular()).containsExactly(balayage.get("id"));
        assertThat(setting()).containsEntry("popular", List.of(balayage.get("id")));
    }

    @Test
    void mostBookedRanksTheLastNinetyDaysAmongServicesBookableOnline() {
        Map<String, Object> chosenOne = chosen(created(colour, "Chosen one"));
        Map<String, Object> hidden = created(colour, "Hidden");
        Map<String, Object> deleted = created(colour, "Deleted");
        api.call(
                alpha,
                "PUT",
                SERVICES + "/" + hidden.get("id") + "/online",
                Map.of("bookableOnline", false, "version", hidden.get("version")),
                manager);
        api.call(
                alpha,
                "DELETE",
                SERVICES + "/" + deleted.get("id") + "?version=" + deleted.get("version"),
                null,
                manager);
        List<Map<String, Object>> booked = new java.util.ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            booked.add(created(nails, "Booked " + i));
        }
        // Booked 3 most, then 7; ties keep the menu's order (category, then name); Booked 8 has none.
        long[] counts = {5, 9, 20, 5, 5, 5, 12, 0};
        for (int i = 0; i < 8; i++) {
            BOOKED.put(id(booked.get(i)), counts[i]);
        }
        BOOKED.put(id(hidden), 100L);
        BOOKED.put(id(deleted), 100L);
        BOOKED.put(UUID.randomUUID(), 50L);
        switchTo("most_booked", 0);
        Instant before = clock.instant();

        List<Object> popular = menuPopular();

        Instant after = clock.instant();
        assertThat(popular)
                .containsExactly(
                        booked.get(2).get("id"),
                        booked.get(6).get("id"),
                        booked.get(1).get("id"),
                        booked.get(0).get("id"),
                        booked.get(3).get("id"),
                        booked.get(4).get("id"))
                .doesNotContain(chosenOne.get("id"));
        assertThat(ASKED).isNotEmpty();
        assertThat(ASKED.getLast()).isBetween(before.minus(Duration.ofDays(90)), after.minus(Duration.ofDays(90)));
        assertThat(setting().get("popular")).isEqualTo(popular);
    }

    @Test
    void choosingAndTheSettingNeedTheirFields() {
        Map<String, Object> service = created(colour, "Balayage");
        fieldErrors(
                api.call(
                        alpha,
                        "PUT",
                        SERVICES + "/" + service.get("id") + "/popular",
                        Map.of("version", service.get("version")),
                        manager),
                "popular");
        fieldErrors(
                api.call(
                        alpha,
                        "PUT",
                        SERVICES + "/" + service.get("id") + "/popular",
                        Map.of("popular", true),
                        manager),
                "version");
        // A save of the service keeps its choice: popular is not one of its terms or fields.
        Map<String, Object> chosenOne = chosen(service);
        Map<String, Object> saved = api.body(put(chosenOne, save(chosenOne, "priceMinor", 300_000)));
        assertThat(saved).containsEntry("popular", true);
    }
}
