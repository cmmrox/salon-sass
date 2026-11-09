package com.salonplatform.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.testing.Http;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * F07-S02 AC-7 through the booking rules endpoints: the hold length and the "No preference" strategy are rules of a
 * version like every other, 10 minutes and {@code least_booked} when never chosen, each save a new version with the
 * change in its history, a bad value a field error, and the public read without the strategy.
 */
class HoldAndAssignRulesIntegrationTest extends BookingTestBase {

    Map<String, Object> settingsOf(Salon salon) {
        Http.Exchange read = call(salon, "GET", BookingRulesFixture.SETTINGS, null, ownerNow(salon));
        status(read, 200);
        return body(read);
    }

    Http.Exchange save(Salon salon, Map<String, Object> rules) {
        long version = ((Number) settingsOf(salon).get("version")).longValue();
        return call(
                salon, "PUT", BookingRulesFixture.SETTINGS, BookingRulesFixture.save(version, rules), ownerNow(salon));
    }

    static Map<String, Object> suggestedWith(String a, Object first, String b, Object second) {
        Map<String, Object> rules = new LinkedHashMap<>(BookingRulesFixture.suggested());
        rules.put(a, first);
        rules.put(b, second);
        return rules;
    }

    @Test
    void aVersionSavedWithoutThemHoldsForTenMinutesAndAssignsTheLeastBooked() {
        assertThat(map(settingsOf(alpha).get("rules")))
                .containsEntry("holdMinutes", 10)
                .containsEntry("noPreference", "least_booked");
        assertThat(owner.queryForMap(
                        "SELECT hold_minutes, no_preference FROM booking_rules_version WHERE tenant_id = ?",
                        alpha.salon().id()))
                .containsEntry("hold_minutes", 10)
                .containsEntry("no_preference", "least_booked");
    }

    @Test
    void aSaveOfEitherIsANewVersionWhoseHistoryNamesItAndThePublicReadLeavesOutTheStrategy() {
        Http.Exchange saved = save(alpha, suggestedWith("holdMinutes", 15, "noPreference", "most_booked"));
        status(saved, 200);
        assertThat(body(saved)).containsEntry("version", 2);
        assertThat(map(body(saved).get("rules")))
                .containsEntry("holdMinutes", 15)
                .containsEntry("noPreference", "most_booked");

        Http.Exchange history = call(alpha, "GET", BookingRulesFixture.HISTORY, null, ownerNow(alpha));
        status(history, 200);
        assertThat(maps(body(history).get("items")).getFirst())
                .containsEntry("version", 2)
                .containsEntry("changedFields", List.of("holdMinutes", "noPreference"));

        Http.Exchange published = call(alpha, "GET", BookingRulesFixture.RULES, null, null);
        status(published, 200);
        assertThat(map(body(published).get("rules")))
                .containsEntry("holdMinutes", 15)
                .doesNotContainKey("noPreference");
        assertThat(published.body()).doesNotContain("noPreference", "most_booked");

        // The first version keeps what it was saved with (decision 089).
        assertThat(owner.queryForObject(
                        "SELECT hold_minutes FROM booking_rules_version WHERE tenant_id = ? AND version = 1",
                        Integer.class,
                        alpha.salon().id()))
                .isEqualTo(10);
        // Beta's rules are its own.
        assertThat(map(settingsOf(beta).get("rules"))).containsEntry("holdMinutes", 10);
    }

    @Test
    void aSaveThatLeavesThemOutKeepsTheCurrentVersionsAndChangesNothingElse() {
        status(save(alpha, suggestedWith("holdMinutes", 20, "noPreference", "most_booked")), 200);

        refused(save(alpha, BookingRulesFixture.suggested()), 409, "invalid_state");

        Http.Exchange other = save(alpha, BookingRulesFixture.with("slotIntervalMinutes", 30));
        status(other, 200);
        assertThat(map(body(other).get("rules")))
                .containsEntry("holdMinutes", 20)
                .containsEntry("noPreference", "most_booked")
                .containsEntry("slotIntervalMinutes", 30);
        Http.Exchange history = call(alpha, "GET", BookingRulesFixture.HISTORY, null, ownerNow(alpha));
        assertThat(maps(body(history).get("items")).getFirst())
                .containsEntry("changedFields", List.of("slotIntervalMinutes"));
    }

    @Test
    void aValueNotOfferedIsAFieldErrorAndNothingIsSaved() {
        long before = count(alpha, "booking_rules_version");

        fieldErrors(
                save(alpha, suggestedWith("holdMinutes", 7, "noPreference", "even")),
                "rules.holdMinutes",
                "rules.noPreference");
        fieldErrors(save(alpha, BookingRulesFixture.with("holdMinutes", 0)), "rules.holdMinutes");
        fieldErrors(save(alpha, BookingRulesFixture.with("holdMinutes", 60)), "rules.holdMinutes");
        // Read strictly: a text is not a number, and a number is not a strategy.
        fieldErrors(save(alpha, BookingRulesFixture.with("holdMinutes", "10")), "rules.holdMinutes");
        fieldErrors(save(alpha, BookingRulesFixture.with("noPreference", 1)), "rules.noPreference");

        assertThat(count(alpha, "booking_rules_version")).isEqualTo(before);
    }
}
