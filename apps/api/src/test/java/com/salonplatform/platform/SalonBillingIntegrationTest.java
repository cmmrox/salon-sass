package com.salonplatform.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.identity.application.SessionAccess;
import com.salonplatform.identity.domain.CustomerAccount;
import com.salonplatform.identity.domain.StaffAccount;
import com.salonplatform.identity.domain.StaffRole;
import com.salonplatform.identity.infrastructure.IdentityCrypto;
import com.salonplatform.platform.application.BillingJob;
import com.salonplatform.platform.application.OperatorAccounts;
import com.salonplatform.platform.infrastructure.OperatorCrypto;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.testing.Http;
import com.salonplatform.testing.IntegrationTest;
import com.salonplatform.testing.MovableClock;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import com.salonplatform.testing.TestSalons;
import com.salonplatform.testing.TestSalons.TestSalon;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * F03-S02 on a salon's own host: a read-only salon keeps reads, sign-in, MFA, renewal, sign-out,
 * password change and customer privacy rights and refuses every other mutation while another salon is
 * unaffected; staff seats are enforced with one winner for the last seat; staff read their own salon's
 * entitlements, only the Owner its invoices, and the customer principal neither (AC-8 to AC-10, AC-12).
 */
// JSON bodies are read as untyped maps (Map.class); each cast states the shape the assertion
// beside it then checks, so a wrong shape fails that assertion with a ClassCastException.
@SuppressWarnings("unchecked")
class SalonBillingIntegrationTest extends IntegrationTest {

    static final MovableClock CLOCK = new MovableClock(Instant.now().truncatedTo(ChronoUnit.SECONDS));

    @TestConfiguration
    static class Overrides {
        @Bean
        @Primary
        Clock movableClock() {
            return CLOCK;
        }
    }

    @Autowired
    OperatorAccounts accounts;

    @Autowired
    OperatorCrypto crypto;

    @Autowired
    IdentityCrypto identityCrypto;

    @Autowired
    JsonMapper json;

    @Autowired
    Environment environment;

    @Autowired
    PlatformTransactionManager manager;

    @Autowired
    SessionAccess sessions;

    @Autowired
    BillingJob job;

    @PersistenceContext
    EntityManager em;

    private final JdbcTemplate owner = TestDatabase.of(TestPostgres.shared()).asOwner();
    private BillingTestSupport billing;

    @BeforeEach
    void setUp() {
        CLOCK.set(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        OperatorTestSupport console =
                new OperatorTestSupport(http(), port(), accounts, crypto, json, environment, CLOCK);
        billing = new BillingTestSupport(console, http(), port(), owner, identityCrypto, json, manager, em, CLOCK);
    }

    private static void status(Http.Exchange exchange, int expected) {
        BillingTestSupport.status(exchange, expected);
    }

    private Map<String, Object> body(Http.Exchange exchange) {
        return billing.body(exchange);
    }

    private long activeStaff(TestSalon salon) {
        return owner.queryForObject(
                "SELECT count(*) FROM identity_staff WHERE tenant_id = ? AND active", Long.class, salon.id());
    }

    private Map<String, Object> customerTokens(TestSalon salon) throws Exception {
        return TenantContext.callAs(
                new TestSalons(owner).asCurrent(salon),
                () -> new TransactionTemplate(manager).execute(status -> {
                    CustomerAccount customer = CustomerAccount.verified(
                            "+9477" + String.format("%07d", (int) (Math.random() * 9_000_000) + 1_000_000),
                            CLOCK.instant());
                    em.persist(customer);
                    em.flush();
                    var tokens = sessions.newCustomer(customer, "JUnit phone");
                    return Map.<String, Object>of(
                            "accessToken", tokens.accessToken(), "refreshToken", tokens.refreshToken());
                }));
    }

    // --- AC-8 and AC-9 ---------------------------------------------------------------------------

    @Test
    void aReadOnlySalonKeepsReadsSignInAndPrivacyRightsAndRefusesEveryOtherMutationWhileAnotherIsUnaffected()
            throws Exception {
        String plan = billing.plan(10);
        TestSalon salon = billing.legacy(plan);
        TestSalon other = billing.legacy(plan);
        StaffAccount ownerAccount = billing.staff(salon, StaffRole.OWNER, BillingTestSupport.email("owner"));
        StaffAccount manager = billing.staff(salon, StaffRole.MANAGER, BillingTestSupport.email("manager"));
        StaffAccount removable = billing.staff(salon, StaffRole.RECEPTIONIST, BillingTestSupport.email("desk"));
        StaffAccount otherOwner = billing.staff(other, StaffRole.OWNER, BillingTestSupport.email("owner"));
        Map<String, Object> customer = customerTokens(salon);
        owner.update("UPDATE tenant SET access_state = 'read_only' WHERE id = ?", salon.id());

        // Sign-in and MFA work.
        Map<String, Object> ownerTokens = billing.tokens(salon, ownerAccount);
        String ownerToken = (String) ownerTokens.get("accessToken");
        Map<String, Object> managerTokens = billing.tokens(salon, manager);
        String managerToken = (String) managerTokens.get("accessToken");

        // Reads work.
        status(billing.identityCall(salon, "GET", "/me", null, ownerToken), 200);
        status(billing.identityCall(salon, "GET", "/staff", null, ownerToken), 200);
        status(billing.identityCall(salon, "GET", "/sessions", null, managerToken), 200);
        status(billing.identityCall(salon, "GET", "/audit", null, ownerToken), 200);
        status(billing.salonCall(salon, "GET", "/api/v1/salon", null, null), 200);
        status(billing.salonCall(salon, "GET", "/api/v1/salon/entitlements", null, managerToken), 200);
        status(
                billing.salonCall(
                        salon, "GET", "/api/v1/identity/customers/me", null, (String) customer.get("accessToken")),
                200);
        status(
                billing.salonCall(salon, "GET", "/api/v1/identity/customers/me/export", null, (String)
                        customer.get("accessToken")),
                200);

        // Every other mutation is refused before any change, signed in or not.
        long before = activeStaff(salon);
        Object[][] refused = {
            {
                "POST",
                "/staff",
                BillingTestSupport.staffRequest(
                        BillingTestSupport.email("new"), "RECEPTIONIST", "synthetic initial password")
            },
            {"PATCH", "/staff/" + removable.getId(), Map.of("version", 0, "role", "MANAGER")},
            {"DELETE", "/staff/" + removable.getId(), null}
        };
        for (Object[] mutation : refused) {
            for (String token : new String[] {ownerToken, null}) {
                Http.Exchange denied =
                        billing.identityCall(salon, (String) mutation[0], (String) mutation[1], mutation[2], token);
                status(denied, 403);
                assertThat(denied.body()).contains("salon_read_only");
            }
        }
        status(billing.salonCall(salon, "POST", "/api/v1/identity/not-yet-a-route", Map.of(), ownerToken), 403);
        assertThat(activeStaff(salon)).isEqualTo(before);
        assertThat(owner.queryForObject(
                        "SELECT role FROM identity_staff WHERE tenant_id = ? AND id = ?",
                        String.class,
                        salon.id(),
                        removable.getId()))
                .isEqualTo("RECEPTIONIST");

        // Renewal, password change, sign-out and the customer's privacy rights still work.
        Http.Exchange renewed = billing.identityCall(
                salon, "POST", "/sessions/refresh", Map.of("refreshToken", managerTokens.get("refreshToken")), null);
        status(renewed, 200);
        String renewedToken = (String) body(renewed).get("accessToken");
        status(
                billing.identityCall(
                        salon,
                        "PUT",
                        "/staff/password",
                        Map.of(
                                "oldPassword",
                                billing.password,
                                "newPassword",
                                "another synthetic password " + UUID.randomUUID()),
                        renewedToken),
                200);
        status(
                billing.identityCall(salon, "DELETE", "/sessions/" + ownerTokens.get("sessionId"), null, ownerToken),
                204);
        String customerToken = (String) customer.get("accessToken");
        Map<String, Object> profile =
                body(billing.salonCall(salon, "GET", "/api/v1/identity/customers/me", null, customerToken));
        Map<String, Object> update = new LinkedHashMap<>();
        update.put("version", profile.get("version"));
        update.put("name", "Synthetic Customer");
        update.put("email", null);
        update.put("birthday", null);
        update.put("preferredLanguage", "en");
        update.put("notificationPreferences", Map.of("sms", true, "whatsapp", false, "email", true));
        update.put("marketingConsent", false);
        status(billing.salonCall(salon, "PUT", "/api/v1/identity/customers/me", update, customerToken), 200);
        status(
                billing.salonCall(
                        salon, "DELETE", "/api/v1/identity/customers/me", Map.of("confirm", true), customerToken),
                204);

        // Another salon is unaffected throughout.
        String otherToken = billing.token(other, otherOwner);
        status(
                billing.identityCall(
                        other,
                        "POST",
                        "/staff",
                        BillingTestSupport.staffRequest(
                                BillingTestSupport.email("fine"), "RECEPTIONIST", "synthetic initial password"),
                        otherToken),
                201);

        // Paid again: mutations work on the next request.
        owner.update("UPDATE tenant SET access_state = 'full' WHERE id = ?", salon.id());
        String fresh = billing.token(salon, ownerAccount);
        status(
                billing.identityCall(
                        salon,
                        "POST",
                        "/staff",
                        BillingTestSupport.staffRequest(
                                BillingTestSupport.email("back"), "RECEPTIONIST", "synthetic initial password"),
                        fresh),
                201);
    }

    // --- AC-10 -----------------------------------------------------------------------------------

    @Test
    void staffSeatsAreEnforcedARemovalFreesOneAndTheLastSeatHasOneWinner() throws Exception {
        TestSalon salon = billing.legacy(billing.plan(2));
        StaffAccount ownerAccount = billing.staff(salon, StaffRole.OWNER, BillingTestSupport.email("owner"));
        String token = billing.token(salon, ownerAccount);
        Http.Exchange second = billing.identityCall(
                salon,
                "POST",
                "/staff",
                BillingTestSupport.staffRequest(
                        BillingTestSupport.email("second"), "MANAGER", "synthetic initial password"),
                token);
        status(second, 201);
        Http.Exchange third = billing.identityCall(
                salon,
                "POST",
                "/staff",
                BillingTestSupport.staffRequest(
                        BillingTestSupport.email("third"), "MANAGER", "synthetic initial password"),
                token);
        status(third, 409);
        assertThat(third.body()).contains("entitlement_exceeded").contains("\"field\":\"staffSeats\"");
        assertThat(activeStaff(salon)).isEqualTo(2);
        status(billing.identityCall(salon, "DELETE", "/staff/" + body(second).get("id"), null, token), 204);
        status(
                billing.identityCall(
                        salon,
                        "POST",
                        "/staff",
                        BillingTestSupport.staffRequest(
                                BillingTestSupport.email("fourth"), "MANAGER", "synthetic initial password"),
                        token),
                201);
        assertThat(activeStaff(salon)).isEqualTo(2);

        // Two (here five) simultaneous creations for the last seat create exactly one.
        TestSalon race = billing.legacy(billing.plan(2));
        StaffAccount raceOwner = billing.staff(race, StaffRole.OWNER, BillingTestSupport.email("owner"));
        String raceToken = billing.token(race, raceOwner);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(5);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String email = BillingTestSupport.email("racer" + i);
            Callable<Integer> create = () -> {
                start.await();
                return billing.identityCall(
                                race,
                                "POST",
                                "/staff",
                                BillingTestSupport.staffRequest(email, "RECEPTIONIST", "synthetic initial password"),
                                raceToken)
                        .status();
            };
            results.add(pool.submit(create));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> result : results) {
            statuses.add(result.get());
        }
        pool.shutdown();
        assertThat(statuses).containsOnly(201, 409);
        assertThat(statuses.stream().filter(code -> code == 201).count()).isEqualTo(1);
        assertThat(activeStaff(race)).isEqualTo(2);
    }

    // --- AC-12 -----------------------------------------------------------------------------------

    @Test
    void staffReadTheirOwnEntitlementsOnlyTheOwnerReadsInvoicesAndTheCustomerNeither() throws Exception {
        String plan = billing.plan(7);
        TestSalon salon = billing.legacy(plan);
        TestSalon other = billing.legacy(plan);
        Map<String, Object> start = new LinkedHashMap<>();
        start.put("monthlyAmount", 500000);
        start.put("paymentMethod", "bank_transfer");
        start.put("currency", "LKR");
        status(billing.startSubscription(salon.id(), start, UUID.randomUUID().toString()), 201);
        status(billing.startSubscription(other.id(), start, UUID.randomUUID().toString()), 201);
        Map<StaffRole, StaffAccount> staff = new LinkedHashMap<>();
        for (StaffRole role : StaffRole.values()) {
            staff.put(
                    role,
                    billing.staff(
                            salon, role, BillingTestSupport.email(role.name().toLowerCase())));
        }
        StaffAccount otherOwner = billing.staff(other, StaffRole.OWNER, BillingTestSupport.email("owner"));
        CLOCK.advance(Duration.ofDays(14));
        job.run(CLOCK.instant());
        UUID otherInvoice = owner.queryForObject("SELECT id FROM invoice WHERE salon_id = ?", UUID.class, other.id());

        for (StaffRole role : StaffRole.values()) {
            String token = billing.token(salon, staff.get(role));
            Http.Exchange read = billing.salonCall(salon, "GET", "/api/v1/salon/entitlements", null, token);
            status(read, 200);
            Map<String, Object> entitlements = body(read);
            assertThat((Map<String, Object>) entitlements.get("limits")).containsEntry("staffSeats", 7);
            assertThat((Map<String, Object>) entitlements.get("usage")).containsEntry("staffSeats", 5);
            assertThat((Map<String, Object>) entitlements.get("subscription")).containsEntry("state", "active");
            assertThat((Map<String, Object>) entitlements.get("features")).containsKeys("customDomain", "dedicatedApp");
            Http.Exchange invoices = billing.salonCall(salon, "GET", "/api/v1/salon/invoices", null, token);
            if (role == StaffRole.OWNER) {
                status(invoices, 200);
                assertThat(body(invoices)).containsEntry("total", 1);
                Map<String, Object> first =
                        ((List<Map<String, Object>>) body(invoices).get("items")).get(0);
                status(billing.salonCall(salon, "GET", "/api/v1/salon/invoices/" + first.get("id"), null, token), 200);
                // Another salon's invoice is not found, as one that does not exist.
                Http.Exchange foreign =
                        billing.salonCall(salon, "GET", "/api/v1/salon/invoices/" + otherInvoice, null, token);
                status(foreign, 404);
                status(
                        billing.salonCall(salon, "GET", "/api/v1/salon/invoices/" + UUID.randomUUID(), null, token),
                        404);
            } else {
                status(invoices, 403);
                assertThat(invoices.body()).contains("permission_denied");
            }
        }
        // A salon's staff token on another salon's host is not found.
        String salonOwner = billing.token(salon, staff.get(StaffRole.OWNER));
        status(billing.salonCall(other, "GET", "/api/v1/salon/entitlements", null, salonOwner), 404);
        String otherToken = billing.token(other, otherOwner);
        assertThat(((Map<String, Object>)
                        body(billing.salonCall(other, "GET", "/api/v1/salon/entitlements", null, otherToken))
                                .get("usage")))
                .containsEntry("staffSeats", 1);

        String customer = (String) customerTokens(salon).get("accessToken");
        for (String path : List.of("/api/v1/salon/entitlements", "/api/v1/salon/invoices")) {
            Http.Exchange denied = billing.salonCall(salon, "GET", path, null, customer);
            status(denied, 403);
            assertThat(denied.body()).contains("permission_denied");
        }
        status(billing.salonCall(salon, "GET", "/api/v1/salon/entitlements", null, null), 401);

        // A legacy salon without a plan or subscription: no limits, no subscription, usage counted.
        TestSalon bare = billing.legacy(null);
        StaffAccount bareOwner = billing.staff(bare, StaffRole.OWNER, BillingTestSupport.email("owner"));
        Map<String, Object> none = body(
                billing.salonCall(bare, "GET", "/api/v1/salon/entitlements", null, billing.token(bare, bareOwner)));
        assertThat(none.get("plan")).isNull();
        assertThat(none.get("limits")).isNull();
        assertThat(none.get("subscription")).isNull();
        assertThat((Map<String, Object>) none.get("usage")).containsEntry("staffSeats", 1);
    }
}
