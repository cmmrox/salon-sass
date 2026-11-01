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
}
