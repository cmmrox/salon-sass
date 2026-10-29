package com.salonplatform.branding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.salonplatform.branding.domain.SalonBrand;
import com.salonplatform.branding.infrastructure.SalonBrandRepository;
import com.salonplatform.shared.tenancy.CurrentTenant;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.testing.StartedApplication;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import com.salonplatform.testing.TestSalons;
import com.salonplatform.testing.TestSalons.TestSalon;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.lang.reflect.Field;
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
 * The first layer on its own: Hibernate's tenant filter ({@code @TenantId}), with row-level
 * security out of the way. In this database the policy is disabled on {@code salon_brand} and the
 * application's role may write it, so that Hibernate and nothing else stands between one salon and
 * another; the first test proves the database itself no longer holds anything back. The four cases
 * of docs/architecture/multi-tenancy.md, "Tests you must write", hold.
 */
class SalonBrandHibernateFilterAloneIntegrationTest {

    private static TestDatabase database;

    private static StartedApplication application;

    private static TestSalon alpha;

    private static TestSalon beta;

    private static SalonBrandRepository brands;

    private static EntityManager entityManager;

    private static TransactionTemplate transaction;

    @BeforeAll
    static void applicationOnADatabaseWithoutRowLevelSecurity() {
        database = TestDatabase.createEmpty(TestPostgres.shared());
        application = database.start();
        JdbcTemplate owner = database.asOwner();
        owner.execute("ALTER TABLE salon_brand NO FORCE ROW LEVEL SECURITY");
        owner.execute("ALTER TABLE salon_brand DISABLE ROW LEVEL SECURITY");
        owner.execute("GRANT INSERT, UPDATE, DELETE ON salon_brand TO " + TestPostgres.APP_USER);
        TestSalons salons = new TestSalons(owner);
        alpha = salons.create("Alpha Studio", "#D6086A");
        beta = salons.create("Beta Lounge", "#1F2937");

        brands = application.bean(SalonBrandRepository.class);
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
        // The application's own role, outside any transaction and with no salon set: every row.
        assertThat(application.jdbc().queryForList("SELECT tenant_id FROM salon_brand", UUID.class))
                .contains(alpha.id(), beta.id());
    }

    @Test
    void alphaReadsOnlyAlphasRowAndBetasIsNotFound() throws Exception {
        as(
                alpha,
                () -> inTransaction(() -> {
                    assertThat(brands.findAll())
                            .extracting(SalonBrand::getTenantId)
                            .containsExactly(alpha.id());
                    assertThat(brands.findCurrent())
                            .map(SalonBrand::getDisplayName)
                            .contains(alpha.name());
                    assertThat(brands.findById(beta.id()))
                            .as("beta's row, by its id")
                            .isEmpty();
                    assertThat(brands.count()).isEqualTo(1);
                    return null;
                }));
    }

    @Test
    void alphaCannotUpdateOrDeleteBetasRow() throws Exception {
        as(
                alpha,
                () -> inTransaction(() -> {
                    assertThat(entityManager
                                    .createQuery(
                                            "update SalonBrand b set b.displayName = 'Taken' where b.tenantId = :beta")
                                    .setParameter("beta", beta.id())
                                    .executeUpdate())
                            .as("rows updated")
                            .isZero();
                    assertThat(entityManager
                                    .createQuery("delete from SalonBrand b where b.tenantId = :beta")
                                    .setParameter("beta", beta.id())
                                    .executeUpdate())
                            .as("rows deleted")
                            .isZero();
                    brands.deleteById(beta.id());
                    return null;
                }));

        assertThat(database.asOwner()
                        .queryForMap(
                                "SELECT display_name, brand_colour FROM salon_brand WHERE tenant_id = ?", beta.id()))
                .containsEntry("display_name", beta.name())
                .containsEntry("brand_colour", beta.brandColour());
    }

    @Test
    void anInsertIsStampedWithTheCurrentSalonAndOneNamingAnotherSalonIsRefused() throws Exception {
        TestSalon delta = new TestSalons(database.asOwner()).createWithoutBrand("active");
        TestSalon epsilon = new TestSalons(database.asOwner()).createWithoutBrand("active");

        as(delta, () -> inTransaction(() -> brands.saveAndFlush(new SalonBrand("Delta", "#00AA00"))));
        assertThat(database.asOwner()
                        .queryForObject(
                                "SELECT display_name FROM salon_brand WHERE tenant_id = ?", String.class, delta.id()))
                .isEqualTo("Delta");

        // The key of this table is the salon, generated by @TenantId from the current salon. Saved
        // the way a repository saves an entity that already carries a key (merged), Hibernate looks
        // for the row under alpha, finds none, and refuses rather than insert one for epsilon.
        // Without @TenantId the key would be assigned, and epsilon's row would be inserted.
        SalonBrand forEpsilon = new SalonBrand("Not mine", "#123456");
        setTenantId(forEpsilon, epsilon.id());
        assertThatThrownBy(() -> as(alpha, () -> inTransaction(() -> brands.saveAndFlush(forEpsilon))))
                .isNotNull();

        // Persisted directly with the key already set: refused too.
        SalonBrand persisted = new SalonBrand("Not mine either", "#123456");
        setTenantId(persisted, epsilon.id());
        assertThatThrownBy(() -> as(
                        alpha,
                        () -> inTransaction(() -> {
                            entityManager.persist(persisted);
                            entityManager.flush();
                            return null;
                        })))
                .isNotNull();

        assertThat(database.asOwner()
                        .queryForObject(
                                "SELECT count(*) FROM salon_brand WHERE tenant_id = ?", Integer.class, epsilon.id()))
                .isZero();
    }

    @Test
    void withNoSalonNothingIsFoundAndNothingCanBeCreated() {
        List<SalonBrand> found = inTransaction(brands::findAll);
        assertThat(found).isEmpty();
        assertThat(inTransaction(brands::findCurrent)).isEmpty();

        assertThatThrownBy(() -> inTransaction(() -> brands.saveAndFlush(new SalonBrand("Nobody's", "#123456"))))
                .as("stamped with the nil id, which no salon has")
                .isNotNull();
        assertThat(database.asOwner()
                        .queryForObject(
                                "SELECT count(*) FROM salon_brand WHERE display_name = ?", Integer.class, "Nobody's"))
                .isZero();
    }

    private static <T> T as(TestSalon salon, Callable<T> work) throws Exception {
        return TenantContext.callAs(new CurrentTenant(salon.id(), salon.slug(), TestSalons.DEFAULT_TIME_ZONE), work);
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

    private static void setTenantId(SalonBrand brand, UUID tenantId) throws ReflectiveOperationException {
        Field field = SalonBrand.class.getDeclaredField("tenantId");
        field.setAccessible(true);
        field.set(brand, tenantId);
    }
}
