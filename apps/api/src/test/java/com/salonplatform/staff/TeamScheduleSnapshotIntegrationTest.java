package com.salonplatform.staff;

import static com.salonplatform.staff.TeamFixture.leave;
import static com.salonplatform.staff.TeamFixture.leaveOf;
import static com.salonplatform.staff.TeamFixture.pause;
import static com.salonplatform.staff.TeamFixture.schedule;
import static com.salonplatform.staff.TeamFixture.week;
import static com.salonplatform.staff.TeamFixture.weekSave;
import static com.salonplatform.staff.TeamFixture.weekly;
import static com.salonplatform.staff.TeamFixture.workday;
import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.staff.MemberWorkingDay.DecidedBy;
import com.salonplatform.staff.repository.MemberScheduleRepository;
import com.salonplatform.staff.repository.MemberWeekdayBreakRepository;
import com.salonplatform.testing.Http;
import com.salonplatform.testing.TestSalons;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;

/**
 * {@link TeamSchedule} read while a weekly save is under way: the answer is the whole old week or the whole new one, never a
 * mix. Neither test rests on timing. A save replaces every row of the week, so the new breaks hang off new weekdays, and a read
 * that saw the old weekdays and then the new breaks would answer the old hours with their breaks gone. Two seams on the
 * repositories the read and the save use stop each at the exact statement that matters:
 *
 * <ul>
 *   <li>the save is held open with all its rows written and nothing committed, and is read meanwhile;
 *   <li>a save commits between the read's two statements, which is the only moment a mix could be made.
 * </ul>
 */
// Its wrapped repositories make a context of its own, closed after this class to free its pools.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TeamScheduleSnapshotIntegrationTest extends TwoSalonTeam {

    /** Runs once, just before a read asks for the breaks of the weekdays it has already read. */
    private static final AtomicReference<Runnable> BEFORE_READING_BREAKS = new AtomicReference<>();

    /** Runs once, when a save has written the whole week and has not committed. */
    private static final AtomicReference<Runnable> WHEN_WEEK_WRITTEN = new AtomicReference<>();

    @TestConfiguration
    static class Seams {
        @Bean
        static BeanPostProcessor stopAtTheStatementsThatMatter() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (bean instanceof MemberWeekdayBreakRepository) {
                        return stopping(bean, MemberWeekdayBreakRepository.class, "ofMembers", BEFORE_READING_BREAKS);
                    }
                    if (bean instanceof MemberScheduleRepository) {
                        // The save's last write to the week ends with this flush.
                        return stopping(bean, MemberScheduleRepository.class, "flush", WHEN_WEEK_WRITTEN);
                    }
                    return bean;
                }
            };
        }

        private static Object stopping(Object bean, Class<?> type, String method, AtomicReference<Runnable> stop) {
            InvocationHandler handler = (proxy, called, arguments) -> {
                Runnable once = called.getName().equals(method) ? stop.getAndSet(null) : null;
                if (once != null) {
                    once.run();
                }
                try {
                    return called.invoke(bean, arguments);
                } catch (InvocationTargetException ex) {
                    throw ex.getCause();
                }
            };
            return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
        }
    }

    @Autowired
    TeamSchedule teamSchedule;

    private UUID nimali;
    private LocalDate monday;
    private LocalDate tuesday;

    private static final List<TimeWindow> OLD_MONDAY = List.of(window("09:00", "13:00"), window("14:00", "18:00"));
    private static final List<TimeWindow> NEW_MONDAY = List.of(window("10:00", "12:00"), window("12:30", "16:00"));

    private static TimeWindow window(String starts, String finishes) {
        return new TimeWindow(LocalTime.parse(starts), LocalTime.parse(finishes));
    }

    private static List<Map<String, Object>> oldWeek() {
        return week(workday("monday", "09:00", "18:00", pause("13:00", "14:00")), workday("tuesday", "09:00", "18:00"));
    }

    private static List<Map<String, Object>> newWeek() {
        return week(workday("monday", "10:00", "16:00", pause("12:00", "12:30")));
    }

    @BeforeEach
    void aPersonOnTheOldWeek() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), TestSalons.DEFAULT_TIME_ZONE);
        monday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        tuesday = monday.plusDays(1);
        nimali = UUID.fromString(added("Nimali").get("id").toString());
        status(saving(oldWeek()), 200);
    }

    @AfterEach
    void noStops() {
        BEFORE_READING_BREAKS.set(null);
        WHEN_WEEK_WRITTEN.set(null);
    }

    private Http.Exchange saving(List<Map<String, Object>> days) {
        Http.Exchange read = api.call(alpha, "GET", schedule(nimali), null, salonOwner);
        status(read, 200);
        long version = ((Number) api.body(read).get("version")).longValue();
        return api.call(alpha, "PUT", weekly(nimali), weekSave(version, days), salonOwner);
    }

    private List<MemberWorkingDay> read() throws Exception {
        return TenantContext.callAs(salons.asCurrent(alpha), () -> teamSchedule.between(nimali, monday, tuesday));
    }

    private void isTheWholeOldWeek(List<MemberWorkingDay> days) {
        assertThat(days)
                .containsExactly(
                        new MemberWorkingDay(monday, true, OLD_MONDAY, DecidedBy.WEEK),
                        new MemberWorkingDay(tuesday, true, List.of(window("09:00", "18:00")), DecidedBy.WEEK));
    }

    private void isTheWholeNewWeek(List<MemberWorkingDay> days) {
        assertThat(days)
                .containsExactly(
                        new MemberWorkingDay(monday, true, NEW_MONDAY, DecidedBy.WEEK),
                        new MemberWorkingDay(tuesday, false, List.of(), DecidedBy.WEEK));
    }

    @Test
    void aReadWhileASaveIsHeldOpenAnswersTheWholeOldWeekAndAfterItTheWholeNewOne() throws Exception {
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        WHEN_WEEK_WRITTEN.set(() -> {
            written.countDown();
            try {
                assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        });

        CompletableFuture<Http.Exchange> save = CompletableFuture.supplyAsync(() -> saving(newWeek()));
        assertThat(written.await(20, TimeUnit.SECONDS))
                .as("the save reached the point where its whole week is written")
                .isTrue();
        assertThat(save).isNotDone();

        // The read does not wait for the save, and sees nothing of it.
        isTheWholeOldWeek(read());
        assertThat(save).as("still held open while it was read").isNotDone();

        release.countDown();
        status(save.get(30, TimeUnit.SECONDS), 200);

        isTheWholeNewWeek(read());
    }

    @Test
    void aSaveCommittingBetweenTheReadsStatementsLeavesTheReadOnTheWholeOldWeek() throws Exception {
        BEFORE_READING_BREAKS.set(() -> status(saving(newWeek()), 200));

        List<MemberWorkingDay> whileItCommitted = read();

        assertThat(BEFORE_READING_BREAKS.get())
                .as("the save ran part way through the read")
                .isNull();
        isTheWholeOldWeek(whileItCommitted);
        isTheWholeNewWeek(read());
    }

    @Test
    void leaveCommittingPartWayThroughAReadIsNotHalfSeenAndTheNextReadHasIt() throws Exception {
        BEFORE_READING_BREAKS.set(() -> status(
                api.call(alpha, "POST", leave(nimali), leaveOf(monday, tuesday, "annual", null), salonOwner), 201));

        List<MemberWorkingDay> whileItCommitted = read();

        isTheWholeOldWeek(whileItCommitted);
        assertThat(read())
                .containsExactly(
                        new MemberWorkingDay(monday, false, List.of(), DecidedBy.LEAVE),
                        new MemberWorkingDay(tuesday, false, List.of(), DecidedBy.LEAVE));
    }
}
