package com.salonplatform.staff;

import static com.salonplatform.staff.TeamFixture.dateChanges;
import static com.salonplatform.staff.TeamFixture.notWorking;
import static com.salonplatform.staff.TeamFixture.schedule;
import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.testing.Http;
import com.salonplatform.testing.MovableClock;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Dates follow the salon's time zone (F06-S02 AC-10): {@code Asia/Colombo} is UTC+05:30, so these instants are one local
 * date in the salon and another in UTC.
 */
// Its movable clock makes a context of its own, closed after this class to free its pools.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TeamScheduleClockIntegrationTest extends TwoSalonTeam {

    static final MovableClock CLOCK = new MovableClock(Instant.parse("2086-06-14T06:00:00Z"));

    @TestConfiguration
    static class Overrides {
        @Bean
        @Primary
        Clock movableClock() {
            return CLOCK;
        }
    }

    private List<Map<String, Object>> changes(Object member) {
        Http.Exchange read = api.call(alpha, "GET", schedule(member), null, salonOwner);
        status(read, 200);
        return maps(api.body(read).get("dateChanges"));
    }

    @Test
    void justBeforeMidnightInTheSalonTodayIsTheSalonsWhileUtcIsTheSameDay() {
        CLOCK.set(Instant.parse("2086-06-14T18:00:00Z")); // 23:30 on the 14th in the salon.
        salonOwner = settings.signedIn(alpha, com.salonplatform.identity.domain.StaffRole.OWNER)
                .token();
        Object member = added("Clock Person").get("id");

        Http.Exchange read = api.call(alpha, "GET", schedule(member), null, salonOwner);
        assertThat(api.body(read).get("today")).isEqualTo("2086-06-14");
        status(api.call(alpha, "POST", dateChanges(member), notWorking("2086-06-14", null), salonOwner), 201);
        fieldErrors(api.call(alpha, "POST", dateChanges(member), notWorking("2086-06-13", null), salonOwner), "date");
        status(api.call(alpha, "POST", dateChanges(member), notWorking("2087-06-14", null), salonOwner), 201);
        fieldErrors(api.call(alpha, "POST", dateChanges(member), notWorking("2087-06-15", null), salonOwner), "date");
    }

    /** The other direction: west of UTC, 23:30 in the salon is already tomorrow in UTC, the date the spec's example meant. */
    @Test
    void westOfUtcAt2330TodayIsTheSalonsThoughItIsAlreadyTomorrowInUtc() {
        owner.update("UPDATE tenant SET time_zone = 'America/New_York' WHERE id = ?", alpha.id());
        CLOCK.set(Instant.parse("2086-09-15T03:30:00Z")); // 23:30 on the 14th in New York, the 15th in UTC.
        salonOwner = settings.signedIn(alpha, com.salonplatform.identity.domain.StaffRole.OWNER)
                .token();
        Object member = added("Clock Person").get("id");

        Http.Exchange read = api.call(alpha, "GET", schedule(member), null, salonOwner);
        assertThat(api.body(read).get("today")).isEqualTo("2086-09-14");
        status(api.call(alpha, "POST", dateChanges(member), notWorking("2086-09-14", null), salonOwner), 201);
        fieldErrors(api.call(alpha, "POST", dateChanges(member), notWorking("2086-09-13", null), salonOwner), "date");
        status(api.call(alpha, "POST", dateChanges(member), notWorking("2087-09-14", null), salonOwner), 201);
        fieldErrors(api.call(alpha, "POST", dateChanges(member), notWorking("2087-09-15", null), salonOwner), "date");
    }

    @Test
    void afterMidnightInTheSalonTodayIsAlreadyTomorrowHoweverUtcReadsIt() {
        CLOCK.set(Instant.parse("2086-07-14T19:00:00Z")); // 00:30 on the 15th in the salon, still the 14th in UTC.
        salonOwner = settings.signedIn(alpha, com.salonplatform.identity.domain.StaffRole.OWNER)
                .token();
        Object member = added("Clock Person").get("id");

        Http.Exchange read = api.call(alpha, "GET", schedule(member), null, salonOwner);
        assertThat(api.body(read).get("today")).isEqualTo("2086-07-15");
        // The 14th is UTC's today, and yesterday in the salon.
        fieldErrors(api.call(alpha, "POST", dateChanges(member), notWorking("2086-07-14", null), salonOwner), "date");
        status(api.call(alpha, "POST", dateChanges(member), notWorking("2086-07-15", null), salonOwner), 201);
    }

    @Test
    void aChangeLeavesTheListAndCannotBeRemovedOnceTheSalonsDateHasPassed() {
        CLOCK.set(Instant.parse("2086-08-14T18:00:00Z")); // 23:30 on the 14th in the salon.
        salonOwner = settings.signedIn(alpha, com.salonplatform.identity.domain.StaffRole.OWNER)
                .token();
        Object member = added("Clock Person").get("id");
        Http.Exchange added = api.call(alpha, "POST", dateChanges(member), notWorking("2086-08-14", null), salonOwner);
        status(added, 201);
        String path =
                dateChanges(member) + "/" + map(api.body(added).get("change")).get("id");
        assertThat(changes(member)).hasSize(1);

        CLOCK.set(Instant.parse("2086-08-14T19:00:00Z")); // 00:30 on the 15th there.
        salonOwner = settings.signedIn(alpha, com.salonplatform.identity.domain.StaffRole.OWNER)
                .token();

        assertThat(changes(member)).isEmpty();
        refused(api.call(alpha, "DELETE", path, null, salonOwner), 404, "not_found");
    }

    // --- F06-S03: leave and blocks follow the salon's date too -----------------------------------------------------

    private String signedInOwner() {
        return settings.signedIn(alpha, com.salonplatform.identity.domain.StaffRole.OWNER)
                .token();
    }

    private Http.Exchange addLeave(Object member, String first, String last) {
        return api.call(
                alpha, "POST", TeamFixture.leave(member), TeamFixture.leaveOf(first, last, "sick", null), salonOwner);
    }

    private Http.Exchange addBlock(Object member, String date) {
        return api.call(
                alpha,
                "POST",
                TeamFixture.blockedTime(member),
                TeamFixture.blockOf(date, "10:00", "11:00", "Training"),
                salonOwner);
    }

    private List<Map<String, Object>> listed(Object member, String field) {
        Http.Exchange read = api.call(alpha, "GET", schedule(member), null, salonOwner);
        status(read, 200);
        return maps(api.body(read).get(field));
    }

    @Test
    void leaveAndBlocksUseTheSalonsDateAtAnEdgeOfTheDayEastOfUtc() {
        CLOCK.set(Instant.parse("2088-07-14T19:00:00Z")); // 00:30 on the 15th in the salon, still the 14th in UTC.
        salonOwner = signedInOwner();
        Object member = added("Clock Person").get("id");

        fieldErrors(addLeave(member, "2088-07-14", "2088-07-14"), "firstDay", "lastDay");
        status(addLeave(member, "2088-07-15", "2088-07-15"), 201);
        status(addLeave(member, "2089-07-15", "2089-07-15"), 201);
        fieldErrors(addLeave(member, "2089-07-16", "2089-07-16"), "firstDay", "lastDay");
        fieldErrors(addBlock(member, "2088-07-14"), "date");
        status(addBlock(member, "2088-07-15"), 201);
        status(addBlock(member, "2089-07-15"), 201);
        fieldErrors(addBlock(member, "2089-07-16"), "date");
    }

    @Test
    void leaveAndBlocksUseTheSalonsDateWestOfUtc() {
        owner.update("UPDATE tenant SET time_zone = 'America/New_York' WHERE id = ?", alpha.id());
        CLOCK.set(Instant.parse("2088-09-15T03:30:00Z")); // 23:30 on the 14th in New York, the 15th in UTC.
        salonOwner = signedInOwner();
        Object member = added("Clock Person").get("id");

        status(addLeave(member, "2088-09-14", "2088-09-14"), 201);
        status(addBlock(member, "2088-09-14"), 201);
        fieldErrors(addLeave(member, "2088-09-13", "2088-09-13"), "firstDay", "lastDay");
        fieldErrors(addBlock(member, "2088-09-13"), "date");
    }

    @Test
    void whenTheSalonsDatePassesLeaveThatEndsLeavesTheListAndTheCardAndCannotBeRemoved() {
        CLOCK.set(Instant.parse("2088-08-14T18:00:00Z")); // 23:30 on the 14th in the salon.
        salonOwner = signedInOwner();
        Object member = added("Clock Person").get("id");
        Http.Exchange ending = addLeave(member, "2088-08-14", "2088-08-14");
        status(ending, 201);
        Http.Exchange block = addBlock(member, "2088-08-14");
        status(block, 201);
        Http.Exchange goingOn = addLeave(member, "2088-08-16", "2088-08-18");
        status(goingOn, 201);
        String endingPath = TeamFixture.leave(member) + "/"
                + map(api.body(ending).get("leave")).get("id");
        String blockPath = TeamFixture.blockedTime(member) + "/"
                + map(api.body(block).get("block")).get("id");
        assertThat(listed(member, "leave")).hasSize(2);
        assertThat(listed(member, "blockedTime")).hasSize(1);
        assertThat(map(read(member).get("nextLeave"))).containsEntry("firstDay", "2088-08-14");

        CLOCK.set(Instant.parse("2088-08-14T19:00:00Z")); // 00:30 on the 15th there.
        salonOwner = signedInOwner();

        assertThat(listed(member, "leave"))
                .extracting(each -> each.get("firstDay"))
                .containsExactly("2088-08-16");
        assertThat(listed(member, "blockedTime")).isEmpty();
        assertThat(map(read(member).get("nextLeave"))).containsEntry("firstDay", "2088-08-16");
        refused(api.call(alpha, "DELETE", endingPath, null, salonOwner), 404, "not_found");
        refused(api.call(alpha, "DELETE", blockPath, null, salonOwner), 404, "not_found");

        CLOCK.set(Instant.parse("2088-08-16T19:00:00Z")); // 00:30 on the 17th: under way.
        salonOwner = signedInOwner();

        assertThat(map(read(member).get("nextLeave")))
                .as("the card shows leave under way")
                .containsEntry("firstDay", "2088-08-16");
        status(
                api.call(
                        alpha,
                        "DELETE",
                        TeamFixture.leave(member) + "/"
                                + map(api.body(goingOn).get("leave")).get("id"),
                        null,
                        salonOwner),
                204);
        assertThat(read(member).get("nextLeave")).isNull();
    }
}
