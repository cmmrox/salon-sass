package com.salonplatform.staff;

import static com.salonplatform.staff.TeamFixture.blockOf;
import static com.salonplatform.staff.TeamFixture.blockedTime;
import static com.salonplatform.staff.TeamFixture.leave;
import static com.salonplatform.staff.TeamFixture.leaveOf;
import static com.salonplatform.staff.TeamFixture.schedule;
import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.identity.domain.StaffRole;
import com.salonplatform.testing.Http;
import com.salonplatform.testing.TestSalons;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The bookings in a period of leave or a block (F06-S03 AC-3, AC-8, decision 104(c)): with an implementation that reports
 * bookings standing in for {@code booking}'s (F07), the forms' question, the answers to adding, and the count on the list all
 * come from it. Its bookings live here and {@code staff} has no way to change them, so a flag can only be what it answers now.
 */
class MemberPeriodsAffectedIntegrationTest extends TwoSalonTeam {

    /** One booking, kept by the stand-in, as {@code booking} would keep it. */
    private record Held(AffectedBooking booking, LocalTime finishes) {}

    private static final List<Held> BOOKINGS = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final Set<MemberPeriod> ASKED = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger QUESTIONS = new AtomicInteger();
    private static volatile Runnable whenAsked = () -> {};

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
                    return List.of();
                }

                @Override
                public List<AffectedBooking> outsideDate(UUID memberId, LocalDate date, List<TimeWindow> windows) {
                    return List.of();
                }

                @Override
                public Map<MemberPeriod, List<AffectedBooking>> inPeriods(UUID memberId, Set<MemberPeriod> periods) {
                    QUESTIONS.incrementAndGet();
                    ASKED.addAll(periods);
                    whenAsked.run();
                    Map<MemberPeriod, List<AffectedBooking>> answer = new HashMap<>();
                    for (MemberPeriod period : periods) {
                        List<AffectedBooking> in = BOOKINGS.stream()
                                .filter(held -> lies(held, period))
                                .map(Held::booking)
                                .toList();
                        if (!in.isEmpty()) {
                            answer.put(period, in);
                        }
                    }
                    return answer;
                }

                private boolean lies(Held held, MemberPeriod period) {
                    AffectedBooking booking = held.booking();
                    boolean onDays = !booking.date().isBefore(period.firstDay())
                            && !booking.date().isAfter(period.lastDay());
                    return onDays
                            && (period.time() == null
                                    || (booking.time().isBefore(period.time().finishes())
                                            && held.finishes()
                                                    .isAfter(period.time().starts())));
                }
            };
        }
    }

    private LocalDate today;
    private Object nimali;

    @BeforeEach
    void aPerson() {
        today = LocalDate.ofInstant(clock.instant(), TestSalons.DEFAULT_TIME_ZONE);
        nimali = added("Nimali").get("id");
        ASKED.clear();
        QUESTIONS.set(0);
    }

    @AfterEach
    void noBookings() {
        BOOKINGS.clear();
        whenAsked = () -> {};
    }

    private static void booking(LocalDate date, String starts, String finishes, String customer) {
        BOOKINGS.add(new Held(
                new AffectedBooking(date, LocalTime.parse(starts), customer, "Balayage"), LocalTime.parse(finishes)));
    }

    private Map<String, Object> scheduleRead() {
        Http.Exchange read = api.call(alpha, "GET", schedule(nimali), null, salonOwner);
        status(read, 200);
        return api.body(read);
    }

    @Test
    void theLeaveFormsQuestionListsTheBookingsWithTheirDateTimeCustomerAndService() {
        booking(today.plusDays(2), "10:30", "11:30", "Kasun Perera");
        booking(today.plusDays(9), "10:30", "11:30", "Outside the leave");

        Http.Exchange answer = api.call(
                alpha,
                "GET",
                leave(nimali) + "/affected?firstDay=" + today.plusDays(1) + "&lastDay=" + today.plusDays(3),
                null,
                salonOwner);

        status(answer, 200);
        assertThat(maps(api.body(answer).get("affected")))
                .containsExactly(Map.of(
                        "date", today.plusDays(2).toString(),
                        "time", "10:30",
                        "customerName", "Kasun Perera",
                        "serviceName", "Balayage"));
        assertThat(ASKED).containsExactly(MemberPeriod.days(today.plusDays(1), today.plusDays(3)));
    }

    @Test
    void theBlockFormsQuestionAsksAboutThatPartOfTheDate() {
        booking(today.plusDays(1), "10:30", "11:30", "Overlapping");
        booking(today.plusDays(1), "12:00", "13:00", "Only touching");

        Http.Exchange answer = api.call(
                alpha,
                "GET",
                blockedTime(nimali) + "/affected?date=" + today.plusDays(1) + "&starts=11:00&finishes=12:00",
                null,
                salonOwner);

        status(answer, 200);
        assertThat(maps(api.body(answer).get("affected")))
                .extracting(each -> each.get("customerName"))
                .containsExactly("Overlapping");
        assertThat(ASKED)
                .containsExactly(MemberPeriod.partOf(
                        today.plusDays(1), new TimeWindow(LocalTime.of(11, 0), LocalTime.of(12, 0))));
    }

    @Test
    void addingLeaveOverABookingIsAllowedListsItAndCountsItWhichClearsWhenTheLeaveIsRemoved() {
        booking(today.plusDays(2), "10:30", "11:30", "Kasun Perera");
        booking(today.plusDays(3), "09:00", "10:00", "Amara Silva");

        Http.Exchange added = api.call(
                alpha,
                "POST",
                leave(nimali),
                leaveOf(today.plusDays(1), today.plusDays(3), "annual", null),
                salonOwner);

        status(added, 201);
        assertThat(map(api.body(added).get("leave"))).containsEntry("bookingsToResolve", 2);
        assertThat(maps(api.body(added).get("affected")))
                .extracting(each -> each.get("customerName"))
                .containsExactly("Kasun Perera", "Amara Silva");
        assertThat(maps(scheduleRead().get("leave")).getFirst()).containsEntry("bookingsToResolve", 2);

        // The same bookings are still there, untouched; nothing is stored, so the flag goes with the leave.
        List<Held> kept = List.copyOf(BOOKINGS);
        status(
                api.call(
                        alpha,
                        "DELETE",
                        leave(nimali) + "/" + map(api.body(added).get("leave")).get("id"),
                        null,
                        salonOwner),
                204);

        assertThat(BOOKINGS).containsExactlyElementsOf(kept);
        assertThat(maps(scheduleRead().get("leave"))).isEmpty();
        Http.Exchange again = api.call(
                alpha,
                "POST",
                leave(nimali),
                leaveOf(today.plusDays(5), today.plusDays(5), "annual", null),
                salonOwner);
        assertThat(map(api.body(again).get("leave"))).containsEntry("bookingsToResolve", 0);
        assertThat(maps(api.body(again).get("affected"))).isEmpty();
    }

    @Test
    void addingABlockOverABookingIsAllowedAndListsItAndItsCountFollowsTheBookingNotAStoredFlag() {
        booking(today.plusDays(1), "10:30", "11:30", "Kasun Perera");

        Http.Exchange added = api.call(
                alpha,
                "POST",
                blockedTime(nimali),
                blockOf(today.plusDays(1), "10:00", "11:00", "Training"),
                salonOwner);

        status(added, 201);
        assertThat(map(api.body(added).get("block"))).containsEntry("bookingsToResolve", 1);
        assertThat(maps(api.body(added).get("affected")))
                .extracting(each -> each.get("customerName"))
                .containsExactly("Kasun Perera");
        assertThat(maps(scheduleRead().get("blockedTime")).getFirst()).containsEntry("bookingsToResolve", 1);

        // The booking moves away: the next read says so with nothing to keep up to date.
        BOOKINGS.clear();
        booking(today.plusDays(2), "10:30", "11:30", "Kasun Perera");

        assertThat(maps(scheduleRead().get("blockedTime")).getFirst()).containsEntry("bookingsToResolve", 0);
    }

    @Test
    void theQuestionIsAskedOnlyOnceTheAddHasCommitted() {
        long[] seen = {-1};
        whenAsked = () -> seen[0] = owner.queryForObject(
                "SELECT count(*) FROM staff_member_leave WHERE tenant_id = ? AND member_id = ?::uuid",
                Long.class,
                alpha.id(),
                nimali.toString());

        status(
                api.call(
                        alpha,
                        "POST",
                        leave(nimali),
                        leaveOf(today.plusDays(1), today.plusDays(1), "annual", null),
                        salonOwner),
                201);

        assertThat(seen[0])
                .as("another connection sees the row, so its transaction has committed")
                .isEqualTo(1);
    }

    @Test
    void aRefusedAddAsksNothing() {
        status(
                api.call(
                        alpha,
                        "POST",
                        leave(nimali),
                        leaveOf(today.plusDays(1), today.plusDays(1), "annual", null),
                        salonOwner),
                201);
        QUESTIONS.set(0);

        refused(
                api.call(
                        alpha,
                        "POST",
                        leave(nimali),
                        leaveOf(today.plusDays(1), today.plusDays(1), "other", null),
                        salonOwner),
                409,
                "conflict");
        fieldErrors(
                api.call(
                        alpha,
                        "POST",
                        leave(nimali),
                        leaveOf(today.minusDays(1), today.plusDays(1), "other", null),
                        salonOwner),
                "firstDay");

        assertThat(QUESTIONS.get()).isZero();
    }

    @Test
    void theScheduleAsksOneQuestionForAllItsPeriods() {
        status(
                api.call(
                        alpha,
                        "POST",
                        leave(nimali),
                        leaveOf(today.plusDays(1), today.plusDays(1), "annual", null),
                        salonOwner),
                201);
        status(
                api.call(
                        alpha,
                        "POST",
                        leave(nimali),
                        leaveOf(today.plusDays(2), today.plusDays(2), "annual", null),
                        salonOwner),
                201);
        status(
                api.call(
                        alpha,
                        "POST",
                        blockedTime(nimali),
                        blockOf(today.plusDays(3), "10:00", "11:00", "One"),
                        salonOwner),
                201);
        status(
                api.call(
                        alpha,
                        "POST",
                        blockedTime(nimali),
                        blockOf(today.plusDays(4), "10:00", "11:00", "Two"),
                        salonOwner),
                201);
        QUESTIONS.set(0);
        ASKED.clear();

        scheduleRead();

        assertThat(QUESTIONS.get()).isEqualTo(1);
        assertThat(new HashSet<>(ASKED)).hasSize(4);
    }

    @Test
    void forLeaveUnderWayOnlyWhatIsLeftOfItIsAskedSoAPastBookingIsNotToResolve() {
        booking(today.minusDays(1), "10:00", "11:00", "Already past");
        booking(today.plusDays(1), "10:00", "11:00", "Still coming");
        owner.update(
                """
                INSERT INTO staff_member_leave (tenant_id, id, branch_id, member_id, first_day, last_day, leave_type,
                    created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?::uuid, ?, ?, 'annual', ?, now(), now())
                """,
                alpha.id(),
                UUID.randomUUID(),
                salons.defaultBranch(alpha),
                nimali.toString(),
                today.minusDays(2),
                today.plusDays(2),
                UUID.randomUUID());

        Map<String, Object> entry = maps(scheduleRead().get("leave")).getFirst();

        assertThat(entry)
                .containsEntry("firstDay", today.minusDays(2).toString())
                .containsEntry("bookingsToResolve", 1);
        assertThat(ASKED).containsExactly(MemberPeriod.days(today, today.plusDays(2)));
    }

    @Test
    void aReceptionistSeesTheCountAndTheBookingsOnABlockButNothingOfTheLeave() {
        booking(today.plusDays(1), "10:30", "11:30", "Kasun Perera");
        status(
                api.call(
                        alpha,
                        "POST",
                        leave(nimali),
                        leaveOf(today.plusDays(1), today.plusDays(1), "sick", "Private note"),
                        salonOwner),
                201);
        String receptionist = settings.signedIn(alpha, StaffRole.RECEPTIONIST).token();

        Http.Exchange read = api.call(alpha, "GET", schedule(nimali), null, receptionist);

        assertThat(maps(api.body(read).get("leave")).getFirst())
                .containsOnlyKeys("id", "firstDay", "lastDay", "bookingsToResolve")
                .containsEntry("bookingsToResolve", 1);
        Http.Exchange block = api.call(
                alpha,
                "GET",
                blockedTime(nimali) + "/affected?date=" + today.plusDays(1) + "&starts=10:00&finishes=11:00",
                null,
                receptionist);
        assertThat(maps(api.body(block).get("affected"))).hasSize(1);
    }
}
