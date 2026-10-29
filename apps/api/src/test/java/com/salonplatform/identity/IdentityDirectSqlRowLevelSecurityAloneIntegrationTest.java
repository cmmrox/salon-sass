package com.salonplatform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.salonplatform.identity.IdentityRows.SalonRows;
import com.salonplatform.identity.infrastructure.CustomerDataErasure;
import com.salonplatform.identity.infrastructure.ExpiredSecurityState;
import com.salonplatform.identity.infrastructure.IdentityLock;
import com.salonplatform.identity.infrastructure.SyntheticIdentityReset;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.testing.StartedApplication;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import com.salonplatform.testing.TestSalons;
import com.salonplatform.testing.TestSalons.TestSalon;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Identity's direct SQL, the named exceptions Hibernate's filter never sees: the lock row, the expiry cleanup,
 * a deleted customer's erasure and the local fixture reset. Each runs in a tenant transaction with alpha set and
 * reaches alpha's rows only. Then row-level security is shown to be enough on its own: inside alpha's
 * transaction the request is made to name beta, so that each statement's own {@code tenant_id} predicate names
 * beta's rows, and still not one of them is changed, because the policy compares with the transaction's salon.
 */
class IdentityDirectSqlRowLevelSecurityAloneIntegrationTest {

    private static final Instant LATER = Instant.now().plus(10, ChronoUnit.DAYS);

    private static TestDatabase database;

    private static StartedApplication application;

    private static TestSalons salons;

    private static IdentityRows identityRows;

    private static TransactionTemplate transaction;

    private TestSalon alpha;

    private TestSalon beta;

    private SalonRows alphas;

    private SalonRows betas;

    @BeforeAll
    static void applicationWithRowLevelSecurityOn() {
        database = TestDatabase.createEmpty(TestPostgres.shared());
        application = database.start();
        salons = new TestSalons(database.asOwner());
        identityRows = new IdentityRows(database.asOwner());
        transaction = new TransactionTemplate(application.bean(PlatformTransactionManager.class));
    }

    @AfterAll
    static void stop() {
        application.close();
    }

    @BeforeEach
    void twoSalonsWithARowInEveryIdentityTable() {
        alpha = salons.create("Direct Alpha", "#111111");
        beta = salons.create("Direct Beta", "#222222");
        alphas = identityRows.writeFor(alpha);
        betas = identityRows.writeFor(beta);
        customerCodeBucketFor(alpha);
        customerCodeBucketFor(beta);
    }

    @Test
    void theExpiryCleanupRemovesTheCurrentSalonsStateOnly() {
        ExpiredSecurityState expired = application.bean(ExpiredSecurityState.class);
        Map<String, List<Map<String, Object>>> before = identityRows.snapshotOf(beta.id());

        asAlpha(() -> cleanUpEverything(expired));

        assertThat(identityRows.snapshotOf(beta.id())).isEqualTo(before);
        assertThat(rows("identity_mfa_challenge", alpha)).isZero();
        assertThat(rows("identity_mfa_step", alpha)).isZero();
        assertThat(rows("identity_rate_bucket", alpha)).isZero();
        assertThat(rows("identity_customer_challenge", alpha)).isZero();
    }

    @Test
    void theExpiryCleanupNamingAnotherSalonIsStoppedByRowLevelSecurity() {
        ExpiredSecurityState expired = application.bean(ExpiredSecurityState.class);
        Map<String, List<Map<String, Object>>> before = identityRows.snapshotOf(beta.id());

        asAlphaNamingBeta(() -> cleanUpEverything(expired));

        assertThat(identityRows.snapshotOf(beta.id())).isEqualTo(before);
    }

    @Test
    void erasingACustomerReachesTheCurrentSalonsRowsOnly() {
        CustomerDataErasure erasure = application.bean(CustomerDataErasure.class);
        Map<String, List<Map<String, Object>>> before = identityRows.snapshotOf(beta.id());

        asAlpha(() -> {
            erasure.eraseChallengesOfNumber(betas.numberHash());
            erasure.eraseSessionsOf(betas.customer());
            erasure.eraseChallengesOfNumber(alphas.numberHash());
            erasure.eraseSessionsOf(alphas.customer());
        });

        assertThat(identityRows.snapshotOf(beta.id())).isEqualTo(before);
        assertThat(rows("identity_customer_challenge", alpha)).isZero();
        assertThat(database.asOwner()
                        .queryForObject(
                                "SELECT count(*) FROM identity_session WHERE tenant_id = ? AND kind = 'customer'",
                                Integer.class,
                                alpha.id()))
                .isZero();
    }

    @Test
    void erasingACustomerNamingAnotherSalonIsStoppedByRowLevelSecurity() {
        CustomerDataErasure erasure = application.bean(CustomerDataErasure.class);
        Map<String, List<Map<String, Object>>> before = identityRows.snapshotOf(beta.id());

        asAlphaNamingBeta(() -> {
            erasure.eraseChallengesOfNumber(betas.numberHash());
            erasure.eraseSessionsOf(betas.customer());
        });

        assertThat(identityRows.snapshotOf(beta.id())).isEqualTo(before);
    }

    @Test
    void theFixtureResetEmptiesTheCurrentSalonOnly() {
        SyntheticIdentityReset reset = application.bean(SyntheticIdentityReset.class);
        Map<String, List<Map<String, Object>>> before = identityRows.snapshotOf(beta.id());

        asAlpha(() -> {
            reset.removeCustomerRecords();
            reset.removeEveryRecord();
        });

        assertThat(identityRows.snapshotOf(beta.id())).isEqualTo(before);
        for (String table : IdentityRows.TABLES) {
            assertThat(rows(table, alpha)).as(table).isEqualTo(table.equals("identity_guard") ? 1 : 0);
        }
    }

    @Test
    void theFixtureResetNamingAnotherSalonIsStoppedByRowLevelSecurity() {
        SyntheticIdentityReset reset = application.bean(SyntheticIdentityReset.class);
        Map<String, List<Map<String, Object>>> before = identityRows.snapshotOf(beta.id());

        asAlphaNamingBeta(() -> {
            reset.removeCustomerRecords();
            reset.removeEveryRecord();
        });

        assertThat(identityRows.snapshotOf(beta.id())).isEqualTo(before);
    }

    @Test
    void theLockRowIsCreatedForTheCurrentSalonOnly() {
        IdentityLock lock = application.bean(IdentityLock.class);
        TestSalon delta = salons.create("Direct Delta", "#333333");
        Map<String, List<Map<String, Object>>> before = identityRows.snapshotOf(beta.id());

        as(delta, () -> lock.lockCurrentSalon(Instant.now()));
        asAlpha(() -> lock.lockCurrentSalon(Instant.now()));

        assertThat(rows("identity_guard", delta)).isEqualTo(1);
        assertThat(rows("identity_guard", alpha)).isEqualTo(1);
        assertThat(identityRows.snapshotOf(beta.id())).isEqualTo(before);
    }

    @Test
    void aLockRowNamingAnotherSalonIsRefusedByRowLevelSecurity() {
        IdentityLock lock = application.bean(IdentityLock.class);
        TestSalon delta = salons.create("Direct Delta", "#333333");

        assertThatThrownBy(() ->
                        inTransactionOf(alpha, () -> nameAs(delta, () -> lock.lockCurrentSalon(Instant.now()))))
                .rootCause()
                .hasMessageContaining("violates row-level security policy for table \"identity_guard\"");

        assertThat(rows("identity_guard", delta)).isZero();
    }

    private static void cleanUpEverything(ExpiredSecurityState expired) {
        expired.removeCustomerRateBucketsStartedBy(LATER);
        expired.removeCustomerChallengesExpiredBy(LATER);
        expired.removeMfaChallengesExpiredBy(LATER);
        expired.removeMfaStepsBefore(Long.MAX_VALUE);
        expired.removeStaffRateBucketsStartedBy(LATER);
    }

    private static void customerCodeBucketFor(TestSalon salon) {
        Timestamp now = Timestamp.from(Instant.now());
        database.asOwner()
                .update("""
                        INSERT INTO identity_rate_bucket (id, tenant_id, created_at, updated_at, dimension, key_hash,
                            window_start, attempts)
                        VALUES (?, ?, ?, ?, 'customer-number', ?, ?, 1)
                        """, UUID.randomUUID(), salon.id(), now, now, "number-bucket-" + UUID.randomUUID(), now);
    }

    private static int rows(String table, TestSalon salon) {
        JdbcTemplate owner = database.asOwner();
        return owner.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE tenant_id = ?", Integer.class, salon.id());
    }

    private void asAlpha(Runnable work) {
        as(alpha, work);
    }

    /** In alpha's transaction, with the request naming beta: only row-level security can keep beta's rows. */
    private void asAlphaNamingBeta(Runnable work) {
        inTransactionOf(alpha, () -> nameAs(beta, work));
    }

    private static void as(TestSalon salon, Runnable work) {
        inTransactionOf(salon, work);
    }

    private static void inTransactionOf(TestSalon salon, Runnable work) {
        call(salon, () -> {
            transaction.executeWithoutResult(status -> work.run());
            return null;
        });
    }

    private static void nameAs(TestSalon salon, Runnable work) {
        call(salon, () -> {
            work.run();
            return null;
        });
    }

    private static void call(TestSalon salon, java.util.concurrent.Callable<Void> work) {
        try {
            TenantContext.callAs(salons.asCurrent(salon), work);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
