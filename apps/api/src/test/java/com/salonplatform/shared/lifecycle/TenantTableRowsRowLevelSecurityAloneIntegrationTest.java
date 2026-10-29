package com.salonplatform.shared.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.testing.StartedApplication;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import com.salonplatform.testing.TestSalons;
import com.salonplatform.testing.TestSalons.TestSalon;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A closed salon's export of a table ({@link TenantTableRows#export}), direct SQL that Hibernate's filter never
 * sees. It reads the current salon's rows only; and when the request names another salon inside a transaction
 * set to the first, its own {@code tenant_id} predicate names the other salon's rows and row-level security alone
 * keeps every one of them out.
 */
class TenantTableRowsRowLevelSecurityAloneIntegrationTest {

    private static final TenantDataModule.Table AUDIT =
            new TenantDataModule.Table("audit_entry", "created_at", Map.of());

    private static TestDatabase database;

    private static StartedApplication application;

    private static TestSalons salons;

    private static TestSalon alpha;

    private static TestSalon beta;

    @BeforeAll
    static void twoSalonsWithAnAuditEntryEach() {
        database = TestDatabase.createEmpty(TestPostgres.shared());
        application = database.start();
        salons = new TestSalons(database.asOwner());
        alpha = salons.create("Export Alpha", "#111111");
        beta = salons.create("Export Beta", "#222222");
        for (TestSalon salon : List.of(alpha, beta)) {
            database.asOwner().update("""
                            INSERT INTO audit_entry (tenant_id, id, actor_kind, actor_id, action, subject_kind,
                                subject_id, created_at, updated_at)
                            VALUES (?, ?, 'system', 'test', 'x.exported', 'thing', ?, now(), now())
                            """, salon.id(), UUID.randomUUID(), salon.slug());
        }
    }

    @AfterAll
    static void stop() {
        application.close();
    }

    @Test
    void theExportReadsTheCurrentSalonsRowsOnly() throws Exception {
        List<String> rows = exportIn(alpha, alpha);

        assertThat(rows)
                .singleElement()
                .asString()
                .contains(alpha.id().toString())
                .doesNotContain(beta.id().toString());
    }

    @Test
    void anExportNamingAnotherSalonReadsNothingThroughRowLevelSecurity() throws Exception {
        assertThat(exportIn(alpha, beta)).isEmpty();
    }

    /** The export, in a transaction with one salon set, for the salon the request names. */
    private static List<String> exportIn(TestSalon transactionSalon, TestSalon namedSalon) throws Exception {
        JdbcClient jdbc = application.bean(JdbcClient.class);
        TransactionTemplate transaction = new TransactionTemplate(application.bean(PlatformTransactionManager.class));
        return TenantContext.callAs(
                salons.asCurrent(transactionSalon),
                () -> transaction.execute(status -> {
                    try {
                        return TenantContext.callAs(
                                salons.asCurrent(namedSalon), () -> TenantTableRows.export(jdbc, AUDIT));
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex);
                    }
                }));
    }
}
