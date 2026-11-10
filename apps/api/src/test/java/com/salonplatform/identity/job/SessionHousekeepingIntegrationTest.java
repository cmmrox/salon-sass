package com.salonplatform.identity.job;

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
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Platform review M6: sessions and their refresh history were never removed, and the refresh lookups had no index,
 * so both tables grew for the life of the salon and every refresh scanned all of its sessions. The security cleanup
 * now removes a session, with its history, a week after it expired or was revoked, and the lookups by refresh
 * credential and by spent credential are indexed.
 */
// JSON bodies are read as untyped maps; each cast states the shape the assertion beside it checks.
@SuppressWarnings("unchecked")
class SessionHousekeepingIntegrationTest extends IntegrationTest {

    @Autowired
    IdentityCrypto crypto;

    @Autowired
    Clock clock;

    @Autowired
    JsonMapper json;

    @Autowired
    PlatformTransactionManager manager;

    @Autowired
    IdentityCleanupJob job;

    @PersistenceContext
    EntityManager em;

    private final JdbcTemplate owner = TestDatabase.of(TestPostgres.shared()).asOwner();
    private TestSalons salons;
    private TestSalon alpha;
    private TestSalon beta;
    private String password;

    @BeforeEach
    void twoSalonsWithAMemberEach() throws Exception {
        salons = new TestSalons(owner);
        alpha = salons.create("Housekeeping Alpha", "#123456");
        beta = salons.create("Housekeeping Beta", "#654321");
        password = UUID.randomUUID() + " synthetic";
        for (TestSalon salon : List.of(alpha, beta)) {
            TenantContext.callAs(
                    salons.asCurrent(salon),
                    () -> new TransactionTemplate(manager).execute(status -> {
                        StaffAccount account = StaffAccount.create(
                                "manager@example.test",
                                "+94770000401",
                                crypto.encodePassword(password),
                                StaffRole.MANAGER,
                                false,
                                clock.instant());
                        em.persist(account);
                        return account;
                    }));
        }
    }

    @Test
    void sessionsEndedOverAWeekAgoGoWithTheirHistoryAndEveryOtherSessionStays() throws Exception {
        UUID expiredLongAgo = renewedSession(alpha);
        UUID revokedLongAgo = renewedSession(alpha);
        UUID expiredRecently = renewedSession(alpha);
        UUID live = renewedSession(alpha);
        UUID namedByAChallenge = sessionNamedByAChallenge(alpha);
        UUID othersEndedLongAgo = renewedSession(beta);
        end(expiredLongAgo, "expires_at = now() - interval '8 days'");
        end(revokedLongAgo, "revoked_at = now() - interval '8 days'");
        end(expiredRecently, "expires_at = now() - interval '6 days'");
        end(namedByAChallenge, "revoked_at = now() - interval '8 days'");
        end(othersEndedLongAgo, "expires_at = now() - interval '8 days'");
        assertThat(history(expiredLongAgo))
                .as("a renewal left a spent credential")
                .isEqualTo(1);

        TenantContext.callAs(
                salons.asCurrent(alpha),
                () -> new TransactionTemplate(manager).execute(status -> {
                    job.runFor(salons.asCurrent(alpha), clock.instant());
                    return null;
                }));

        assertThat(exists(expiredLongAgo)).as("expired over a week ago").isFalse();
        assertThat(history(expiredLongAgo)).as("its refresh history with it").isZero();
        assertThat(exists(revokedLongAgo)).as("revoked over a week ago").isFalse();
        assertThat(history(revokedLongAgo)).isZero();
        assertThat(exists(expiredRecently)).as("ended less than a week ago").isTrue();
        assertThat(history(expiredRecently)).isEqualTo(1);
        assertThat(exists(live)).as("live").isTrue();
        assertThat(exists(namedByAChallenge))
                .as("a second-step challenge still names it")
                .isTrue();
        assertThat(exists(othersEndedLongAgo))
                .as("another salon's, in another salon's run")
                .isTrue();
    }

    @Test
    void theRefreshLookupsAreIndexedForTheirSalon() {
        assertThat(indexColumns("identity_session", "identity_session_refresh")).isEqualTo("tenant_id,refresh_hash");
        assertThat(owner.queryForObject(
                        "SELECT indisunique FROM pg_index WHERE indexrelid = 'identity_session_refresh'::regclass",
                        Boolean.class))
                .isTrue();
        assertThat(indexColumns("identity_refresh_history", "identity_refresh_credential"))
                .isEqualTo("tenant_id,credential_hash");
    }

    /** A signed-in session of the salon's manager, renewed once so that it has a spent credential. */
    private UUID renewedSession(TestSalon salon) {
        Map<String, Object> tokens = signIn(salon);
        Http.Exchange renewed =
                call(salon, "POST", "/sessions/refresh", Map.of("refreshToken", tokens.get("refreshToken")), null);
        assertThat(renewed.status()).withFailMessage("%s", renewed.body()).isEqualTo(200);
        return UUID.fromString((String) tokens.get("sessionId"));
    }

    /** A session that has asked for a voluntary two-step enrolment, whose challenge names it. */
    private UUID sessionNamedByAChallenge(TestSalon salon) {
        Map<String, Object> tokens = signIn(salon);
        Http.Exchange challenge =
                call(salon, "POST", "/staff/mfa/challenges", null, (String) tokens.get("accessToken"));
        assertThat(challenge.status()).withFailMessage("%s", challenge.body()).isEqualTo(200);
        return UUID.fromString((String) tokens.get("sessionId"));
    }

    private Map<String, Object> signIn(TestSalon salon) {
        Http.Exchange signedIn = call(
                salon,
                "POST",
                "/staff/sign-in",
                Map.of("identifier", "manager@example.test", "password", password, "deviceLabel", "JUnit device"),
                null);
        assertThat(signedIn.status()).withFailMessage("%s", signedIn.body()).isEqualTo(200);
        return (Map<String, Object>) json.readValue(signedIn.body(), Map.class).get("credentials");
    }

    private void end(UUID session, String assignment) {
        owner.update("UPDATE identity_session SET " + assignment + " WHERE id = ?", session);
    }

    private boolean exists(UUID session) {
        return owner.queryForObject("SELECT count(*) FROM identity_session WHERE id = ?", Integer.class, session) == 1;
    }

    private int history(UUID session) {
        return owner.queryForObject(
                "SELECT count(*) FROM identity_refresh_history WHERE session_id = ?", Integer.class, session);
    }

    private String indexColumns(String table, String index) {
        return owner.queryForObject("""
                SELECT string_agg(a.attname, ',' ORDER BY k.ordinality)
                  FROM pg_index i
                  JOIN pg_class c ON c.oid = i.indexrelid
                  CROSS JOIN LATERAL unnest(i.indkey) WITH ORDINALITY AS k(attnum, ordinality)
                  JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum
                 WHERE c.relname = ? AND i.indrelid = ?::regclass
                """, String.class, index, table);
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
