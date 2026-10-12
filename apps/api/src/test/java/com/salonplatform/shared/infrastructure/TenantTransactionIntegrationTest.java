package com.salonplatform.shared.infrastructure;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import com.salonplatform.shared.tenancy.CurrentTenant;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.testing.StartedApplication;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import com.salonplatform.testing.TestSalons;
import com.salonplatform.testing.TestSalons.TestSalon;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How the salon reaches the database through the application's own transactions: set at the start
 * of each one, for that one only. The pool here holds a single connection, so every transaction runs
 * on the connection the previous one used, as a busy pool's connections are reused. Native SQL goes
 * through the same transaction, so row-level security holds for it without Hibernate's filter.
 */
class TenantTransactionIntegrationTest {

    private static StartedApplication application;

    private static TestSalon alpha;

    private static TestSalon beta;

    private static TransactionTemplate transaction;

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void applicationWithOneConnection() {
        TestDatabase database = TestDatabase.createEmpty(TestPostgres.shared());
        application = database.start("--spring.datasource.hikari.maximum-pool-size=1");
        TestSalons salons = new TestSalons(database.asOwner());
        alpha = salons.create("Alpha Studio", "#D6086A");
        beta = salons.create("Beta Lounge", "#1F2937");
        transaction = new TransactionTemplate(application.bean(PlatformTransactionManager.class));
        jdbc = application.jdbc();
    }

    @AfterAll
    static void stop() {
        application.close();
    }

    @Test
    void eachTransactionCarriesTheSalonItStartedWithAndTheNextOneOnTheSameConnectionCarriesNone() throws Exception {
        Map<String, Object> inAlpha = as(alpha, () -> transaction.execute(status -> state()));
        Map<String, Object> afterwards = transaction.execute(status -> state());

        assertThat(inAlpha.get("tenant")).isEqualTo(alpha.id().toString());
        assertThat(afterwards.get("pid")).as("the same connection").isEqualTo(inAlpha.get("pid"));
        assertThat((String) afterwards.get("tenant")).as("no salon in the next transaction").isNullOrEmpty();
    }

    @Test
    void nativeSqlInATransactionSeesTheTransactionsSalonOnlyAndNothingWithoutOne() throws Exception {
        List<UUID> seenByAlpha = as(alpha, () -> transaction.execute(status -> visibleTenants()));
        List<UUID> seenWithout = transaction.execute(status -> visibleTenants());

        assertThat(seenByAlpha).containsExactly(alpha.id());
        assertThat(seenWithout).isEmpty();
    }

    @Test
    void salonsTakingTurnsOnOneConnectionNeverSeeEachOther() throws Exception {
        for (int turn = 0; turn < 20; turn++) {
            TestSalon salon = turn % 2 == 0 ? alpha : beta;
            List<UUID> seenInTurn = as(salon, () -> transaction.execute(status -> visibleTenants()));
            List<UUID> seenBetweenTurns = transaction.execute(status -> visibleTenants());

            assertThat(seenInTurn).as("turn %d", turn).containsExactly(salon.id());
            assertThat(seenBetweenTurns).as("between turns").isEmpty();
        }
    }

    @Test
    void aQueryOutsideAnyTransactionSeesNoSalonsRows() throws Exception {
        List<UUID> seenWithNoSalon = visibleTenants();
        List<UUID> seenWithASalonButNoTransaction = as(alpha, TenantTransactionIntegrationTest::visibleTenants);

        assertThat(seenWithNoSalon).isEmpty();
        assertThat(seenWithASalonButNoTransaction).as("a salon in the context is set by a transaction only").isEmpty();
    }

    private static Map<String, Object> state() {
        return jdbc.queryForMap("SELECT pg_backend_pid() AS pid, current_setting('app.tenant_id', true) AS tenant");
    }

    private static List<UUID> visibleTenants() {
        return jdbc.queryForList("SELECT tenant_id FROM salon_brand ORDER BY tenant_id", UUID.class);
    }

    private static <T> T as(TestSalon salon, Callable<T> work) throws Exception {
        return TenantContext.callAs(new CurrentTenant(salon.id(), salon.slug(), TestSalons.DEFAULT_TIME_ZONE), work);
    }
}
