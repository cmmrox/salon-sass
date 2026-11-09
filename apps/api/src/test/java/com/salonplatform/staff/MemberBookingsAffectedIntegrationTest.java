package com.salonplatform.staff;

import static com.salonplatform.staff.TeamFixture.dateChanges;
import static com.salonplatform.staff.TeamFixture.differentHours;
import static com.salonplatform.staff.TeamFixture.notWorking;
import static com.salonplatform.staff.TeamFixture.pause;
import static com.salonplatform.staff.TeamFixture.schedule;
import static com.salonplatform.staff.TeamFixture.week;
import static com.salonplatform.staff.TeamFixture.weekSave;
import static com.salonplatform.staff.TeamFixture.weekly;
import static com.salonplatform.staff.TeamFixture.workday;
import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.testing.Http;
import com.salonplatform.testing.TestSalons;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The bookings a change leaves outside (F06-S02 AC-11): with an implementation that reports what this test names standing
 * in for {@code booking}'s (F07), the answers list them, and nothing about the change differs for them.
 */
class MemberBookingsAffectedIntegrationTest extends TwoSalonTeam {

    static final AtomicReference<List<AffectedBooking>> REPORTED = new AtomicReference<>(List.of());
    static final AtomicReference<Map<DayOfWeek, List<TimeWindow>>> ASKED_WEEK = new AtomicReference<>();
    static final AtomicReference<Set<LocalDate>> ASKED_EXCEPT = new AtomicReference<>();
    static final AtomicReference<LocalDate> ASKED_FROM = new AtomicReference<>();
    static final AtomicReference<List<TimeWindow>> ASKED_DATE = new AtomicReference<>();

    @TestConfiguration
    static class Overrides {
        @Bean
        @Primary
        MemberBookingsAffected reportingBookings() {
            return new MemberBookingsAffected() {
                @Override
                public List<AffectedBooking> outsideWeek(
                        UUID memberId,
                        Map<DayOfWeek, List<TimeWindow>> windows,
                        LocalDate from,
                        Set<LocalDate> exceptDates) {
                    ASKED_WEEK.set(windows);
                    ASKED_FROM.set(from);
                    ASKED_EXCEPT.set(exceptDates);
                    return REPORTED.get();
                }

                @Override
                public List<AffectedBooking> outsideDate(UUID memberId, LocalDate date, List<TimeWindow> windows) {
                    ASKED_DATE.set(windows);
                    return REPORTED.get();
                }

                @Override
                public Map<MemberPeriod, List<AffectedBooking>> inPeriods(UUID memberId, Set<MemberPeriod> periods) {
                    return Map.of();
                }
            };
        }
    }

    @AfterEach
    void noBookings() {
        REPORTED.set(List.of());
        ASKED_WEEK.set(null);
        ASKED_EXCEPT.set(null);
        ASKED_FROM.set(null);
        ASKED_DATE.set(null);
    }

    private static final AffectedBooking KASUNS =
            new AffectedBooking(LocalDate.of(2099, 3, 2), LocalTime.of(10, 30), "Kasun Perera", "Balayage");

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), TestSalons.DEFAULT_TIME_ZONE);
    }

    private long version(Object member) {
        Http.Exchange read = api.call(alpha, "GET", schedule(member), null, salonOwner);
        status(read, 200);
        return ((Number) api.body(read).get("version")).longValue();
    }

    @Test
    void aWeeklySaveListsTheBookingsLeftOutsideAndIsStillSaved() {
        Object member = added("Nimali").get("id");
        long at = version(member);
        REPORTED.set(List.of(KASUNS));

        Http.Exchange answer = api.call(
                alpha,
                "PUT",
                weekly(member),
                weekSave(at, week(workday("monday", "09:00", "18:00", pause("13:00", "14:00")))),
                salonOwner);

        status(answer, 200);
        assertThat(maps(api.body(answer).get("affected")))
                .containsExactly(Map.of(
                        "date", "2099-03-02",
                        "time", "10:30",
                        "customerName", "Kasun Perera",
                        "serviceName", "Balayage"));
        assertThat(version(member)).isEqualTo(at + 1);
        assertThat(ASKED_FROM.get()).isEqualTo(today());
        assertThat(ASKED_WEEK.get()).hasSize(7);
        assertThat(ASKED_WEEK.get().get(DayOfWeek.MONDAY))
                .as("what the booking question is given: the hours with the breaks out")
                .containsExactly(
                        new TimeWindow(LocalTime.of(9, 0), LocalTime.of(13, 0)),
                        new TimeWindow(LocalTime.of(14, 0), LocalTime.of(18, 0)));
        assertThat(ASKED_WEEK.get().get(DayOfWeek.TUESDAY)).isEmpty();
    }

    @Test
    void aWeeklySaveDoesNotAskAboutDatesThatHaveTheirOwnChange() {
        Object member = added("Nimali").get("id");
        LocalDate changed = today().plusDays(2);
        status(api.call(alpha, "POST", dateChanges(member), notWorking(changed, null), salonOwner), 201);

        status(
                api.call(
                        alpha,
                        "PUT",
                        weekly(member),
                        weekSave(version(member), week(workday("monday", "09:00", "18:00"))),
                        salonOwner),
                200);

        assertThat(ASKED_EXCEPT.get()).containsExactly(changed);
    }

    @Test
    void addingADateChangeListsTheBookingsItAffectsAndIsStillAdded() {
        Object member = added("Nimali").get("id");
        REPORTED.set(List.of(KASUNS));

        Http.Exchange answer = api.call(
                alpha,
                "POST",
                dateChanges(member),
                differentHours(today().plusDays(1), "09:00", "13:00", null, pause("10:00", "10:15")),
                salonOwner);

        status(answer, 201);
        assertThat(maps(api.body(answer).get("affected")))
                .extracting(each -> each.get("customerName"))
                .containsExactly("Kasun Perera");
        assertThat(map(api.body(answer).get("change"))).containsEntry("starts", "09:00");
        assertThat(ASKED_DATE.get())
                .containsExactly(
                        new TimeWindow(LocalTime.of(9, 0), LocalTime.of(10, 0)),
                        new TimeWindow(LocalTime.of(10, 15), LocalTime.of(13, 0)));
    }

    @Test
    void aDateNotWorkedAsksWithNoWindows() {
        Object member = added("Nimali").get("id");

        status(api.call(alpha, "POST", dateChanges(member), notWorking(today().plusDays(1), null), salonOwner), 201);

        assertThat(ASKED_DATE.get()).isEmpty();
    }

    @Test
    void aRefusedChangeAsksNothing() {
        Object member = added("Nimali").get("id");
        long at = version(member);

        refused(api.call(alpha, "PUT", weekly(member), weekSave(at + 5, week()), salonOwner), 409, "conflict");

        assertThat(ASKED_WEEK.get()).isNull();
    }
}
