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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
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
 * A plan move and a request for a staff seat, in each order, with the first one's transaction held open so that the
 * second certainly waits for it (F06-S01 AC-6, the lead's ruling that a salon never ends over its plan). A timed race
 * reaches these orders only sometimes: {@code salon_entitlements()} read "no plan" for whoever waited behind a move
 * until V29, and the race in {@link PlanMoveCountsTeamMembersIntegrationTest} passed with that defect present.
 */
class PlanMoveAndSeatOrderIntegrationTest extends IntegrationTest {

    private static final String MEMBERS = "/api/v1/team/members";
    private static final String ACCOUNTS = "/api/v1/identity/staff";

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
    private TestSalon salon;
    private String token;

    @BeforeEach
    void setUp() {
        OperatorTestSupport console =
                new OperatorTestSupport(http(), port(), accounts, crypto, json, environment, clock);
        billing = new BillingTestSupport(console, http(), port(), owner, identityCrypto, json, manager, em, clock);
    }

    /** A salon on this plan (null for none), whose one active account is its Owner: one seat used. */
    private void salonOn(String planId) throws Exception {
        salon = billing.legacy(planId);
        StaffAccount salonOwner = billing.staff(salon, StaffRole.OWNER, BillingTestSupport.email("owner"));
        token = billing.token(salon, salonOwner);
    }

    @FunctionalInterface
    private interface HeldWork {
        void run(Connection connection) throws Exception;
    }

    /** Sends the request while the work's transaction is open, sees it wait, commits, and answers what it got. */
    private Http.Exchange behind(HeldWork work, Callable<Http.Exchange> request) throws Exception {
        try (Connection held = owner.getDataSource().getConnection()) {
            held.setAutoCommit(false);
            work.run(held);
            long waitingBefore = waitingForALock();
            CompletableFuture<Http.Exchange> asking = CompletableFuture.supplyAsync(() -> {
                try {
                    return request.call();
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            });
            for (int tries = 0; tries < 400 && waitingForALock() <= waitingBefore && !asking.isDone(); tries++) {
                Thread.sleep(25);
            }
            assertThat(asking.isDone()).as("the request has not been answered").isFalse();
            assertThat(waitingForALock()).as("the request waits for the lock").isGreaterThan(waitingBefore);
            held.commit();
            return asking.get(60, TimeUnit.SECONDS);
        }
    }

    private long waitingForALock() {
        return owner.queryForObject("SELECT count(*) FROM pg_locks WHERE NOT granted", Long.class);
    }

    // --- what is held open ----------------------------------------------------------------------------------------

    /** What the operator's plan move does to the registry row, uncommitted. */
    private HeldWork aMoveTo(String planId) {
        return held -> {
            try (PreparedStatement update =
                    held.prepareStatement("UPDATE tenant SET plan_id = ?::uuid, version = version + 1 WHERE id = ?")) {
                update.setString(1, planId);
                update.setObject(2, salon.id());
                assertThat(update.executeUpdate()).isEqualTo(1);
            }
        };
    }

    /** An update of the registry row that changes no plan: the waiter wakes and rechecks, with nothing to find. */
    private HeldWork aChangeOfNoPlan() {
        return held -> {
            try (PreparedStatement update =
                    held.prepareStatement("UPDATE tenant SET version = version + 1 WHERE id = ?")) {
                update.setObject(1, salon.id());
                assertThat(update.executeUpdate()).isEqualTo(1);
            }
        };
    }

    /** The registry row share-locked as a request taking a seat holds it, the way {@code salon_entitlements} does. */
    private void shareLockTheRegistryRow(Connection held) throws Exception {
        try (PreparedStatement lock = held.prepareStatement("SELECT id FROM tenant WHERE id = ? FOR SHARE")) {
            lock.setObject(1, salon.id());
            lock.executeQuery().close();
        }
    }

    /** A member with no sign-in being added and not yet committed, holding the registry row as that request does. */
    private HeldWork aMemberBeingAdded() {
        return held -> {
            shareLockTheRegistryRow(held);
            try (PreparedStatement insert = held.prepareStatement("""
                    INSERT INTO staff_team_member (tenant_id, id, branch_id, name, shown_online, version, created_at,
                        updated_at)
                    VALUES (?, ?, (SELECT id FROM branding_branch WHERE tenant_id = ? AND is_default), 'Held Member',
                        true, 0, now(), now())
                    """)) {
                insert.setObject(1, salon.id());
                insert.setObject(2, UUID.randomUUID());
                insert.setObject(3, salon.id());
                assertThat(insert.executeUpdate()).isEqualTo(1);
            }
        };
    }

    /** An account being created and not yet committed, holding the registry row as that request does. */
    private HeldWork anAccountBeingCreated() {
        return held -> {
            shareLockTheRegistryRow(held);
            try (PreparedStatement insert = held.prepareStatement("""
                    INSERT INTO identity_staff (id, tenant_id, created_at, updated_at, email, password_hash, role,
                        active, professional_grant, version)
                    VALUES (?, ?, now(), now(), ?, 'not a real hash', 'RECEPTIONIST', true, false, 0)
                    """)) {
                insert.setObject(1, UUID.randomUUID());
                insert.setObject(2, salon.id());
                insert.setString(3, BillingTestSupport.email("held"));
                assertThat(insert.executeUpdate()).isEqualTo(1);
            }
        };
    }

    // --- the requests -------------------------------------------------------------------------------------------

    private Http.Exchange addMember() {
        return billing.salonCall(salon, "POST", MEMBERS, Map.of("name", "Waiting Member"), token);
    }

    private Http.Exchange createAccount() {
        return billing.salonCall(
                salon,
                "POST",
                ACCOUNTS,
                BillingTestSupport.staffRequest(
                        BillingTestSupport.email("waiting"), "RECEPTIONIST", "synthetic initial password"),
                token);
    }

    private Http.Exchange moveTo(String planId) {
        Map<String, Object> move = new LinkedHashMap<>();
        move.put("version", owner.queryForObject("SELECT version FROM tenant WHERE id = ?", Long.class, salon.id()));
        move.put("planId", planId);
        return billing.console.callOn(
                billing.console.host(), "PUT", "/salons/" + salon.id() + "/plan", move, billing.admin(), Map.of());
    }

    // --- what is read ---------------------------------------------------------------------------------------------

    private long seatsUsed() {
        return owner.queryForObject("""
                SELECT (SELECT count(*) FROM identity_staff WHERE tenant_id = ? AND active)
                     + (SELECT count(*) FROM staff_team_member
                         WHERE tenant_id = ? AND removed_at IS NULL AND sign_in_account_id IS NULL)
                """, Long.class, salon.id(), salon.id());
    }

    private UUID planOfSalon() {
        return owner.queryForObject("SELECT plan_id FROM tenant WHERE id = ?", UUID.class, salon.id());
    }

    private static void noFreeSeat(Http.Exchange exchange) {
        BillingTestSupport.status(exchange, 409);
        assertThat(exchange.body()).contains("entitlement_exceeded").contains("\"staffSeats\"");
    }

    // --- a move, then a request for a seat ----------------------------------------------------------------------

    @Test
    void aMemberWaitingBehindAMoveIsHeldToTheNewPlan() throws Exception {
        salonOn(billing.plan(5));
        String tight = billing.plan(1);

        Http.Exchange answer = behind(aMoveTo(tight), this::addMember);

        noFreeSeat(answer);
        assertThat(seatsUsed()).isEqualTo(1);
        assertThat(planOfSalon()).isEqualTo(UUID.fromString(tight));
    }

    @Test
    void anAccountWaitingBehindAMoveIsHeldToTheNewPlan() throws Exception {
        salonOn(billing.plan(5));
        String tight = billing.plan(1);

        Http.Exchange answer = behind(aMoveTo(tight), this::createAccount);

        noFreeSeat(answer);
        assertThat(seatsUsed()).isEqualTo(1);
    }

    @Test
    void aMemberWaitingBehindAMoveToAPlanWithRoomIsAdded() throws Exception {
        salonOn(billing.plan(1));
        String roomy = billing.plan(2);

        Http.Exchange answer = behind(aMoveTo(roomy), this::addMember);

        BillingTestSupport.status(answer, 201);
        assertThat(seatsUsed()).isEqualTo(2);
    }

    @Test
    void anAccountWaitingBehindAMoveToAPlanWithRoomIsCreated() throws Exception {
        salonOn(billing.plan(1));
        String roomy = billing.plan(2);

        Http.Exchange answer = behind(aMoveTo(roomy), this::createAccount);

        BillingTestSupport.status(answer, 201);
        assertThat(seatsUsed()).isEqualTo(2);
    }

    @Test
    void aSalonGivenItsFirstPlanIsHeldToItByWhoeverWaitedBehindTheMove() throws Exception {
        salonOn(null);
        String tight = billing.plan(1);

        Http.Exchange answer = behind(aMoveTo(tight), this::addMember);

        noFreeSeat(answer);
    }

    @Test
    void aSalonGenuinelyWithoutAPlanIsStillUnlimitedAfterWaiting() throws Exception {
        salonOn(null);

        Http.Exchange answer = behind(aChangeOfNoPlan(), this::addMember);

        BillingTestSupport.status(answer, 201);
        assertThat(planOfSalon()).isNull();
        BillingTestSupport.status(createAccount(), 201);
        assertThat(seatsUsed()).isEqualTo(3);
    }

    @Test
    void aSalonOnAPlanKeepsItsLimitAfterWaitingBehindAChangeOfSomethingElse() throws Exception {
        salonOn(billing.plan(1));

        Http.Exchange answer = behind(aChangeOfNoPlan(), this::addMember);

        noFreeSeat(answer);
    }

    // --- a request for a seat, then a move ----------------------------------------------------------------------

    @Test
    void aMoveWaitingBehindAMemberBeingAddedCountsThatMember() throws Exception {
        String roomy = billing.plan(5);
        salonOn(roomy);
        String oneSeat = billing.plan(1);

        Http.Exchange answer = behind(aMemberBeingAdded(), () -> moveTo(oneSeat));

        noFreeSeat(answer);
        assertThat(planOfSalon()).isEqualTo(UUID.fromString(roomy));
        assertThat(seatsUsed()).isEqualTo(2);
    }

    @Test
    void aMoveWaitingBehindAnAccountBeingCreatedCountsThatAccount() throws Exception {
        String roomy = billing.plan(5);
        salonOn(roomy);
        String oneSeat = billing.plan(1);

        Http.Exchange answer = behind(anAccountBeingCreated(), () -> moveTo(oneSeat));

        noFreeSeat(answer);
        assertThat(planOfSalon()).isEqualTo(UUID.fromString(roomy));
        assertThat(seatsUsed()).isEqualTo(2);
    }

    @Test
    void aMoveWithRoomForWhatItWaitedBehindStillGoesThrough() throws Exception {
        salonOn(billing.plan(5));
        String twoSeats = billing.plan(2);

        Http.Exchange answer = behind(aMemberBeingAdded(), () -> moveTo(twoSeats));

        BillingTestSupport.status(answer, 200);
        assertThat(planOfSalon()).isEqualTo(UUID.fromString(twoSeats));
        assertThat(seatsUsed()).isEqualTo(2);
    }
}
