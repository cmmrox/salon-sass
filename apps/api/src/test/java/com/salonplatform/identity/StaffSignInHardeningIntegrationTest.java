package com.salonplatform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.salonplatform.identity.adapter.IdentityCrypto;
import com.salonplatform.identity.domain.StaffRole;
import com.salonplatform.identity.entity.StaffAccount;
import com.salonplatform.identity.inprocess.IdentityProvisioning;
import com.salonplatform.shared.persistence.PlatformDatabase;
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
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.HashMap;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The sign-in hardening left over from BUG-F02-016 (decision 120): one address's failed sign-ins are limited across
 * every salon, not only salon by salon, while correct ones give their count back; a sign-in refused 503 because the
 * password checks were at their limit counts toward no limit; the staff 429 says when to try again; and a staff
 * account's password, and a new Owner's, are hashed through the password checks, outside any transaction. One check
 * at once here, and sixty failures per address across salons, set here whatever the default.
 */
// JSON bodies are read as untyped maps; each cast states the shape the assertion beside it checks.
@SuppressWarnings("unchecked")
@TestPropertySource(
        properties = {
            "salon.identity.password-checks.concurrent=1",
            "salon.identity.sign-in.address-limit-across-salons=60"
        })
class StaffSignInHardeningIntegrationTest extends IntegrationTest {

    private static final int FAILURES_ACROSS_SALONS = 60;

    /** The accounts of each salon, so that no identifier reaches its own limit of ten. */
    private static final int ACCOUNTS_PER_SALON = 3;

    /** One hash of the test's password, made once, for every account the tests create. */
    private static String passwordHash;

    private static String password;

    @MockitoSpyBean
    IdentityCrypto crypto;

    @Autowired
    Clock clock;

    @Autowired
    JsonMapper json;

    @Autowired
    PlatformTransactionManager manager;

    @Autowired
    IdentityProvisioning provisioning;

    @Autowired
    PlatformDatabase platform;

    @PersistenceContext
    EntityManager em;

    private final TestDatabase database = TestDatabase.of(TestPostgres.shared());
    private final JdbcTemplate owner = database.asOwner();
    private final ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor();
    private final List<Boolean> transactionOpenAtHash = new CopyOnWriteArrayList<>();
    private final CountDownLatch checking = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicBoolean holdNextCheck = new AtomicBoolean();
    private final List<TestSalon> salons = new java.util.ArrayList<>();
    private TestSalon alpha;
    private TestSalon beta;

    @BeforeEach
    void fourSalonsWithThreeAccountsEach() throws Exception {
        // The counters across salons are shared by every test of this context: each test starts with none.
        owner.update("DELETE FROM shared_address_attempt");
        if (passwordHash == null) {
            password = UUID.randomUUID() + " synthetic";
            passwordHash = crypto.encodePassword(password);
        }
        TestSalons registry = new TestSalons(owner);
        for (String name : List.of("Alpha", "Beta", "Gamma", "Delta")) {
            TestSalon salon = registry.create("Hardening " + name, "#123456");
            salons.add(salon);
            for (int i = 0; i < ACCOUNTS_PER_SALON; i++) {
                add(salon, staff(i), "+9477000030" + i, StaffRole.RECEPTIONIST);
            }
        }
        alpha = salons.get(0);
        beta = salons.get(1);
        doAnswer(call -> {
                    if (holdNextCheck.compareAndSet(true, false)) {
                        checking.countDown();
                        release.await(15, TimeUnit.SECONDS);
                    }
                    return call.callRealMethod();
                })
                .when(crypto)
                .matchesPassword(any(), any());
        doAnswer(call -> {
                    transactionOpenAtHash.add(TransactionSynchronizationManager.isActualTransactionActive());
                    return call.callRealMethod();
                })
                .when(crypto)
                .encodePassword(any());
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        requests.shutdownNow();
    }

    @Test
    void sixtyFailuresFromOneAddressAcrossSalonsAreThenRefusedAtEverySalonAndSayWhenToTryAgain() {
        for (int i = 0; i < FAILURES_ACROSS_SALONS; i++) {
            TestSalon salon = salons.get(i % 3);
            assertThat(signIn(salon, "nobody-" + i + "@example.test", "wrong password")
                            .status())
                    .as("failure %d, the %dth on its salon", i + 1, i / 3 + 1)
                    .isEqualTo(401);
        }

        for (TestSalon salon : salons) {
            Http.Exchange limited = signIn(salon, staff(0), password);
            assertThat(limited.status())
                    .as("%s, though no salon has seen thirty attempts", salon.slug())
                    .isEqualTo(429);
            assertThat((String) json.readValue(limited.body(), Map.class).get("code"))
                    .isEqualTo("rate_limited");
            assertThat(limited.headers().get("retry-after")).isEqualTo("900");
        }
        assertThat(owner.queryForList("SELECT key_hash FROM shared_address_attempt", String.class))
                .as("the counter keeps a keyed hash, never the address")
                .singleElement()
                .satisfies(hash -> assertThat(hash).doesNotContain("127.0.0.1").hasSize(64));
    }

    @Test
    void aHundredCorrectSignInsFromOneAddressAcrossSalonsAreNeverRefused() {
        for (int i = 0; i < 100; i++) {
            TestSalon salon = salons.get(i % salons.size());
            Http.Exchange signedIn = signIn(salon, staff(i / salons.size() % ACCOUNTS_PER_SALON), password);
            assertThat(signedIn.status())
                    .withFailMessage("sign-in %d: %s", i + 1, signedIn.body())
                    .isEqualTo(200);
        }

        assertThat(failuresAcrossSalons()).as("every one gave its count back").isZero();
    }

    @Test
    void aFailureAfterManyCorrectSignInsStillCounts() {
        for (int i = 0; i < 10; i++) {
            assertThat(signIn(salons.get(i % 2), staff(i % ACCOUNTS_PER_SALON), password)
                            .status())
                    .isEqualTo(200);
        }

        assertThat(signIn(alpha, staff(0), "wrong password").status()).isEqualTo(401);

        assertThat(failuresAcrossSalons()).isEqualTo(1);
    }

    @Test
    void twoCorrectSignInsAtOnceGiveBackExactlyTwo() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(signIn(beta, "nobody-" + i + "@example.test", "wrong password")
                            .status())
                    .isEqualTo(401);
        }

        Future<Http.Exchange> first = requests.submit(() -> signIn(alpha, staff(0), password));
        Future<Http.Exchange> second = requests.submit(() -> signIn(beta, staff(1), password));

        assertThat(first.get(15, TimeUnit.SECONDS).status()).isEqualTo(200);
        assertThat(second.get(15, TimeUnit.SECONDS).status()).isEqualTo(200);
        assertThat(failuresAcrossSalons())
                .as("the three failures, and nothing of the two correct ones")
                .isEqualTo(3);
    }

    @Test
    void aSignInRefused503BecauseTheChecksAreBusyCountsTowardNoLimit() throws Exception {
        holdNextCheck.set(true);
        Future<Http.Exchange> held = requests.submit(() -> signIn(alpha, staff(0), "wrong one"));
        assertThat(checking.await(15, TimeUnit.SECONDS)).isTrue();

        for (int i = 0; i < 3; i++) {
            assertThat(signIn(alpha, staff(0), "wrong again").status())
                    .as("the only check is busy")
                    .isEqualTo(503);
        }
        release.countDown();
        assertThat(held.get(15, TimeUnit.SECONDS).status()).isEqualTo(401);

        assertThat(identifierAttempts(alpha))
                .as("the identifier's attempts: only the one that was checked")
                .isEqualTo(1);
        assertThat(owner.queryForObject("SELECT sum(attempts) FROM shared_address_attempt", Integer.class))
                .as("the address's attempts across salons: only the one that was checked")
                .isEqualTo(1);
        assertThat(signIn(alpha, staff(0), password).status()).isEqualTo(200);
    }

    @Test
    void aStaffAccountsPasswordIsHashedOutsideAnyTransaction() {
        String token = ownerToken();
        transactionOpenAtHash.clear();

        Http.Exchange created = call(
                alpha,
                "POST",
                "/staff",
                Map.of("email", "new-" + UUID.randomUUID() + "@example.test", "password", password, "role", "MANAGER"),
                token);

        assertThat(created.status()).withFailMessage("%s", created.body()).isEqualTo(201);
        assertThat(transactionOpenAtHash).containsExactly(false);
    }

    @Test
    void aNewOwnersPasswordIsHashedThroughThePasswordChecksOutsideAnyTransaction() {
        transactionOpenAtHash.clear();

        assertThat(provisioning.prepareOwner("owner.", "First Owner", "first-owner@example.test", password))
                .isNotNull();
        assertThat(transactionOpenAtHash).containsExactly(false);
        assertThatIllegalStateException()
                .as("refused inside a transaction, as every password check is")
                .isThrownBy(() -> platform.inTransaction(status ->
                        provisioning.prepareOwner("owner.", "First Owner", "first-owner@example.test", password)));
    }

    @Test
    void neitherRuntimeRoleReachesTheCountersButThroughItsOwnFunction() throws SQLException {
        String[] table = {
            "SELECT * FROM shared_address_attempt",
            "DELETE FROM shared_address_attempt",
            "INSERT INTO shared_address_attempt VALUES ('x', 'y', now(), now() + interval '1 minute', 1, now(), now())"
        };
        String count = "SELECT shared_count_address_attempt('staff-sign-in', 'h', now(), interval '15 minutes')";
        String giveBack = "SELECT shared_return_address_attempt('staff-sign-in', 'h', now())";
        String cleanup = "SELECT shared_remove_spent_address_attempts(now(), 10)";
        try (Connection application = database.connectAsApplication();
                Connection platformRole = database.connectAsPlatform()) {
            refused(application, table);
            refused(application, cleanup);
            refused(platformRole, table);
            refused(platformRole, count, giveBack);
        }
    }

    private static void refused(Connection connection, String... statements) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                assertThatExceptionOfType(SQLException.class)
                        .as(sql)
                        .isThrownBy(() -> statement.execute(sql))
                        .satisfies(refused -> assertThat(refused.getSQLState()).isEqualTo("42501"));
            }
        }
    }

    @Test
    void theCleanupRemovesOnlyTheCountersWhoseWindowHasEnded() {
        signIn(alpha, "nobody@example.test", "wrong password");
        owner.update(
                "INSERT INTO shared_address_attempt VALUES ('staff-sign-in', ?, now() - interval '20 minutes',"
                        + " now() - interval '5 minutes', 3, now(), now())",
                "a".repeat(64));

        // As the platform job calls it: on the platform connection.
        Integer removed = platform.inTransaction(status -> platform.jdbc()
                .sql("SELECT shared_remove_spent_address_attempts(now(), 1000)")
                .query(Integer.class)
                .single());

        assertThat(removed).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM shared_address_attempt", Integer.class))
                .as("the window still open is kept")
                .isEqualTo(1);
    }

    private void add(TestSalon salon, String email, String mobile, StaffRole role) throws Exception {
        TenantContext.callAs(
                new TestSalons(owner).asCurrent(salon),
                () -> new TransactionTemplate(manager).execute(status -> {
                    StaffAccount account =
                            StaffAccount.create(email, mobile, passwordHash, role, false, clock.instant());
                    em.persist(account);
                    return account;
                }));
    }

    /** An Owner of alpha signed in with two-step verification. */
    private String ownerToken() {
        String secret = crypto.newTotpSecret();
        try {
            TenantContext.callAs(
                    new TestSalons(owner).asCurrent(alpha),
                    () -> new TransactionTemplate(manager).execute(status -> {
                        StaffAccount account = StaffAccount.create(
                                "owner@example.test",
                                "+94770000399",
                                crypto.encodePassword(password),
                                StaffRole.OWNER,
                                false,
                                clock.instant());
                        account.enrollMfa(
                                crypto.encrypt("totp:" + alpha.id() + ":" + account.getId(), secret),
                                account.getCreatedAt());
                        em.persist(account);
                        return account;
                    }));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        Http.Exchange signedIn = signIn(alpha, "owner@example.test", password);
        assertThat(signedIn.status()).withFailMessage("%s", signedIn.body()).isEqualTo(200);
        Map<String, Object> challenge = json.readValue(signedIn.body(), Map.class);
        Http.Exchange verified = call(
                alpha,
                "POST",
                "/staff/mfa/verify",
                Map.of(
                        "challengeId",
                        challenge.get("challengeId"),
                        "challengeToken",
                        challenge.get("challengeToken"),
                        "code",
                        crypto.totpCode(secret, clock.instant().getEpochSecond() / 30)),
                null);
        assertThat(verified.status()).withFailMessage("%s", verified.body()).isEqualTo(200);
        return (String) json.readValue(verified.body(), Map.class).get("accessToken");
    }

    private static String staff(int number) {
        return "staff-" + number + "@example.test";
    }

    private int failuresAcrossSalons() {
        return owner.queryForObject("SELECT coalesce(sum(attempts), 0) FROM shared_address_attempt", Integer.class);
    }

    private int identifierAttempts(TestSalon salon) {
        return owner.queryForObject(
                "SELECT sum(attempts) FROM identity_rate_bucket WHERE tenant_id = ? AND dimension = 'staff-identifier'",
                Integer.class,
                salon.id());
    }

    private Http.Exchange signIn(TestSalon salon, String identifier, String secret) {
        return call(
                salon,
                "POST",
                "/staff/sign-in",
                Map.of("identifier", identifier, "password", secret, "deviceLabel", "JUnit device"),
                null);
    }

    private Http.Exchange call(TestSalon salon, String method, String path, Object request, String token) {
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
}
