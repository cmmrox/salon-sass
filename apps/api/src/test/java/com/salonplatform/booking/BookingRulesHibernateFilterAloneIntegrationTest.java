package com.salonplatform.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.booking.entity.BookingRulesVersion;
import com.salonplatform.booking.repository.BookingRulesVersionRepository;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.testing.StartedApplication;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import com.salonplatform.testing.TestSalons;
import com.salonplatform.testing.TestSalons.TestSalon;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The first layer on its own for F04-S04's table: Hibernate's tenant filter ({@code @TenantId}), with row-level
 * security disabled on it and every write granted in this database, so that Hibernate and nothing else stands
 * between one salon and another. The cases of docs/architecture/multi-tenancy.md, "Tests you must write", hold.
 */
class BookingRulesHibernateFilterAloneIntegrationTest {

    private static final BookingTerms TERMS = new BookingTerms(
            15,
            60,
            60,
            new DepositRule.None(),
            24,
            new Retention.Nothing(),
            new Retention.Deposit(),
            new Rescheduling.NotOnline());

    private static TestDatabase database;
    private static StartedApplication application;
    private static TestSalons salons;
    private static TestSalon alpha;
    private static TestSalon beta;
    private static EntityManager entityManager;
    private static TransactionTemplate transaction;

    @BeforeAll
    static void applicationOnADatabaseWithoutRowLevelSecurity() throws Exception {
        database = TestDatabase.createEmpty(TestPostgres.shared());
        application = database.start();
        JdbcTemplate owner = database.asOwner();
        for (String table : BookingRulesRowLevelSecurityAloneIntegrationTest.TABLES) {
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
            as(
                    salon,
                    () -> inTransaction(() -> {
                        application
                                .bean(BookingRulesVersionRepository.class)
                                .persist(BookingRulesVersion.saved(
                                        branch, 1, TERMS, "LKR", List.of("deposit"), UUID.randomUUID(), Instant.now()));
                        return null;
                    }));
        }
    }

    @AfterAll
    static void stop() {
        application.close();
    }

    private static BookingRulesVersionRepository versions() {
        return application.bean(BookingRulesVersionRepository.class);
    }

    @Test
    void rowLevelSecurityIsReallyOutOfTheWayHereAndEachSalonWasStamped() {
        assertThat(application.jdbc().queryForList("SELECT tenant_id FROM booking_rules_version", UUID.class))
                .contains(alpha.id(), beta.id())
                .containsOnly(alpha.id(), beta.id());
    }

    @Test
    void alphaReadsOnlyItsOwnVersionsAndBetasAreNotFound() throws Exception {
        UUID betasBranch = salons.defaultBranch(beta);
        UUID betasVersion = database.asOwner()
                .queryForObject("SELECT id FROM booking_rules_version WHERE tenant_id = ?", UUID.class, beta.id());
        as(
                alpha,
                () -> inTransaction(() -> {
                    assertThat(versions().findAll())
                            .extracting(BookingRulesVersion::getTenantId)
                            .containsOnly(alpha.id());
                    assertThat(versions().findVersion(betasVersion)).isEmpty();
                    assertThat(versions().findFirstByBranchIdOrderByVersionDesc(betasBranch))
                            .isEmpty();
                    assertThat(versions().history(betasBranch, PageRequest.of(0, 20)))
                            .isEmpty();
                    return null;
                }));
    }

    @Test
    void alphaCannotDeleteBetasVersions() throws Exception {
        as(
                alpha,
                () -> inTransaction(() -> {
                    assertThat(entityManager
                                    .createQuery("delete from BookingRulesVersion v where v.tenantId = :beta")
                                    .setParameter("beta", beta.id())
                                    .executeUpdate())
                            .isZero();
                    return null;
                }));
        assertThat(database.asOwner()
                        .queryForObject(
                                "SELECT count(*) FROM booking_rules_version WHERE tenant_id = ?",
                                Integer.class,
                                beta.id()))
                .isEqualTo(1);
    }

    @Test
    void aVersionSavedWhileAlphaIsSetIsAlphasAndBetaNeverSeesIt() throws Exception {
        UUID id = as(
                alpha,
                () -> inTransaction(() -> {
                    BookingRulesVersion row = BookingRulesVersion.saved(
                            UUID.randomUUID(), 7, TERMS, "LKR", List.of(), UUID.randomUUID(), Instant.now());
                    versions().persist(row);
                    return row.getId();
                }));
        assertThat(database.asOwner()
                        .queryForObject("SELECT tenant_id FROM booking_rules_version WHERE id = ?", UUID.class, id))
                .isEqualTo(alpha.id());
        as(
                beta,
                () -> inTransaction(() -> {
                    assertThat(versions().findVersion(id)).isEmpty();
                    return null;
                }));
    }

    @Test
    void withNoSalonNothingIsFound() {
        assertThat(inTransaction(() -> versions().findAll())).isEmpty();
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
