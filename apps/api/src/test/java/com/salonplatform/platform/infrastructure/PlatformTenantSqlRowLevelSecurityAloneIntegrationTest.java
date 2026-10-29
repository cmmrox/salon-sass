package com.salonplatform.platform.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.platform.infrastructure.SupportSessionStore.SalonAuditEntry;
import com.salonplatform.shared.entitlements.SalonEntitlements.Limits;
import com.salonplatform.shared.infrastructure.PlatformDatabase;
import com.salonplatform.testing.StartedApplication;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import com.salonplatform.testing.TestSalons;
import com.salonplatform.testing.TestSalons.TestSalon;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * The platform's direct SQL that reaches a salon's rows, with row-level security and the salon each
 * statement names as the only guards: no Hibernate in the path (docs/engineering/backend.md, "Persistence
 * and migrations"). The tenant-pool reads run as the application's role on a plain connection with only
 * {@code app.tenant_id} set; the two writes the platform makes for a named salon run on the platform
 * connection through the stores the product uses.
 */
class PlatformTenantSqlRowLevelSecurityAloneIntegrationTest {

    private static final String SET_TENANT = "SELECT set_config('app.tenant_id', ?, true)";

    private static TestDatabase database;

    private static StartedApplication application;

    private static TestSalon alpha;

    private static TestSalon beta;

    @BeforeAll
    static void twoSalonsOnDifferentPlansWithMessagesAndOneSupportEntry() {
        database = TestDatabase.createEmpty(TestPostgres.shared());
        application = database.start();
        JdbcTemplate owner = database.asOwner();
        TestSalons salons = new TestSalons(owner);
        alpha = salons.create("Alpha Studio", "#D6086A");
        beta = salons.create("Beta Lounge", "#1F2937");
        onPlan(owner, alpha, "alpha-plan", 2);
        onPlan(owner, beta, "beta-plan", 7);

        PlatformDatabase platform = application.bean(PlatformDatabase.class);
        PlatformOutbox outbox = application.bean(PlatformOutbox.class);
        SupportSessionStore support = application.bean(SupportSessionStore.class);
        Instant now = Instant.now();
        platform.inTransaction(status -> {
            outbox.enqueue(alpha.id(), BillingLedger.EMAIL, Map.of("to", "alpha"), now);
            outbox.enqueue(alpha.id(), BillingLedger.EMAIL, Map.of("to", "alpha"), now);
            for (int i = 0; i < 3; i++) {
                outbox.enqueue(beta.id(), BillingLedger.EMAIL, Map.of("to", "beta"), now);
            }
            support.recordInSalonAudit(new SalonAuditEntry(
                    alpha.id(),
                    "support",
                    "rls-alone",
                    "support.session_started",
                    UUID.randomUUID(),
                    "a reason long enough",
                    "{}",
                    null,
                    now));
            return null;
        });
    }

    @AfterAll
    static void stop() {
        application.close();
    }

    @Test
    void theEntitlementsReadAreThoseOfTheSalonSetAndNoneWithoutOne() throws SQLException {
        Optional<Limits> alphas = asApplication(alpha, client -> new SalonEntitlementsStore(client).forCurrentSalon());
        Optional<Limits> betas = asApplication(beta, client -> new SalonEntitlementsStore(client).forCurrentSalon());
        Optional<Limits> nobodys = asApplication(null, client -> new SalonEntitlementsStore(client).forCurrentSalon());

        assertThat(alphas.map(Limits::staffSeats)).contains(2);
        assertThat(betas.map(Limits::staffSeats)).contains(7);
        assertThat(nobodys).isEmpty();
    }

    @Test
    void aMessageWrittenForANamedSalonBelongsToThatSalonOnly() {
        JdbcTemplate owner = database.asOwner();

        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM outbox_message WHERE tenant_id = ? AND payload ->> 'to' = 'alpha'",
                        Integer.class,
                        alpha.id()))
                .isEqualTo(2);
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM outbox_message WHERE tenant_id <> ? AND payload ->> 'to' = 'alpha'",
                        Integer.class,
                        alpha.id()))
                .isZero();
    }

    @Test
    void aSupportAuditEntryWrittenForANamedSalonBelongsToThatSalonOnly() {
        assertThat(database.asOwner()
                        .queryForList("SELECT tenant_id FROM audit_entry WHERE actor_id = 'rls-alone'", UUID.class))
                .containsExactly(alpha.id());
    }

    private static void onPlan(JdbcTemplate owner, TestSalon salon, String key, int staffSeats) {
        UUID plan = UUID.randomUUID();
        owner.update("""
                INSERT INTO plan (id, key, name, staff_seats, branches, message_allowance, custom_domain, dedicated_app,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, 1, 0, false, false, now(), now())
                """, plan, key, key, staffSeats);
        owner.update("UPDATE tenant SET plan_id = ? WHERE id = ?", plan, salon.id());
    }

    /**
     * Runs the work as the application's role in one transaction on a plain connection, with the salon
     * set for that transaction only (or none), and rolls it back.
     */
    private static <T> T asApplication(TestSalon salon, Function<JdbcClient, T> work) throws SQLException {
        try (Connection connection = database.connectAsApplication()) {
            connection.setAutoCommit(false);
            if (salon != null) {
                try (PreparedStatement statement = connection.prepareStatement(SET_TENANT)) {
                    statement.setString(1, salon.id().toString());
                    statement.execute();
                }
            }
            T result = work.apply(JdbcClient.create(new SingleConnectionDataSource(connection, true)));
            connection.rollback();
            return result;
        }
    }
}
