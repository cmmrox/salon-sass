package com.salonplatform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

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
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * BUG-F02-016, decision 120: a staff password is checked (PBKDF2, deliberately slow) holding no database
 * transaction and no salon lock. Before the fix the check ran inside the identity transaction, holding a pooled
 * connection and the salon's identity lock, so every other identity request of that salon waited behind it and a
 * handful of sign-ins in flight could exhaust the pool for every salon.
 */
// JSON bodies are read as untyped maps; each cast states the shape the assertion beside it checks.
@SuppressWarnings("unchecked")
class StaffPasswordCheckIntegrationTest extends IntegrationTest {

    @MockitoSpyBean
    IdentityCrypto crypto;

    @Autowired
    Clock clock;

    @Autowired
    JsonMapper json;

    @Autowired
    PlatformTransactionManager manager;

    @PersistenceContext
    EntityManager em;

    private final JdbcTemplate owner = TestDatabase.of(TestPostgres.shared()).asOwner();
    private final ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor();
    private final List<Boolean> transactionOpenAtCheck = new CopyOnWriteArrayList<>();
    private final CountDownLatch checking = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicBoolean holdNextCheck = new AtomicBoolean();
    private TestSalon salon;
    private String password;

    @BeforeEach
    void setUp() throws Exception {
        salon = new TestSalons(owner).create("Password Check Salon", "#123456");
        password = UUID.randomUUID() + " synthetic";
        add("manager@example.test", "+94770000101", StaffRole.MANAGER);
        add("receptionist@example.test", "+94770000102", StaffRole.RECEPTIONIST);
        doAnswer(call -> {
                    transactionOpenAtCheck.add(TransactionSynchronizationManager.isActualTransactionActive());
                    if (holdNextCheck.compareAndSet(true, false)) {
                        checking.countDown();
                        release.await(15, TimeUnit.SECONDS);
                    }
                    return call.callRealMethod();
                })
                .when(crypto)
                .matchesPassword(any(), any());
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        requests.shutdownNow();
    }

    private void add(String email, String mobile, StaffRole role) throws Exception {
        TenantContext.callAs(
                new TestSalons(owner).asCurrent(salon),
                () -> new TransactionTemplate(manager).execute(status -> {
                    StaffAccount account = StaffAccount.create(
                            email, mobile, crypto.encodePassword(password), role, false, clock.instant());
                    em.persist(account);
                    return account;
                }));
    }

    private Http.Exchange call(String method, String path, Object request, String token) {
        Map<String, String> headers = new java.util.HashMap<>();
        headers.put("Content-Type", "application/json");
        if (token != null) headers.put("Authorization", "Bearer " + token);
        return http().sendOnHost(
                        method,
                        salon.host(port()),
                        "/api/v1/identity" + path,
                        headers,
                        json.writeValueAsString(request));
    }

    private Http.Exchange signIn(String identifier, String secret) {
        return call(
                "POST",
                "/staff/sign-in",
                Map.of("identifier", identifier, "password", secret, "deviceLabel", "JUnit device"),
                null);
    }

    @Test
    void aSignInWhosePasswordIsBeingCheckedDoesNotHoldUpTheSalonsOtherSignIns() throws Exception {
        holdNextCheck.set(true);
        Future<Http.Exchange> slow = requests.submit(() -> signIn("manager@example.test", "wrong password"));
        assertThat(checking.await(15, TimeUnit.SECONDS)).isTrue();

        // The first sign-in is still checking its password. Another of the same salon is answered at once.
        long started = System.nanoTime();
        Http.Exchange other = requests.submit(() -> signIn("receptionist@example.test", password))
                .get(15, TimeUnit.SECONDS);
        long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(other.status()).withFailMessage("%s", other.body()).isEqualTo(200);
        assertThat(tookMillis).isLessThan(2_500);
        assertThat(slow.isDone()).isFalse();

        release.countDown();
        assertThat(slow.get(15, TimeUnit.SECONDS).status()).isEqualTo(401);
        assertThat(transactionOpenAtCheck).containsOnly(false).hasSize(2);
    }

    @Test
    void aPasswordChangeChecksTheOldPasswordOutsideAnyTransaction() {
        Http.Exchange signedIn = signIn("receptionist@example.test", password);
        assertThat(signedIn.status()).isEqualTo(200);
        String access = (String) ((Map<String, Object>)
                        json.readValue(signedIn.body(), Map.class).get("credentials"))
                .get("accessToken");
        transactionOpenAtCheck.clear();

        Http.Exchange wrong = call(
                "PUT",
                "/staff/password",
                Map.of("oldPassword", "not the password", "newPassword", UUID.randomUUID() + " next"),
                access);
        String next = UUID.randomUUID() + " next";
        Http.Exchange changed =
                call("PUT", "/staff/password", Map.of("oldPassword", password, "newPassword", next), access);

        assertThat(wrong.status()).isEqualTo(401);
        assertThat(changed.status()).withFailMessage("%s", changed.body()).isEqualTo(200);
        assertThat(transactionOpenAtCheck).containsExactly(false, false);
        assertThat(signIn("receptionist@example.test", next).status()).isEqualTo(200);
        assertThat(signIn("receptionist@example.test", password).status()).isEqualTo(401);
    }
}
