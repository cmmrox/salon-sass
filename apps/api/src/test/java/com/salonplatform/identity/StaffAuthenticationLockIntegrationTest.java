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
import java.sql.Statement;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Decision 120, platform review M2: authenticating a request takes no salon-wide exclusive lock and writes
 * nothing. Before, every signed-in request ran through the identity transaction that locks the salon's
 * {@code identity_guard} row ({@code SELECT ... FOR UPDATE}), so one salon's requests queued behind each other
 * and behind any sign-in in flight, and each read was a write. A session revoked, or a member removed, is still
 * refused on the next request, because the session and its account are read from the database every time.
 */
// JSON bodies are read as untyped maps; each cast states the shape the assertion beside it checks.
@SuppressWarnings("unchecked")
class StaffAuthenticationLockIntegrationTest extends IntegrationTest {

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
    private TestSalon salon;
    private String password;
    private String access;

    @BeforeEach
    void signedIn() throws Exception {
        salon = new TestSalons(owner).create("Authentication Lock Salon", "#123456");
        password = UUID.randomUUID() + " synthetic";
        TenantContext.callAs(
                new TestSalons(owner).asCurrent(salon),
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
        Http.Exchange signedIn = call(
                "POST",
                "/staff/sign-in",
                Map.of("identifier", "manager@example.test", "password", password, "deviceLabel", "JUnit device"),
                null);
        assertThat(signedIn.status()).withFailMessage("%s", signedIn.body()).isEqualTo(200);
        access = (String) ((Map<String, Object>)
                        json.readValue(signedIn.body(), Map.class).get("credentials"))
                .get("accessToken");
        assertThat(guardRows()).as("the sign-in created the salon's lock row").isEqualTo(1);
    }

    @Test
    void anAuthenticatedReadIsAnsweredWhileAnotherTransactionHoldsTheSalonsIdentityLock() throws Exception {
        try (Connection holder = database.connectAsOwner()) {
            holder.setAutoCommit(false);
            try (Statement statement = holder.createStatement()) {
                statement.execute("SELECT id FROM identity_guard WHERE tenant_id = '" + salon.id() + "' FOR UPDATE");
            }

            long started = System.nanoTime();
            Http.Exchange me = call("GET", "/me", null, access);
            Http.Exchange sessions = call("GET", "/sessions?page=0&size=10", null, access);
            long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertThat(me.status()).withFailMessage("%s", me.body()).isEqualTo(200);
            assertThat(sessions.status()).withFailMessage("%s", sessions.body()).isEqualTo(200);
            assertThat(tookMillis).as("neither waited for the lock").isLessThan(1_500);
            holder.rollback();
        }
    }

    @Test
    void anAuthenticatedReadWritesNothingNotEvenALockOnTheSalonsRow() {
        long before = guardRowVersion();

        for (int i = 0; i < 3; i++) {
            Http.Exchange me = call("GET", "/me", null, access);
            assertThat(me.status()).withFailMessage("%s", me.body()).isEqualTo(200);
        }

        assertThat(guardRowVersion())
                .as("no transaction locked or changed the salon's identity row (its xmax is unchanged)")
                .isEqualTo(before);
    }

    @Test
    void aSessionRevokedByACommittedTransactionIsRefusedOnTheNextRequest() {
        assertThat(call("GET", "/me", null, access).status()).isEqualTo(200);

        owner.update(
                "UPDATE identity_session SET revoked_at = now() WHERE tenant_id = ? AND revoked_at IS NULL",
                salon.id());

        Http.Exchange me = call("GET", "/me", null, access);
        assertThat(me.status()).isEqualTo(401);
        assertThat((String) json.readValue(me.body(), Map.class).get("code")).isEqualTo("invalid_session");
    }

    private Http.Exchange call(String method, String path, Object request, String token) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        if (token != null) {
            headers.put("Authorization", "Bearer " + token);
        }
        return http().sendOnHost(
                        method,
                        salon.host(port()),
                        "/api/v1/identity" + path,
                        headers,
                        request == null ? null : json.writeValueAsString(request));
    }

    private int guardRows() {
        return owner.queryForObject(
                "SELECT count(*) FROM identity_guard WHERE tenant_id = ?", Integer.class, salon.id());
    }

    private long guardRowVersion() {
        return owner.queryForObject(
                "SELECT xmax::text::bigint FROM identity_guard WHERE tenant_id = ?", Long.class, salon.id());
    }
}
