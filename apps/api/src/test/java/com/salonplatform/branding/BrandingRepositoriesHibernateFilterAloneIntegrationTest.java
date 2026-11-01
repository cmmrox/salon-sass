package com.salonplatform.branding;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.branding.domain.ProfileContent;
import com.salonplatform.branding.entity.Branch;
import com.salonplatform.branding.entity.BranchContactDraft;
import com.salonplatform.branding.entity.ProfileDraft;
import com.salonplatform.branding.entity.PublishedProfile;
import com.salonplatform.branding.repository.BranchContactDraftRepository;
import com.salonplatform.branding.repository.BranchRepository;
import com.salonplatform.branding.repository.ProfileDraftRepository;
import com.salonplatform.branding.repository.PublishedProfileRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The first layer on its own for F04-S01's tables: Hibernate's tenant filter ({@code @TenantId}), with row-level
 * security disabled on them and every write granted in this database, so that Hibernate and nothing else stands
 * between one salon and another. The cases of docs/architecture/multi-tenancy.md, "Tests you must write", hold.
 */
class BrandingRepositoriesHibernateFilterAloneIntegrationTest {

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
        for (String table : BrandingTablesRowLevelSecurityAloneIntegrationTest.TABLES) {
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
    }

    @AfterAll
    static void stop() {
        application.close();
    }

    @Test
    void rowLevelSecurityIsReallyOutOfTheWayHere() {
        assertThat(application.jdbc().queryForList("SELECT tenant_id FROM branding_branch", UUID.class))
                .contains(alpha.id(), beta.id());
    }

    @Test
    void alphaReadsOnlyItsOwnRowsAndBetasAreNotFound() throws Exception {
        UUID betasBranch = salons.defaultBranch(beta);
        as(
                alpha,
                () -> inTransaction(() -> {
                    BranchRepository branches = application.bean(BranchRepository.class);
                    assertThat(branches.findAllInOrder())
                            .extracting(Branch::getTenantId)
                            .containsOnly(alpha.id());
                    assertThat(branches.findById(betasBranch)).isEmpty();
                    assertThat(branches.existsById(betasBranch)).isFalse();
                    assertThat(application
                                    .bean(BranchContactDraftRepository.class)
                                    .findAllInOrder())
                            .extracting(BranchContactDraft::getTenantId)
                            .containsOnly(alpha.id());
                    assertThat(application.bean(ProfileDraftRepository.class).findAll())
                            .extracting(ProfileDraft::getTenantId)
                            .containsExactly(alpha.id());
                    assertThat(application.bean(ProfileDraftRepository.class).lockCurrent())
                            .map(ProfileDraft::getTenantId)
                            .contains(alpha.id());
                    return null;
                }));
    }

    @Test
    void alphaCannotUpdateOrDeleteBetasRows() throws Exception {
        as(
                alpha,
                () -> inTransaction(() -> {
                    assertThat(entityManager
                                    .createQuery(
                                            "update ProfileDraft d set d.description = 'Taken' where d.tenantId = :beta")
                                    .setParameter("beta", beta.id())
                                    .executeUpdate())
                            .isZero();
                    assertThat(entityManager
                                    .createQuery("delete from BranchContactDraft c where c.tenantId = :beta")
                                    .setParameter("beta", beta.id())
                                    .executeUpdate())
                            .isZero();
                    return null;
                }));
        assertThat(database.asOwner()
                        .queryForObject(
                                "SELECT count(*) FROM branding_branch_contact_draft WHERE tenant_id = ?",
                                Integer.class,
                                beta.id()))
                .isEqualTo(1);
        assertThat(database.asOwner()
                        .queryForObject(
                                "SELECT description FROM branding_profile_draft WHERE tenant_id = ?",
                                String.class,
                                beta.id()))
                .isNull();
    }

    @Test
    void aPublicationIsStampedWithTheCurrentSalonAndWithNoSalonNothingIsFound() throws Exception {
        TestSalon gamma = salons.create("Filter Gamma", "#333333");
        ProfileContent content = new ProfileContent("Gamma words", null, null, null, List.of());
        as(
                gamma,
                () -> inTransaction(() -> application
                        .bean(PublishedProfileRepository.class)
                        .persist(PublishedProfile.first(content, UUID.randomUUID(), Instant.now()))));
        assertThat(database.asOwner()
                        .queryForList(
                                "SELECT tenant_id FROM branding_profile_published WHERE description = 'Gamma words'",
                                UUID.class))
                .containsExactly(gamma.id());

        assertThat(inTransaction(() -> application.bean(BranchRepository.class).findAll()))
                .isEmpty();
        assertThat(inTransaction(
                        () -> application.bean(ProfileDraftRepository.class).findCurrent()))
                .isEmpty();
        assertThat(inTransaction(
                        () -> application.bean(PublishedProfileRepository.class).findCurrent()))
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
