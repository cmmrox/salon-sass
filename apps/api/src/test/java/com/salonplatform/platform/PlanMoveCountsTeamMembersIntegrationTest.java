package com.salonplatform.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.identity.adapter.IdentityCrypto;
import com.salonplatform.identity.domain.StaffRole;
import com.salonplatform.identity.entity.StaffAccount;
import com.salonplatform.platform.adapter.OperatorCrypto;
import com.salonplatform.platform.service.OperatorAccountService;
import com.salonplatform.testing.Http;
import com.salonplatform.testing.IntegrationTest;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import com.salonplatform.testing.TestSalons.TestSalon;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * A plan move counts team members with no linked account, the same count the salon reads as {@code usage.staffSeats}
 * (F06-S01 AC-6, decision 100), and cannot interleave with a request taking a seat.
 */
class PlanMoveCountsTeamMembersIntegrationTest extends IntegrationTest {

    private static final String MEMBERS = "/api/v1/team/members";

    @Autowired
    OperatorAccountService accounts;

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
    Clock clock;

    @PersistenceContext
    EntityManager em;

    private final JdbcTemplate owner = TestDatabase.of(TestPostgres.shared()).asOwner();
    private BillingTestSupport billing;

    @BeforeEach
    void setUp() {
        OperatorTestSupport console =
                new OperatorTestSupport(http(), port(), accounts, crypto, json, environment, clock);
        billing = new BillingTestSupport(console, http(), port(), owner, identityCrypto, json, manager, em, clock);
    }

    private Http.Exchange move(UUID salon, long version, String planId) {
        Map<String, Object> move = new LinkedHashMap<>();
        move.put("version", version);
        move.put("planId", planId);
        return billing.console.callOn(
                billing.console.host(), "PUT", "/salons/" + salon + "/plan", move, billing.admin(), Map.of());
    }

    private long versionOf(UUID salon) {
        return owner.queryForObject("SELECT version FROM tenant WHERE id = ?", Long.class, salon);
    }

    private long seatsUsed(TestSalon salon) {
        return owner.queryForObject("""
                SELECT (SELECT count(*) FROM identity_staff WHERE tenant_id = ? AND active)
                     + (SELECT count(*) FROM staff_team_member
                         WHERE tenant_id = ? AND removed_at IS NULL AND sign_in_account_id IS NULL)
                """, Long.class, salon.id(), salon.id());
    }

    private int planSeats(TestSalon salon) {
        return owner.queryForObject(
                "SELECT p.staff_seats FROM tenant t JOIN plan p ON p.id = t.plan_id WHERE t.id = ?",
                Integer.class,
                salon.id());
    }

    @Test
    void aMoveBelowTheSeatsUsedIsRefusedWithMembersCounted() throws Exception {
        String tight = billing.plan(1);
        String enough = billing.plan(2);
        TestSalon salon = billing.legacy(billing.plan(5));
        StaffAccount salonOwner = billing.staff(salon, StaffRole.OWNER, BillingTestSupport.email("owner"));
        String token = billing.token(salon, salonOwner);
        BillingTestSupport.status(
                billing.salonCall(salon, "POST", MEMBERS, Map.of("name", "Nimali Perera"), token), 201);

        Http.Exchange refused = move(salon.id(), versionOf(salon.id()), tight);

        BillingTestSupport.status(refused, 409);
        assertThat(refused.body()).contains("entitlement_exceeded").contains("\"staffSeats\"");
        assertThat(planSeats(salon)).isEqualTo(5);

        BillingTestSupport.status(move(salon.id(), versionOf(salon.id()), enough), 200);
        assertThat(planSeats(salon)).isEqualTo(2);
    }

    @Test
    void usageAndThePlanMoveReportTheSameCount() throws Exception {
        TestSalon salon = billing.legacy(billing.plan(5));
        StaffAccount salonOwner = billing.staff(salon, StaffRole.OWNER, BillingTestSupport.email("owner"));
        String token = billing.token(salon, salonOwner);
        BillingTestSupport.status(billing.salonCall(salon, "POST", MEMBERS, Map.of("name", "One Person"), token), 201);
        BillingTestSupport.status(billing.salonCall(salon, "POST", MEMBERS, Map.of("name", "Two People"), token), 201);

        Http.Exchange read = billing.salonCall(salon, "GET", "/api/v1/salon/entitlements", null, token);

        BillingTestSupport.status(read, 200);
        assertThat(billing.body(read).get("usage")).isEqualTo(Map.of("staffSeats", 3));
        BillingTestSupport.status(move(salon.id(), versionOf(salon.id()), billing.plan(2)), 409);
        BillingTestSupport.status(move(salon.id(), versionOf(salon.id()), billing.plan(3)), 200);
    }

    @Test
    void aMoveAndARequestForASeatAtOnceNeverLeaveTheSalonOverItsPlan() throws Exception {
        for (int round = 0; round < 10; round++) {
            String tight = billing.plan(1);
            TestSalon salon = billing.legacy(billing.plan(5));
            StaffAccount salonOwner = billing.staff(salon, StaffRole.OWNER, BillingTestSupport.email("owner"));
            String token = billing.token(salon, salonOwner);
            long version = versionOf(salon.id());
            billing.admin();

            CompletableFuture<Http.Exchange> adding = CompletableFuture.supplyAsync(
                    () -> billing.salonCall(salon, "POST", MEMBERS, Map.of("name", "Racing Member"), token));
            CompletableFuture<Http.Exchange> moving =
                    CompletableFuture.supplyAsync(() -> move(salon.id(), version, tight));
            List<Integer> statuses = List.of(
                    adding.get(60, TimeUnit.SECONDS).status(),
                    moving.get(60, TimeUnit.SECONDS).status());

            assertThat(statuses).as("round %d", round).allMatch(status -> status < 500);
            assertThat(seatsUsed(salon)).as("round %d: %s", round, statuses).isLessThanOrEqualTo(planSeats(salon));
        }
    }
}
