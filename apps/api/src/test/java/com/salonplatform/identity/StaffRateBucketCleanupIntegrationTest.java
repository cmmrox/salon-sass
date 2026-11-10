package com.salonplatform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.identity.adapter.IdentityCrypto;
import com.salonplatform.identity.domain.StaffRole;
import com.salonplatform.identity.entity.StaffAccount;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.testing.Http;
import com.salonplatform.testing.IntegrationTest;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import com.salonplatform.testing.TestSalons;
import com.salonplatform.testing.TestSalons.TestSalon;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * BUG-F02-017: the security cleanup job removing a staff rate bucket while a sign-in is counting an attempt against
 * it. The sign-in starts a new window, as when no bucket exists, and is answered as usual; before the fix its update
 * of the removed row failed and the sign-in was answered 500.
 */
class StaffRateBucketCleanupIntegrationTest extends IntegrationTest {

    @Autowired
    IdentityCrypto crypto;

    @Autowired
    Clock clock;

    @Autowired
    JsonMapper json;

    @Autowired
    PlatformTransactionManager manager;

    @PersistenceContext
    EntityManager em;

    private final TestDatabase database = TestDatabase.of(TestPostgres.shared());
    private final JdbcTemplate owner = database.asOwner();
    private final ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor();
    private TestSalon salon;
    private String password;

    @BeforeEach
    void setUp() throws Exception {
        TestSalons salons = new TestSalons(owner);
        salon = salons.create("Rate Bucket Salon", "#345678");
        password = UUID.randomUUID() + " synthetic";
        TenantContext.callAs(
                salons.asCurrent(salon),
                () -> new TransactionTemplate(manager).execute(status -> {
                    StaffAccount account = StaffAccount.create(
                            "manager@example.test",
                            "+94770000201",
                            crypto.encodePassword(password),
                            StaffRole.MANAGER,
                            false,
                            clock.instant());
                    em.persist(account);
                    return account;
                }));
    }

    @AfterEach
    void tearDown() {
        requests.shutdownNow();
    }

    private Http.Exchange signIn() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        return http().sendOnHost(
                        "POST",
                        salon.host(port()),
                        "/api/v1/identity/staff/sign-in",
                        headers,
                        json.writeValueAsString(Map.of(
                                "identifier", "manager@example.test",
                                "password", password,
                                "deviceLabel", "JUnit device")));
    }

    private int staffBuckets() {
        Integer rows = owner.queryForObject(
                "SELECT count(*) FROM identity_rate_bucket WHERE tenant_id = ? AND dimension LIKE 'staff-%'",
                Integer.class, salon.id());
        return rows == null ? 0 : rows;
    }

    /** Whether a statement on the salon's rate buckets is waiting for a row lock. */
    private boolean aStatementWaitsOnTheBuckets() {
        Integer waiting = owner.queryForObject("""
                SELECT count(*) FROM pg_stat_activity
                 WHERE wait_event_type = 'Lock' AND query ILIKE '%identity_rate_bucket%'
                   AND query NOT ILIKE '%DELETE FROM identity_rate_bucket%'
                """, Integer.class);
        return waiting != null && waiting > 0;
    }

    @Test
    void aSignInWhoseBucketTheCleanupJobRemovesMeanwhileStartsANewWindowAndSucceeds() throws Exception {
        assertThat(signIn().status()).isEqualTo(200);
        assertThat(staffBuckets()).isEqualTo(2);

        Http.Exchange answer;
        try (Connection job = database.connectAsApplication()) {
            job.setAutoCommit(false);
            try (PreparedStatement tenant = job.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                tenant.setString(1, salon.id().toString());
                tenant.execute();
            }
            // The job's own statement (ExpiredSecurityState.removeStaffRateBucketsStartedBy), held open.
            try (PreparedStatement delete = job.prepareStatement("""
                    DELETE FROM identity_rate_bucket
                     WHERE tenant_id = ? AND window_start <= ? AND dimension LIKE 'staff-%'
                    """)) {
                delete.setObject(1, salon.id());
                delete.setTimestamp(2, Timestamp.from(Instant.now().plus(Duration.ofDays(1))));
                assertThat(delete.executeUpdate()).isEqualTo(2);
            }
            Future<Http.Exchange> signIn = requests.submit(this::signIn);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!aStatementWaitsOnTheBuckets() && !signIn.isDone() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(aStatementWaitsOnTheBuckets())
                    .as("the sign-in waits for the job's removal")
                    .isTrue();
            job.commit();
            answer = signIn.get(15, TimeUnit.SECONDS);
        }

        assertThat(answer.status()).withFailMessage("%s", answer.body()).isEqualTo(200);
        assertThat(staffBuckets()).isEqualTo(2);
        assertThat(owner.queryForList(
                        "SELECT attempts FROM identity_rate_bucket WHERE tenant_id = ? AND dimension LIKE 'staff-%'",
                        Integer.class, salon.id()))
                .containsOnly(1);
    }
}
