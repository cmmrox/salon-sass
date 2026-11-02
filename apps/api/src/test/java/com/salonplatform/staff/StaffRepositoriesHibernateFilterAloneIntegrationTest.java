package com.salonplatform.staff;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.staff.domain.LeaveType;
import com.salonplatform.staff.entity.Closure;
import com.salonplatform.staff.entity.HolidayChoice;
import com.salonplatform.staff.entity.HolidayReview;
import com.salonplatform.staff.entity.Member;
import com.salonplatform.staff.entity.MemberBlockedTime;
import com.salonplatform.staff.entity.MemberCategory;
import com.salonplatform.staff.entity.MemberDateChange;
import com.salonplatform.staff.entity.MemberDateChangeBreak;
import com.salonplatform.staff.entity.MemberLeave;
import com.salonplatform.staff.entity.MemberPhoto;
import com.salonplatform.staff.entity.MemberSchedule;
import com.salonplatform.staff.entity.MemberSingleService;
import com.salonplatform.staff.entity.MemberWeekday;
import com.salonplatform.staff.entity.MemberWeekdayBreak;
import com.salonplatform.staff.entity.OpeningHours;
import com.salonplatform.staff.repository.ClosureRepository;
import com.salonplatform.staff.repository.HolidayChoiceRepository;
import com.salonplatform.staff.repository.HolidayReviewRepository;
import com.salonplatform.staff.repository.MemberBlockedTimeRepository;
import com.salonplatform.staff.repository.MemberCategoryRepository;
import com.salonplatform.staff.repository.MemberDateChangeBreakRepository;
import com.salonplatform.staff.repository.MemberDateChangeRepository;
import com.salonplatform.staff.repository.MemberLeaveRepository;
import com.salonplatform.staff.repository.MemberPhotoRepository;
import com.salonplatform.staff.repository.MemberRepository;
import com.salonplatform.staff.repository.MemberScheduleRepository;
import com.salonplatform.staff.repository.MemberSingleServiceRepository;
import com.salonplatform.staff.repository.MemberWeekdayBreakRepository;
import com.salonplatform.staff.repository.MemberWeekdayRepository;
import com.salonplatform.staff.repository.OpeningHoursRepository;
import com.salonplatform.testing.StartedApplication;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import com.salonplatform.testing.TestSalons;
import com.salonplatform.testing.TestSalons.TestSalon;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The first layer on its own for F04-S03's tables: Hibernate's tenant filter ({@code @TenantId}), with row-level
 * security disabled on them and every write granted in this database, so that Hibernate and nothing else stands
 * between one salon and another. The cases of docs/architecture/multi-tenancy.md, "Tests you must write", hold.
 */
class StaffRepositoriesHibernateFilterAloneIntegrationTest {

    private static TestDatabase database;
    private static StartedApplication application;
    private static TestSalons salons;
    private static TestSalon alpha;
    private static TestSalon beta;
    private static EntityManager entityManager;
    private static TransactionTemplate transaction;

    @BeforeAll
    static void applicationOnADatabaseWithoutRowLevelSecurity() {
        database = TestDatabase.createEmpty(TestPostgres.shared());
        application = database.start();
        JdbcTemplate owner = database.asOwner();
        for (String table : StaffTablesRowLevelSecurityAloneIntegrationTest.TABLES) {
            owner.execute("ALTER TABLE " + table + " NO FORCE ROW LEVEL SECURITY");
            owner.execute("ALTER TABLE " + table + " DISABLE ROW LEVEL SECURITY");
            owner.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON " + table + " TO " + TestPostgres.APP_USER);
        }
        salons = new TestSalons(owner);
        alpha = salons.create("Filter Alpha", "#111111");
        beta = salons.create("Filter Beta", "#222222");
        entityManager =
                SharedEntityManagerCreator.createSharedEntityManager(application.bean(EntityManagerFactory.class));
        transaction = new TransactionTemplate(application.bean(PlatformTransactionManager.class));
        for (TestSalon salon : List.of(alpha, beta)) {
            UUID branch = salons.defaultBranch(salon);
            try {
                as(
                        salon,
                        () -> inTransaction(() -> {
                            application
                                    .bean(OpeningHoursRepository.class)
                                    .persist(OpeningHours.first(branch, week(), now()));
                            HolidayReview review =
                                    HolidayReview.firstAcceptance(branch, 2090, UUID.randomUUID(), now());
                            application.bean(HolidayReviewRepository.class).persist(review);
                            application
                                    .bean(HolidayChoiceRepository.class)
                                    .persist(HolidayChoice.made(
                                            branch,
                                            review.getId(),
                                            LocalDate.of(2090, 1, 14),
                                            com.salonplatform.staff.domain.HolidayChoiceKind.CLOSED,
                                            null,
                                            null,
                                            1,
                                            now()));
                            application
                                    .bean(ClosureRepository.class)
                                    .persist(Closure.of(
                                            branch, LocalDate.of(2090, 2, 1), "Training", UUID.randomUUID(), now()));
                            // F06-S01: a team member with a photo, a whole category and a single service.
                            MemberPhoto photo = application
                                    .bean(MemberPhotoRepository.class)
                                    .persist(MemberPhoto.uploaded(
                                            branch, "image/jpeg", 320, 320, new byte[] {1}, now()));
                            Member member = application
                                    .bean(MemberRepository.class)
                                    .saveAndFlush(Member.added(
                                            branch,
                                            new Member.Profile("Filter Person", null, null, null, true),
                                            null,
                                            now()));
                            member.changePhoto(photo.getId(), now());
                            application
                                    .bean(MemberCategoryRepository.class)
                                    .persist(MemberCategory.of(branch, member.getId(), UUID.randomUUID(), now()));
                            application
                                    .bean(MemberSingleServiceRepository.class)
                                    .persist(MemberSingleService.of(branch, member.getId(), UUID.randomUUID(), now()));
                            // F06-S02: their schedule's version, a weekday with a break, a date change with a break.
                            application
                                    .bean(MemberScheduleRepository.class)
                                    .persist(MemberSchedule.first(branch, member.getId(), now()));
                            MemberWeekday weekday = application
                                    .bean(MemberWeekdayRepository.class)
                                    .persist(MemberWeekday.of(
                                            branch,
                                            member.getId(),
                                            DayOfWeek.MONDAY,
                                            LocalTime.of(9, 0),
                                            LocalTime.of(18, 0),
                                            now()));
                            application.bean(MemberWeekdayRepository.class).flush();
                            application
                                    .bean(MemberWeekdayBreakRepository.class)
                                    .persist(MemberWeekdayBreak.of(
                                            branch,
                                            member.getId(),
                                            weekday.getId(),
                                            LocalTime.of(13, 0),
                                            LocalTime.of(14, 0),
                                            now()));
                            MemberDateChange change = application
                                    .bean(MemberDateChangeRepository.class)
                                    .persist(MemberDateChange.of(
                                            branch,
                                            member.getId(),
                                            LocalDate.of(2090, 3, 1),
                                            LocalTime.of(9, 0),
                                            LocalTime.of(13, 0),
                                            "Half day",
                                            UUID.randomUUID(),
                                            now()));
                            application.bean(MemberDateChangeRepository.class).flush();
                            application
                                    .bean(MemberDateChangeBreakRepository.class)
                                    .persist(MemberDateChangeBreak.of(
                                            branch,
                                            member.getId(),
                                            change.getId(),
                                            LocalTime.of(10, 0),
                                            LocalTime.of(10, 15),
                                            now()));
                            application
                                    .bean(MemberLeaveRepository.class)
                                    .persist(MemberLeave.of(
                                            branch,
                                            member.getId(),
                                            LocalDate.of(2090, 4, 1),
                                            LocalDate.of(2090, 4, 3),
                                            LeaveType.SICK,
                                            "Private",
                                            UUID.randomUUID(),
                                            now()));
                            application
                                    .bean(MemberBlockedTimeRepository.class)
                                    .persist(MemberBlockedTime.of(
                                            branch,
                                            member.getId(),
                                            LocalDate.of(2090, 5, 1),
                                            LocalTime.of(10, 0),
                                            LocalTime.of(11, 0),
                                            "Training",
                                            UUID.randomUUID(),
                                            now()));
                            return null;
                        }));
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    @AfterAll
    static void stop() {
        application.close();
    }

    private static Instant now() {
        return Instant.now();
    }

    private static List<WeekdayHours> week() {
        return java.util.Arrays.stream(DayOfWeek.values())
                .map(day -> new WeekdayHours(day, LocalTime.of(9, 0), LocalTime.of(18, 0)))
                .toList();
    }

    @Test
    void rowLevelSecurityIsReallyOutOfTheWayHereAndEachSalonWasStamped() {
        for (String table : StaffTablesRowLevelSecurityAloneIntegrationTest.TABLES) {
            assertThat(application.jdbc().queryForList("SELECT tenant_id FROM " + table, UUID.class))
                    .as(table)
                    .containsExactlyInAnyOrder(alpha.id(), beta.id());
        }
    }

    @Test
    void alphaReadsOnlyItsOwnRowsAndBetasAreNotFound() throws Exception {
        UUID betasBranch = salons.defaultBranch(beta);
        UUID betasClosure = database.asOwner()
                .queryForObject("SELECT id FROM staff_closure WHERE tenant_id = ?", UUID.class, beta.id());
        as(
                alpha,
                () -> inTransaction(() -> {
                    assertThat(application.bean(OpeningHoursRepository.class).findAll())
                            .extracting(OpeningHours::getTenantId)
                            .containsExactly(alpha.id());
                    assertThat(application.bean(OpeningHoursRepository.class).findByBranch(betasBranch))
                            .isEmpty();
                    assertThat(application.bean(HolidayReviewRepository.class).findAll())
                            .extracting(HolidayReview::getTenantId)
                            .containsExactly(alpha.id());
                    assertThat(application.bean(HolidayReviewRepository.class).lock(betasBranch, 2090))
                            .isEmpty();
                    assertThat(application
                                    .bean(HolidayChoiceRepository.class)
                                    .findBetween(betasBranch, LocalDate.of(2090, 1, 1), LocalDate.of(2090, 12, 31)))
                            .isEmpty();
                    assertThat(application.bean(ClosureRepository.class).findAll())
                            .extracting(Closure::getTenantId)
                            .containsExactly(alpha.id());
                    assertThat(application.bean(ClosureRepository.class).findById(betasClosure))
                            .isEmpty();
                    return null;
                }));
    }

    @Test
    void alphaReadsNoneOfBetasTeam() throws Exception {
        JdbcTemplate owner = database.asOwner();
        UUID betasMember =
                owner.queryForObject("SELECT id FROM staff_team_member WHERE tenant_id = ?", UUID.class, beta.id());
        UUID betasPhoto =
                owner.queryForObject("SELECT id FROM staff_member_photo WHERE tenant_id = ?", UUID.class, beta.id());
        UUID betasBranch = salons.defaultBranch(beta);
        as(
                alpha,
                () -> inTransaction(() -> {
                    assertThat(application.bean(MemberRepository.class).findAll())
                            .extracting(Member::getTenantId)
                            .containsExactly(alpha.id());
                    assertThat(application.bean(MemberRepository.class).findAny(betasMember))
                            .isEmpty();
                    assertThat(application.bean(MemberRepository.class).findActive(betasMember, betasBranch))
                            .isEmpty();
                    assertThat(application.bean(MemberRepository.class).active(betasBranch))
                            .isEmpty();
                    assertThat(application.bean(MemberPhotoRepository.class).findPhoto(betasPhoto))
                            .isEmpty();
                    assertThat(application.bean(MemberCategoryRepository.class).ofMembers(List.of(betasMember)))
                            .isEmpty();
                    assertThat(application
                                    .bean(MemberSingleServiceRepository.class)
                                    .ofMembers(List.of(betasMember)))
                            .isEmpty();
                    assertThat(application.bean(MemberRepository.class).countActive())
                            .isEqualTo(1);
                    assertThat(application.bean(MemberRepository.class).countActiveWithoutAccount())
                            .isEqualTo(1);
                    return null;
                }));
    }

    @Test
    void alphaReadsNoneOfBetasSchedules() throws Exception {
        JdbcTemplate owner = database.asOwner();
        UUID betasMember =
                owner.queryForObject("SELECT id FROM staff_team_member WHERE tenant_id = ?", UUID.class, beta.id());
        UUID betasChange = owner.queryForObject(
                "SELECT id FROM staff_member_date_change WHERE tenant_id = ?", UUID.class, beta.id());
        UUID betasBranch = salons.defaultBranch(beta);
        as(
                alpha,
                () -> inTransaction(() -> {
                    assertThat(application.bean(MemberRepository.class).lockActive(betasMember, betasBranch))
                            .isEmpty();
                    assertThat(application.bean(MemberScheduleRepository.class).findOf(betasMember))
                            .isEmpty();
                    assertThat(application.bean(MemberWeekdayRepository.class).ofMembers(List.of(betasMember)))
                            .isEmpty();
                    assertThat(application
                                    .bean(MemberWeekdayBreakRepository.class)
                                    .ofMembers(List.of(betasMember)))
                            .isEmpty();
                    MemberDateChangeRepository changes = application.bean(MemberDateChangeRepository.class);
                    assertThat(changes.upcoming(betasMember, LocalDate.of(2000, 1, 1)))
                            .isEmpty();
                    assertThat(changes.between(betasMember, LocalDate.of(2090, 1, 1), LocalDate.of(2090, 12, 31)))
                            .isEmpty();
                    assertThat(changes.countUpcoming(betasMember, LocalDate.of(2000, 1, 1)))
                            .isZero();
                    assertThat(changes.existsOn(betasMember, LocalDate.of(2090, 3, 1)))
                            .isFalse();
                    assertThat(changes.findOf(betasChange, betasMember)).isEmpty();
                    assertThat(application
                                    .bean(MemberDateChangeBreakRepository.class)
                                    .ofChanges(List.of(betasChange)))
                            .isEmpty();
                    return null;
                }));
    }

    @Test
    void alphaReadsNoneOfBetasLeaveOrBlockedTime() throws Exception {
        JdbcTemplate owner = database.asOwner();
        UUID betasMember =
                owner.queryForObject("SELECT id FROM staff_team_member WHERE tenant_id = ?", UUID.class, beta.id());
        UUID betasLeave =
                owner.queryForObject("SELECT id FROM staff_member_leave WHERE tenant_id = ?", UUID.class, beta.id());
        UUID betasBlock = owner.queryForObject(
                "SELECT id FROM staff_member_blocked_time WHERE tenant_id = ?", UUID.class, beta.id());
        LocalDate from = LocalDate.of(2000, 1, 1);
        LocalDate to = LocalDate.of(2099, 1, 1);
        as(
                alpha,
                () -> inTransaction(() -> {
                    MemberLeaveRepository leaves = application.bean(MemberLeaveRepository.class);
                    assertThat(leaves.findAll())
                            .extracting(MemberLeave::getTenantId)
                            .containsExactly(alpha.id());
                    assertThat(leaves.notEnded(betasMember, from)).isEmpty();
                    assertThat(leaves.overlapping(betasMember, from, to)).isEmpty();
                    assertThat(leaves.countNotEnded(betasMember, from)).isZero();
                    assertThat(leaves.nextOf(List.of(betasMember), from)).isEmpty();
                    assertThat(leaves.findOf(betasLeave, betasMember)).isEmpty();
                    MemberBlockedTimeRepository blocks = application.bean(MemberBlockedTimeRepository.class);
                    assertThat(blocks.findAll())
                            .extracting(MemberBlockedTime::getTenantId)
                            .containsExactly(alpha.id());
                    assertThat(blocks.upcoming(betasMember, from)).isEmpty();
                    assertThat(blocks.between(betasMember, from, to)).isEmpty();
                    assertThat(blocks.countUpcoming(betasMember, from)).isZero();
                    assertThat(blocks.overlaps(
                                    betasMember, LocalDate.of(2090, 5, 1), LocalTime.of(10, 0), LocalTime.of(11, 0)))
                            .isFalse();
                    assertThat(blocks.findOf(betasBlock, betasMember)).isEmpty();
                    return null;
                }));
    }

    @Test
    void alphaCannotRemoveBetasLeaveOrBlockedTime() throws Exception {
        JdbcTemplate owner = database.asOwner();
        UUID betasLeave =
                owner.queryForObject("SELECT id FROM staff_member_leave WHERE tenant_id = ?", UUID.class, beta.id());
        UUID betasBlock = owner.queryForObject(
                "SELECT id FROM staff_member_blocked_time WHERE tenant_id = ?", UUID.class, beta.id());
        as(
                alpha,
                () -> inTransaction(() -> {
                    assertThat(application.bean(MemberLeaveRepository.class).removeById(betasLeave))
                            .isZero();
                    assertThat(application
                                    .bean(MemberBlockedTimeRepository.class)
                                    .removeById(betasBlock))
                            .isZero();
                    return null;
                }));
        for (String table : List.of("staff_member_leave", "staff_member_blocked_time")) {
            assertThat(owner.queryForObject(
                            "SELECT count(*) FROM " + table + " WHERE tenant_id = ?", Long.class, beta.id()))
                    .as(table)
                    .isEqualTo(1);
        }
    }

    @Test
    void alphaCannotRemoveBetasScheduleRows() throws Exception {
        JdbcTemplate owner = database.asOwner();
        UUID betasMember =
                owner.queryForObject("SELECT id FROM staff_team_member WHERE tenant_id = ?", UUID.class, beta.id());
        UUID betasChange = owner.queryForObject(
                "SELECT id FROM staff_member_date_change WHERE tenant_id = ?", UUID.class, beta.id());
        as(
                alpha,
                () -> inTransaction(() -> {
                    assertThat(application
                                    .bean(MemberWeekdayBreakRepository.class)
                                    .removeOf(betasMember))
                            .isZero();
                    assertThat(application.bean(MemberWeekdayRepository.class).removeOf(betasMember))
                            .isZero();
                    assertThat(application
                                    .bean(MemberDateChangeBreakRepository.class)
                                    .removeOf(betasChange))
                            .isZero();
                    assertThat(application
                                    .bean(MemberDateChangeRepository.class)
                                    .removeById(betasChange))
                            .isZero();
                    return null;
                }));
        for (String table : List.of(
                "staff_member_weekday",
                "staff_member_weekday_break",
                "staff_member_date_change",
                "staff_member_date_change_break")) {
            assertThat(owner.queryForObject(
                            "SELECT count(*) FROM " + table + " WHERE tenant_id = ?", Long.class, beta.id()))
                    .as(table)
                    .isEqualTo(1);
        }
    }

    @Test
    void alphaCannotRemoveBetasTeamRows() throws Exception {
        JdbcTemplate owner = database.asOwner();
        UUID betasMember =
                owner.queryForObject("SELECT id FROM staff_team_member WHERE tenant_id = ?", UUID.class, beta.id());
        as(
                alpha,
                () -> inTransaction(() -> {
                    assertThat(application.bean(MemberCategoryRepository.class).removeOf(betasMember))
                            .isZero();
                    assertThat(application
                                    .bean(MemberSingleServiceRepository.class)
                                    .removeOf(betasMember))
                            .isZero();
                    return null;
                }));
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM staff_member_category WHERE tenant_id = ?", Long.class, beta.id()))
                .isEqualTo(1);
    }

    @Test
    void alphaCannotUpdateOrDeleteBetasRows() throws Exception {
        as(
                alpha,
                () -> inTransaction(() -> {
                    assertThat(entityManager
                                    .createQuery("delete from Closure c where c.tenantId = :beta")
                                    .setParameter("beta", beta.id())
                                    .executeUpdate())
                            .isZero();
                    assertThat(entityManager
                                    .createQuery(
                                            "update HolidayChoice c set c.choice = 'open' where c.tenantId = :beta")
                                    .setParameter("beta", beta.id())
                                    .executeUpdate())
                            .isZero();
                    assertThat(entityManager
                                    .createQuery("update OpeningHours h set h.version = 99 where h.tenantId = :beta")
                                    .setParameter("beta", beta.id())
                                    .executeUpdate())
                            .isZero();
                    return null;
                }));
        JdbcTemplate owner = database.asOwner();
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM staff_closure WHERE tenant_id = ?", Integer.class, beta.id()))
                .isEqualTo(1);
        assertThat(owner.queryForObject(
                        "SELECT choice FROM staff_holiday_choice WHERE tenant_id = ?", String.class, beta.id()))
                .isEqualTo("closed");
        assertThat(owner.queryForObject(
                        "SELECT version FROM staff_opening_hours WHERE tenant_id = ?", Long.class, beta.id()))
                .isEqualTo(1L);
    }

    @Test
    void withNoSalonNothingIsFound() {
        assertThat(inTransaction(
                        () -> application.bean(OpeningHoursRepository.class).findAll()))
                .isEmpty();
        assertThat(inTransaction(
                        () -> application.bean(HolidayReviewRepository.class).findAll()))
                .isEmpty();
        assertThat(inTransaction(
                        () -> application.bean(HolidayChoiceRepository.class).findAll()))
                .isEmpty();
        assertThat(inTransaction(() -> application.bean(ClosureRepository.class).findAll()))
                .isEmpty();
        assertThat(inTransaction(() -> application.bean(MemberRepository.class).findAll()))
                .isEmpty();
        assertThat(inTransaction(
                        () -> application.bean(MemberPhotoRepository.class).findAll()))
                .isEmpty();
        assertThat(inTransaction(
                        () -> application.bean(MemberCategoryRepository.class).findAll()))
                .isEmpty();
        assertThat(inTransaction(() ->
                        application.bean(MemberSingleServiceRepository.class).findAll()))
                .isEmpty();
    }

    private static <T> T as(TestSalon salon, Callable<T> work) throws Exception {
        return TenantContext.callAs(salons.asCurrent(salon), work);
    }

    private static <T> T inTransaction(Callable<T> work) {
        return transaction.execute(status -> {
            try {
                return work.call();
            } catch (RuntimeException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
    }
}
