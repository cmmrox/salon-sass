package com.salonplatform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.salonplatform.identity.IdentityRows.SalonRows;
import com.salonplatform.identity.domain.SessionKind;
import com.salonplatform.identity.domain.StaffRole;
import com.salonplatform.identity.entity.AccessSession;
import com.salonplatform.identity.entity.CustomerAccount;
import com.salonplatform.identity.entity.CustomerChallenge;
import com.salonplatform.identity.entity.IdentityGuard;
import com.salonplatform.identity.entity.MfaChallenge;
import com.salonplatform.identity.entity.MfaStep;
import com.salonplatform.identity.entity.RateBucket;
import com.salonplatform.identity.entity.RefreshHistory;
import com.salonplatform.identity.entity.StaffAccount;
import com.salonplatform.identity.repository.AccessSessionRepository;
import com.salonplatform.identity.repository.CustomerAccountRepository;
import com.salonplatform.identity.repository.CustomerChallengeRepository;
import com.salonplatform.identity.repository.IdentityGuardRepository;
import com.salonplatform.identity.repository.MfaChallengeRepository;
import com.salonplatform.identity.repository.MfaStepRepository;
import com.salonplatform.identity.repository.RateBucketRepository;
import com.salonplatform.identity.repository.RefreshHistoryRepository;
import com.salonplatform.identity.repository.StaffAccountRepository;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.testing.StartedApplication;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import com.salonplatform.testing.TestSalons;
import com.salonplatform.testing.TestSalons.TestSalon;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The first layer on its own for every identity repository: Hibernate's tenant filter ({@code @TenantId}), with
 * row-level security out of the way. In this database the policies of the identity tables are disabled and the
 * application's role may write them, so that Hibernate and nothing else stands between one salon and another;
 * the first test proves the database no longer holds anything back. With two salons' rows in every table, alpha
 * finds none of beta's by id, by listing or by any query of the repository, changes and removes none of them
 * through any repository method, and cannot create a row pointing at one; with no salon, nothing is found.
 * {@link IdentityTablesRowLevelSecurityAloneIntegrationTest} is the other layer on its own.
 */
class IdentityRepositoriesHibernateFilterAloneIntegrationTest {

    private static TestDatabase database;

    private static StartedApplication application;

    private static TestSalons salons;

    private static IdentityRows identityRows;

    private static EntityManager entityManager;

    private static TransactionTemplate transaction;

    private TestSalon alpha;

    private TestSalon beta;

    private SalonRows alphas;

    private SalonRows betas;

    @BeforeAll
    static void applicationOnADatabaseWithoutRowLevelSecurity() {
        database = TestDatabase.createEmpty(TestPostgres.shared());
        application = database.start();
        JdbcTemplate owner = database.asOwner();
        for (String table : IdentityRows.TABLES) {
            owner.execute("ALTER TABLE " + table + " NO FORCE ROW LEVEL SECURITY");
            owner.execute("ALTER TABLE " + table + " DISABLE ROW LEVEL SECURITY");
            owner.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON " + table + " TO " + TestPostgres.APP_USER);
        }
        salons = new TestSalons(owner);
        identityRows = new IdentityRows(owner);
        entityManager =
                SharedEntityManagerCreator.createSharedEntityManager(application.bean(EntityManagerFactory.class));
        transaction = new TransactionTemplate(application.bean(PlatformTransactionManager.class));
    }

    @AfterAll
    static void stop() {
        application.close();
    }

    @BeforeEach
    void twoSalonsWithARowInEveryIdentityTable() {
        alpha = salons.create("Filter Alpha", "#111111");
        beta = salons.create("Filter Beta", "#222222");
        alphas = identityRows.writeFor(alpha);
        betas = identityRows.writeFor(beta);
    }

    @Test
    void rowLevelSecurityIsReallyOutOfTheWayHere() {
        // The application's own role, outside any transaction and with no salon set: both salons' rows.
        for (String table : IdentityRows.TABLES) {
            assertThat(application.jdbc().queryForList("SELECT DISTINCT tenant_id FROM " + table, UUID.class))
                    .as(table)
                    .contains(alpha.id(), beta.id());
        }
    }

    @Test
    void staffAccountsOfAnotherSalonAreNeitherFoundNorChanged() {
        StaffAccountRepository accounts = application.bean(StaffAccountRepository.class);
        Map<String, List<Map<String, Object>>> before = identityRows.snapshotOf(beta.id());

        asAlpha(() -> {
            seesOnlyAlphas(accounts, StaffAccount::getTenantId, betas.staff());
            assertThat(accounts.findByIdentifier(betas.email())).isEmpty();
            assertThat(accounts.findByIdentifier(betas.mobile())).isEmpty();
            assertThat(accounts.findHolding(betas.email(), betas.mobile())).isEmpty();
            assertThat(accounts.countHolding(betas.email(), betas.mobile())).isZero();
            assertThat(accounts.countByActiveTrue()).isEqualTo(1);
            assertThat(accounts.countByActiveTrueAndRole(StaffRole.OWNER)).isEqualTo(1);
            assertThat(accounts.findAll(PageRequest.of(0, 10)))
                    .extracting(StaffAccount::getTenantId)
                    .containsOnly(alpha.id());
        });
        attemptsToChangeOrRemove(accounts, StaffAccount::getTenantId, betas.staff());

        assertThat(identityRows.snapshotOf(beta.id())).isEqualTo(before);
    }

    @Test
    void customersOfAnotherSalonAreNeitherFoundNorChanged() {
        CustomerAccountRepository customers = application.bean(CustomerAccountRepository.class);
        Map<String, List<Map<String, Object>>> before = identityRows.snapshotOf(beta.id());

        asAlpha(() -> {
            seesOnlyAlphas(customers, CustomerAccount::getTenantId, betas.customer());
            assertThat(customers.findFirstByMobile(betas.customerMobile())).isEmpty();
        });
        attemptsToChangeOrRemove(customers, CustomerAccount::getTenantId, betas.customer());

        assertThat(identityRows.snapshotOf(beta.id())).isEqualTo(before);
    }

    @Test
    void sessionsOfAnotherSalonAreNeitherFoundNorChanged() {
        AccessSessionRepository sessions = application.bean(AccessSessionRepository.class);
        Map<String, List<Map<String, Object>>> before = identityRows.snapshotOf(beta.id());

        asAlpha(() -> {
            seesOnlyAlphas(sessions, AccessSession::getTenantId, betas.staffSession());
            assertThat(sessions.findById(betas.customerSession())).isEmpty();
            assertThat(sessions.findFirstByRefreshHash(betas.staffRefreshHash()))
                    .isEmpty();
            Instant now = Instant.now();
            assertThat(sessions.findLiveOfStaff(betas.staff(), now, PageRequest.of(0, 10)))
                    .isEmpty();
            assertThat(sessions.findLiveOfCustomer(betas.customer(), now, PageRequest.of(0, 10)))
                    .isEmpty();
            assertThat(sessions.findByKindAndAccountIdAndRevokedAtIsNull(SessionKind.STAFF, betas.staff()))
                    .isEmpty();
            assertThat(sessions.findByKindAndCustomerAccountIdAndRevokedAtIsNull(
                            SessionKind.CUSTOMER, betas.customer()))
                    .isEmpty();
        });
        attemptsToChangeOrRemove(sessions, AccessSession::getTenantId, betas.staffSession());

        assertThat(identityRows.snapshotOf(beta.id())).isEqualTo(before);
    }

    @Test
    void spentRefreshCredentialsOfAnotherSalonAreNeitherFoundNorChanged() {
        RefreshHistoryRepository history = application.bean(RefreshHistoryRepository.class);
        Map<String, List<Map<String, Object>>> before = identityRows.snapshotOf(beta.id());

        asAlpha(() -> {
            seesOnlyAlphas(history, RefreshHistory::getTenantId, betas.refresh());
            assertThat(history.findFirstByCredentialHash(betas.spentRefreshHash()))
                    .isEmpty();
        });
        attemptsToChangeOrRemove(history, RefreshHistory::getTenantId, betas.refresh());

        assertThat(identityRows.snapshotOf(beta.id())).isEqualTo(before);
    }

    @Test
    void secondStepChallengesOfAnotherSalonAreNeitherFoundNorChanged() {
        MfaChallengeRepository challenges = application.bean(MfaChallengeRepository.class);
        Map<String, List<Map<String, Object>>> before = identityRows.snapshotOf(beta.id());

        asAlpha(() -> seesOnlyAlphas(challenges, MfaChallenge::getTenantId, betas.mfaChallenge()));
        attemptsToChangeOrRemove(challenges, MfaChallenge::getTenantId, betas.mfaChallenge());

        assertThat(identityRows.snapshotOf(beta.id())).isEqualTo(before);
    }

    @Test
    void usedStepsOfAnotherSalonAreNeitherFoundNorChanged() {
        MfaStepRepository steps = application.bean(MfaStepRepository.class);
        Map<String, List<Map<String, Object>>> before = identityRows.snapshotOf(beta.id());

        asAlpha(() -> {
            seesOnlyAlphas(steps, MfaStep::getTenantId, betas.mfaStep());
            assertThat(steps.existsByAccountIdAndStep(betas.staff(), betas.step()))
                    .isFalse();
        });
        attemptsToChangeOrRemove(steps, MfaStep::getTenantId, betas.mfaStep());

        assertThat(identityRows.snapshotOf(beta.id())).isEqualTo(before);
    }

    @Test
    void rateBucketsOfAnotherSalonAreNeitherFoundNorChanged() {
        RateBucketRepository buckets = application.bean(RateBucketRepository.class);
        Map<String, List<Map<String, Object>>> before = identityRows.snapshotOf(beta.id());

        asAlpha(() -> {
            seesOnlyAlphas(buckets, RateBucket::getTenantId, betas.rateBucket());
            assertThat(buckets.findFirstByDimensionAndKeyHash("staff-identifier", betas.keyHash()))
                    .isEmpty();
        });
        attemptsToChangeOrRemove(buckets, RateBucket::getTenantId, betas.rateBucket());

        assertThat(identityRows.snapshotOf(beta.id())).isEqualTo(before);
    }

    @Test
    void customerCodesOfAnotherSalonAreNeitherFoundNorChanged() {
        CustomerChallengeRepository codes = application.bean(CustomerChallengeRepository.class);
        Map<String, List<Map<String, Object>>> before = identityRows.snapshotOf(beta.id());

        asAlpha(() -> {
            seesOnlyAlphas(codes, CustomerChallenge::getTenantId, betas.customerChallenge());
            assertThat(codes.findByNumberHash(betas.numberHash())).isEmpty();
            assertThat(codes.findByNumberHashAndInvalidatedAtIsNullAndConsumedAtIsNull(betas.numberHash()))
                    .isEmpty();
            assertThat(codes.findByExpiresAtLessThanEqual(Instant.now().plusSeconds(864_000)))
                    .extracting(CustomerChallenge::getTenantId)
                    .containsOnly(alpha.id());
        });
        attemptsToChangeOrRemove(codes, CustomerChallenge::getTenantId, betas.customerChallenge());

        assertThat(identityRows.snapshotOf(beta.id())).isEqualTo(before);
    }

    @Test
    void theLockRowOfAnotherSalonIsNeitherFoundNorChanged() {
        IdentityGuardRepository guards = application.bean(IdentityGuardRepository.class);
        Map<String, List<Map<String, Object>>> before = identityRows.snapshotOf(beta.id());

        asAlpha(() -> {
            seesOnlyAlphas(guards, IdentityGuard::getTenantId, beta.id());
            assertThat(guards.lockCurrent()).map(IdentityGuard::getTenantId).contains(alpha.id());
        });
        attemptsToChangeOrRemove(guards, IdentityGuard::getTenantId, beta.id());

        assertThat(identityRows.snapshotOf(beta.id())).isEqualTo(before);
    }

    @Test
    void aNewRowIsStampedWithTheCurrentSalonAndCannotPointAtAnotherSalonsRow() {
        StaffAccountRepository accounts = application.bean(StaffAccountRepository.class);
        MfaStepRepository steps = application.bean(MfaStepRepository.class);
        RefreshHistoryRepository history = application.bean(RefreshHistoryRepository.class);
        Map<String, List<Map<String, Object>>> before = identityRows.snapshotOf(beta.id());

        StaffAccount created = callAsAlpha(() -> accounts.persist(StaffAccount.create(
                "new-" + UUID.randomUUID() + "@example.test",
                null,
                "not-a-real-hash",
                StaffRole.RECEPTIONIST,
                false,
                Instant.now())));
        assertThat(database.asOwner()
                        .queryForObject(
                                "SELECT tenant_id FROM identity_staff WHERE id = ?", UUID.class, created.getId()))
                .isEqualTo(alpha.id());

        assertThat(catchThrowable(() -> asAlpha(() -> {
                    steps.persist(MfaStep.used(betas.staff(), 1, Instant.now()));
                    steps.flush();
                })))
                .as("a used step for beta's staff member, written as alpha")
                .isNotNull();
        assertThat(catchThrowable(() -> asAlpha(() -> {
                    history.persist(RefreshHistory.spent(betas.staffSession(), "spent", Instant.now()));
                    history.flush();
                })))
                .as("a spent credential of beta's session, written as alpha")
                .isNotNull();

        assertThat(identityRows.snapshotOf(beta.id())).isEqualTo(before);
    }

    @Test
    void withNoSalonEveryRepositoryFindsNothing() {
        List<JpaRepository<?, UUID>> repositories = List.of(
                application.bean(StaffAccountRepository.class),
                application.bean(CustomerAccountRepository.class),
                application.bean(AccessSessionRepository.class),
                application.bean(RefreshHistoryRepository.class),
                application.bean(MfaChallengeRepository.class),
                application.bean(MfaStepRepository.class),
                application.bean(RateBucketRepository.class),
                application.bean(CustomerChallengeRepository.class),
                application.bean(IdentityGuardRepository.class));

        inTransaction(() -> {
            for (JpaRepository<?, UUID> repository : repositories) {
                assertThat(repository.findAll()).isEmpty();
                assertThat(repository.count()).isZero();
            }
            assertThat(application.bean(StaffAccountRepository.class).findById(alphas.staff()))
                    .isEmpty();
            return null;
        });
    }

    /** By id, by listing and by counting, alpha sees its own rows and none of beta's. */
    private <T> void seesOnlyAlphas(JpaRepository<T, UUID> repository, Function<T, UUID> salon, UUID betasRow) {
        assertThat(repository.findById(betasRow)).as("beta's row, by its id").isEmpty();
        assertThat(repository.existsById(betasRow)).isFalse();
        assertThat(repository.findAllById(List.of(betasRow))).isEmpty();
        assertThat(repository.findAll()).extracting(salon).isNotEmpty().containsOnly(alpha.id());
        assertThat(repository.count()).isEqualTo(repository.findAll().size());
    }

    /**
     * Every way a repository changes or removes a row, aimed at beta's: by id, in a batch, all at once, and
     * saving one of alpha's entities under beta's row's id. Each either does nothing to beta or fails.
     */
    private <T> void attemptsToChangeOrRemove(
            JpaRepository<T, UUID> repository, Function<T, UUID> salon, UUID betasRow) {
        asAlpha(() -> {
            repository.deleteById(betasRow);
            repository.deleteAllById(List.of(betasRow));
            repository.deleteAllByIdInBatch(List.of(betasRow));
            repository.flush();
        });
        catchThrowable(() -> asAlpha(() -> {
            T alphasEntity = repository.findAll().getFirst();
            assertThat(salon.apply(alphasEntity)).isEqualTo(alpha.id());
            entityManager.detach(alphasEntity);
            setField(alphasEntity, "id", betasRow);
            repository.saveAndFlush(alphasEntity);
        }));
        catchThrowable(() -> asAlpha(() -> {
            repository.deleteAllInBatch();
            repository.deleteAll();
            repository.flush();
        }));
    }

    private void asAlpha(Runnable work) {
        callAsAlpha(() -> {
            work.run();
            return null;
        });
    }

    private <T> T callAsAlpha(Supplier<T> work) {
        try {
            return TenantContext.callAs(salons.asCurrent(alpha), () -> inTransaction(work::get));
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static <T> T inTransaction(Supplier<T> work) {
        return transaction.execute(status -> work.get());
    }

    private static void setField(Object entity, String name, Object value) {
        try {
            Field field = entity.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(entity, value);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
