package com.salonplatform.catalogue;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.catalogue.entity.Category;
import com.salonplatform.catalogue.entity.PopularSetting;
import com.salonplatform.catalogue.entity.SalonProduct;
import com.salonplatform.catalogue.entity.SalonService;
import com.salonplatform.catalogue.entity.ServicePhoto;
import com.salonplatform.catalogue.entity.TaxVersion;
import com.salonplatform.catalogue.entity.TermsVariant;
import com.salonplatform.catalogue.entity.TermsVersion;
import com.salonplatform.catalogue.repository.CategoryListRepository;
import com.salonplatform.catalogue.repository.CategoryRepository;
import com.salonplatform.catalogue.repository.PopularSettingRepository;
import com.salonplatform.catalogue.repository.SalonProductRepository;
import com.salonplatform.catalogue.repository.SalonServiceRepository;
import com.salonplatform.catalogue.repository.ServicePhotoRepository;
import com.salonplatform.catalogue.repository.TaxVersionRepository;
import com.salonplatform.catalogue.repository.TermsVariantRepository;
import com.salonplatform.catalogue.repository.TermsVersionRepository;
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
 * The first layer on its own for F05-S01's, F05-S02's and F05-S03's tables: Hibernate's tenant filter ({@code @TenantId}), with row-level
 * security disabled on them and every write granted in this database, so that Hibernate and nothing else stands
 * between one salon and another. The cases of docs/architecture/multi-tenancy.md, "Tests you must write", hold.
 */
class CatalogueHibernateFilterAloneIntegrationTest {

    private static TestDatabase database;
    private static StartedApplication application;
    private static TestSalons salons;
    private static TestSalon alpha;
    private static TestSalon beta;
    private static UUID betasService;
    private static EntityManager entityManager;
    private static TransactionTemplate transaction;

    @BeforeAll
    static void applicationOnADatabaseWithoutRowLevelSecurity() {
        database = TestDatabase.createEmpty(TestPostgres.shared());
        application = database.start();
        JdbcTemplate owner = database.asOwner();
        for (String table : CatalogueRowLevelSecurityAloneIntegrationTest.TABLES) {
            owner.execute("ALTER TABLE " + table + " NO FORCE ROW LEVEL SECURITY");
            owner.execute("ALTER TABLE " + table + " DISABLE ROW LEVEL SECURITY");
            owner.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON " + table + " TO " + TestPostgres.APP_USER);
        }
        salons = new TestSalons(owner);
        alpha = salons.create("Filter Alpha", "#111111");
        beta = salons.create("Filter Beta", "#222222");
        CatalogueRows.serviceWithPhoto(owner, alpha, salons.defaultBranch(alpha), "Haircut");
        betasService = CatalogueRows.serviceWithPhoto(owner, beta, salons.defaultBranch(beta), "Haircut");
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
        for (String table : CatalogueRowLevelSecurityAloneIntegrationTest.TABLES) {
            assertThat(application.jdbc().queryForList("SELECT DISTINCT tenant_id FROM " + table, UUID.class))
                    .as(table)
                    .contains(alpha.id(), beta.id());
        }
    }

    @Test
    void alphaReadsOnlyItsOwnRowsAndBetasAreNotFound() throws Exception {
        UUID betasBranch = salons.defaultBranch(beta);
        JdbcTemplate owner = database.asOwner();
        UUID betasCategory = owner.queryForObject(
                "SELECT id FROM catalogue_category WHERE tenant_id = ? AND position = 1", UUID.class, beta.id());
        UUID betasTerms = owner.queryForObject(
                "SELECT id FROM catalogue_terms_version WHERE tenant_id = ?", UUID.class, beta.id());
        UUID betasPhoto =
                owner.queryForObject("SELECT id FROM catalogue_photo WHERE tenant_id = ?", UUID.class, beta.id());
        UUID betasTax =
                owner.queryForObject("SELECT id FROM catalogue_tax_version WHERE tenant_id = ?", UUID.class, beta.id());
        UUID betasProduct =
                owner.queryForObject("SELECT id FROM catalogue_product WHERE tenant_id = ?", UUID.class, beta.id());
        as(
                alpha,
                () -> inTransaction(() -> {
                    assertThat(bean(CategoryRepository.class).findAll())
                            .extracting(Category::getTenantId)
                            .containsOnly(alpha.id());
                    assertThat(bean(CategoryRepository.class).findInBranch(betasCategory, betasBranch))
                            .isEmpty();
                    assertThat(bean(CategoryRepository.class).inOrder(betasBranch))
                            .isEmpty();
                    assertThat(bean(CategoryListRepository.class).findOfBranch(betasBranch))
                            .isEmpty();
                    assertThat(bean(SalonServiceRepository.class).findAny(betasService))
                            .isEmpty();
                    assertThat(bean(SalonServiceRepository.class).live(betasBranch))
                            .isEmpty();
                    assertThat(bean(TermsVersionRepository.class).findVersion(betasTerms))
                            .isEmpty();
                    assertThat(bean(ServicePhotoRepository.class).findPhoto(betasPhoto))
                            .isEmpty();
                    assertThat(bean(TermsVariantRepository.class).findOfVersions(List.of(betasTerms)))
                            .isEmpty();
                    assertThat(bean(TermsVariantRepository.class).findAll())
                            .extracting(TermsVariant::getTenantId)
                            .containsOnly(alpha.id());
                    assertThat(bean(PopularSettingRepository.class).findOfBranch(betasBranch))
                            .isEmpty();
                    assertThat(bean(PopularSettingRepository.class).findAll())
                            .extracting(PopularSetting::getTenantId)
                            .containsOnly(alpha.id());
                    assertThat(bean(SalonServiceRepository.class).findAll())
                            .extracting(SalonService::getTenantId)
                            .containsOnly(alpha.id());
                    assertThat(bean(TaxVersionRepository.class).findVersion(betasTax))
                            .isEmpty();
                    assertThat(bean(TaxVersionRepository.class).findFirstByBranchIdOrderByVersionDesc(betasBranch))
                            .isEmpty();
                    assertThat(bean(TaxVersionRepository.class).findAll())
                            .extracting(TaxVersion::getTenantId)
                            .containsOnly(alpha.id());
                    assertThat(bean(SalonProductRepository.class).findInBranch(betasProduct, betasBranch))
                            .isEmpty();
                    assertThat(bean(SalonProductRepository.class).byName(betasBranch))
                            .isEmpty();
                    assertThat(bean(SalonProductRepository.class).findAll())
                            .extracting(SalonProduct::getTenantId)
                            .containsOnly(alpha.id());
                    return null;
                }));
    }

    @Test
    void alphaCannotChangeOrDeleteBetasRows() throws Exception {
        as(
                alpha,
                () -> inTransaction(() -> {
                    for (String entity : List.of(
                            "Category",
                            "SalonService",
                            "ServicePhoto",
                            "TermsVariant",
                            "PopularSetting",
                            "SalonProduct")) {
                        assertThat(entityManager
                                        .createQuery("delete from " + entity + " e where e.tenantId = :beta")
                                        .setParameter("beta", beta.id())
                                        .executeUpdate())
                                .as(entity)
                                .isZero();
                    }
                    assertThat(entityManager
                                    .createQuery("update SalonService s set s.name = 'Taken' where s.tenantId = :beta")
                                    .setParameter("beta", beta.id())
                                    .executeUpdate())
                            .isZero();
                    assertThat(entityManager
                                    .createQuery("update SalonProduct p set p.name = 'Taken' where p.tenantId = :beta")
                                    .setParameter("beta", beta.id())
                                    .executeUpdate())
                            .isZero();
                    assertThat(entityManager
                                    .createQuery(
                                            "update PopularSetting p set p.mode = 'most_booked' where p.tenantId = :beta")
                                    .setParameter("beta", beta.id())
                                    .executeUpdate())
                            .isZero();
                    return null;
                }));
        assertThat(database.asOwner()
                        .queryForObject(
                                "SELECT name FROM catalogue_service WHERE tenant_id = ?", String.class, beta.id()))
                .isEqualTo("Haircut");
    }

    @Test
    void rowsWrittenWhileAlphaIsSetAreAlphasAndBetaNeverSeesThem() throws Exception {
        UUID branch = salons.defaultBranch(alpha);
        UUID[] ids = as(
                alpha,
                () -> inTransaction(() -> {
                    Instant now = Instant.now();
                    Category category = bean(CategoryRepository.class)
                            .saveAndFlush(Category.added(branch, "Filter written", 90, now));
                    ServicePhoto photo = bean(ServicePhotoRepository.class)
                            .persist(ServicePhoto.uploaded(branch, "image/jpeg", 1, 1, new byte[] {1}, now));
                    UUID service = UUID.randomUUID();
                    TermsVersion terms = TermsVersion.made(
                            branch,
                            service,
                            1,
                            new TermsVersion.Terms(
                                    100_00,
                                    "LKR",
                                    List.of(new ServiceSegment(SegmentKind.ACTIVE, 30)),
                                    null,
                                    List.of(),
                                    new ServiceDeposit.SalonRule()),
                            UUID.randomUUID(),
                            now);
                    bean(SalonServiceRepository.class)
                            .save(SalonService.created(
                                    service,
                                    branch,
                                    new SalonService.Details("Filter written", category.getId(), null, true),
                                    terms.getId(),
                                    photo.getId(),
                                    now));
                    bean(TermsVersionRepository.class).persist(terms);
                    TermsVariant variant = bean(TermsVariantRepository.class)
                            .persist(TermsVariant.of(
                                    terms,
                                    1,
                                    new ServiceVariant(
                                            UUID.randomUUID(),
                                            "Short",
                                            100_00,
                                            List.of(new ServiceSegment(SegmentKind.ACTIVE, 30)))));
                    TaxVersion tax = bean(TaxVersionRepository.class)
                            .persist(TaxVersion.saved(
                                    branch,
                                    90,
                                    new TaxSettingsVersion.Vat("VAT", 18, false, null),
                                    new TaxSettingsVersion.ServiceCharge("Service charge", false, 0, false),
                                    List.of("vat.registered"),
                                    UUID.randomUUID(),
                                    now));
                    SalonProduct product = bean(SalonProductRepository.class)
                            .saveAndFlush(SalonProduct.added(
                                    branch, new SalonProduct.Details("Filter written", 100, "LKR", true, true), now));
                    entityManager.flush();
                    return new UUID[] {
                        category.getId(),
                        service,
                        terms.getId(),
                        photo.getId(),
                        variant.getId(),
                        tax.getId(),
                        product.getId()
                    };
                }));
        JdbcTemplate owner = database.asOwner();
        assertThat(owner.queryForObject("SELECT tenant_id FROM catalogue_category WHERE id = ?", UUID.class, ids[0]))
                .isEqualTo(alpha.id());
        assertThat(owner.queryForObject("SELECT tenant_id FROM catalogue_service WHERE id = ?", UUID.class, ids[1]))
                .isEqualTo(alpha.id());
        assertThat(owner.queryForObject(
                        "SELECT tenant_id FROM catalogue_terms_version WHERE id = ?", UUID.class, ids[2]))
                .isEqualTo(alpha.id());
        assertThat(owner.queryForObject("SELECT tenant_id FROM catalogue_photo WHERE id = ?", UUID.class, ids[3]))
                .isEqualTo(alpha.id());
        assertThat(owner.queryForObject(
                        "SELECT tenant_id FROM catalogue_terms_variant WHERE id = ?", UUID.class, ids[4]))
                .isEqualTo(alpha.id());
        assertThat(owner.queryForObject("SELECT tenant_id FROM catalogue_tax_version WHERE id = ?", UUID.class, ids[5]))
                .isEqualTo(alpha.id());
        assertThat(owner.queryForObject("SELECT tenant_id FROM catalogue_product WHERE id = ?", UUID.class, ids[6]))
                .isEqualTo(alpha.id());
        as(
                beta,
                () -> inTransaction(() -> {
                    assertThat(bean(SalonServiceRepository.class).findAny(ids[1]))
                            .isEmpty();
                    assertThat(bean(TermsVersionRepository.class).findVersion(ids[2]))
                            .isEmpty();
                    assertThat(bean(ServicePhotoRepository.class).findPhoto(ids[3]))
                            .isEmpty();
                    assertThat(bean(TermsVariantRepository.class).findOfVersions(List.of(ids[2])))
                            .isEmpty();
                    assertThat(bean(TaxVersionRepository.class).findVersion(ids[5]))
                            .isEmpty();
                    assertThat(bean(SalonProductRepository.class).findInBranch(ids[6], branch))
                            .isEmpty();
                    return null;
                }));
    }

    @Test
    void withNoSalonNothingIsFound() {
        assertThat(inTransaction(() -> bean(CategoryRepository.class).findAll()))
                .isEmpty();
        assertThat(inTransaction(() -> bean(SalonServiceRepository.class).findAll()))
                .isEmpty();
        assertThat(inTransaction(() -> bean(TermsVersionRepository.class).findAll()))
                .isEmpty();
        assertThat(inTransaction(() -> bean(ServicePhotoRepository.class).findAll()))
                .isEmpty();
        assertThat(inTransaction(() -> bean(CategoryListRepository.class).findAll()))
                .isEmpty();
        assertThat(inTransaction(() -> bean(TermsVariantRepository.class).findAll()))
                .isEmpty();
        assertThat(inTransaction(() -> bean(PopularSettingRepository.class).findAll()))
                .isEmpty();
        assertThat(inTransaction(() -> bean(TaxVersionRepository.class).findAll()))
                .isEmpty();
        assertThat(inTransaction(() -> bean(SalonProductRepository.class).findAll()))
                .isEmpty();
    }

    private static <T> T bean(Class<T> type) {
        return application.bean(type);
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
