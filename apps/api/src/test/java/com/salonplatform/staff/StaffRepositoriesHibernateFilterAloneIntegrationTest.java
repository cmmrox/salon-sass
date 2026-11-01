package com.salonplatform.staff;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.staff.entity.Closure;
import com.salonplatform.staff.entity.HolidayChoice;
import com.salonplatform.staff.entity.HolidayReview;
import com.salonplatform.staff.entity.OpeningHours;
import com.salonplatform.staff.repository.ClosureRepository;
import com.salonplatform.staff.repository.HolidayChoiceRepository;
import com.salonplatform.staff.repository.HolidayReviewRepository;
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
