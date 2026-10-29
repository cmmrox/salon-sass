package com.salonplatform.identity;

import static org.assertj.core.api.Assertions.*;

import com.salonplatform.identity.domain.*;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.testing.*;
import com.salonplatform.testing.TestSalons.TestSalon;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.Savepoint;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves each isolation layer independently for every new identity entity and composite
 * relationship.
 */
class IdentityIsolationIntegrationTest {
    private static final List<Class<?>> ENTITIES = List.of(
            StaffAccount.class,
            AccessSession.class,
            RefreshHistory.class,
            MfaChallenge.class,
            MfaStep.class,
            RateBucket.class,
            IdentityGuard.class);
    private static final List<String> TABLES = List.of(
            "identity_staff",
            "identity_session",
            "identity_refresh_history",
            "identity_mfa_challenge",
            "identity_mfa_step",
            "identity_rate_bucket",
            "identity_guard");

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void allIdentityTablesAreIsolatedWithEachLayerAlone(boolean hibernateOnly) throws Exception {
        TestDatabase db = TestDatabase.createEmpty(TestPostgres.shared());
        try (StartedApplication app = db.start()) {
            var owner = db.asOwner();
            TestSalons salons = new TestSalons(owner);
            TestSalon alpha = salons.create("Identity layer A", "#111111"),
                    beta = salons.create("Identity layer B", "#222222");
            seed(db, alpha);
            seed(db, beta);
            for (String table : TABLES) {
                var flags = owner.queryForMap(
                        "SELECT relrowsecurity,relforcerowsecurity FROM pg_class WHERE" + " relname=?", table);
                assertThat(flags).containsEntry("relrowsecurity", true).containsEntry("relforcerowsecurity", true);
            }
            if (hibernateOnly) {
                for (String table : TABLES) {
                    owner.execute("ALTER TABLE " + table + " NO FORCE ROW LEVEL SECURITY");
                    owner.execute("ALTER TABLE " + table + " DISABLE ROW LEVEL SECURITY");
                }
                for (String table : TABLES)
                    assertThat(app.jdbc().queryForObject("SELECT count(*) FROM " + table, Long.class))
                            .isEqualTo(2);
                EntityManager em =
                        SharedEntityManagerCreator.createSharedEntityManager(app.bean(EntityManagerFactory.class));
                TransactionTemplate tx = new TransactionTemplate(app.bean(PlatformTransactionManager.class));
                TenantContext.callAs(
                        salons.asCurrent(alpha),
                        () -> tx.execute(status -> {
                            for (Class<?> entity : ENTITIES) {
                                String name = entity.getSimpleName();
                                assertThat(em.createQuery("select e from " + name + " e", entity)
                                                .getResultList())
                                        .hasSize(1);
                                assertThat(em.createQuery("select e from " + name + " e where" + " e.id=:id", entity)
                                                .setParameter("id", beta.id())
                                                .getResultList())
                                        .isEmpty();
                                if (entity != MfaStep.class && entity != RefreshHistory.class) {
                                    assertThat(em.createQuery("update "
                                                            + name
                                                            + " e set"
                                                            + " e.updatedAt=:at"
                                                            + " where"
                                                            + " e.id=:id")
                                                    .setParameter("at", Instant.now())
                                                    .setParameter("id", beta.id())
                                                    .executeUpdate())
                                            .isZero();
                                }

                                if (entity != IdentityGuard.class) {
                                    assertThat(em.createQuery("delete from " + name + " e where" + " e.id=:id")
                                                    .setParameter("id", beta.id())
                                                    .executeUpdate())
                                            .isZero();
                                }
                            }
                            return null;
                        }));
                tx.execute(status -> {
                    for (Class<?> entity : ENTITIES)
                        assertThat(em.createQuery("select e from " + entity.getSimpleName() + " e", entity)
                                        .getResultList())
                                .isEmpty();
                    return null;
                });
                assertThatThrownBy(() -> TenantContext.callAs(
                                salons.asCurrent(alpha),
                                () -> tx.execute(status -> {
                                    AccessSession s = new AccessSession();
                                    s.id = UUID.randomUUID();
                                    s.accountId = beta.id();
                                    s.kind = "staff";
                                    s.refreshHash = "unusable";
                                    s.deviceLabel = "test";
                                    s.expiresAt = Instant.now().plusSeconds(60);
                                    s.createdAt = Instant.now();
                                    s.updatedAt = s.createdAt;
                                    em.persist(s);
                                    em.flush();
                                    return null;
                                })))
                        .hasMessageContaining("session_staff");
            } else {
                try (Connection c = db.connectAsApplication();
                        Statement sql = c.createStatement()) {
                    c.setAutoCommit(false);
                    for (String table : TABLES) {
                        try (var rows = sql.executeQuery("SELECT count(*) FROM " + table)) {
                            rows.next();
                            assertThat(rows.getLong(1)).isZero();
                        }
                    }
                    sql.execute("SELECT set_config('app.tenant_id','" + alpha.id() + "',true)");
                    for (String table : TABLES) {
                        try (var rows = sql.executeQuery("SELECT count(*) FROM " + table)) {
                            rows.next();
                            assertThat(rows.getLong(1)).isEqualTo(1);
                        }
                        if (!table.equals("identity_mfa_step") && !table.equals("identity_refresh_history")) {
                            assertThat(sql.executeUpdate("UPDATE "
                                            + table
                                            + " SET updated_at=updated_at WHERE"
                                            + " id='"
                                            + beta.id()
                                            + "'"))
                                    .isZero();
                        }

                        if (!table.equals("identity_guard")) {
                            assertThat(sql.executeUpdate("DELETE FROM " + table + " WHERE id='" + beta.id() + "'"))
                                    .isZero();
                        }

                        Savepoint point = c.setSavepoint();
                        assertThatThrownBy(() -> sql.executeUpdate("INSERT INTO "
                                        + table
                                        + " SELECT '"
                                        + UUID.randomUUID()
                                        + "'::uuid,'"
                                        + beta.id()
                                        + "'::uuid,"
                                        + columnsAfterTenant(table)
                                        + " FROM "
                                        + table
                                        + " WHERE id='"
                                        + alpha.id()
                                        + "'"))
                                .isInstanceOf(java.sql.SQLException.class)
                                .hasMessageContaining("row-level security");
                        c.rollback(point);
                    }
                    Savepoint point = c.setSavepoint();
                    assertThatThrownBy(() -> sql.executeUpdate("INSERT INTO identity_session SELECT '"
                                    + UUID.randomUUID()
                                    + "'::uuid,tenant_id,created_at,updated_at,'"
                                    + beta.id()
                                    + "'::uuid,kind,refresh_hash,device_label,expires_at,revoked_at,generation,mfa_verified,customer_account_id"
                                    + " FROM identity_session WHERE id='"
                                    + alpha.id()
                                    + "'"))
                            .hasMessageContaining("session_staff");
                    c.rollback(point);
                    c.commit();
                    for (String table : TABLES) {
                        try (var rows = sql.executeQuery("SELECT count(*) FROM " + table)) {
                            rows.next();
                            assertThat(rows.getLong(1)).isZero();
                        }
                    }
                }
            }
        }
    }

    private static String columnsAfterTenant(String table) {
        return switch (table) {
            // This attempts a cross-tenant WITH CHECK failure by replacing tenant through a SQL
            // expression below.
            case "identity_staff" ->
                "created_at,updated_at,email,mobile,password_hash,role,active,professional_grant,mfa_secret,version";
            case "identity_session" ->
                "created_at,updated_at,account_id,kind,refresh_hash,device_label,expires_at,revoked_at,generation,mfa_verified,customer_account_id";
            case "identity_refresh_history" -> "created_at,updated_at,session_id,credential_hash";
            case "identity_mfa_challenge" ->
                "created_at,updated_at,account_id,session_id,purpose,credential_hash,pending_secret,device_label,expires_at,attempts,consumed_at";
            case "identity_mfa_step" -> "created_at,updated_at,account_id,step";
            case "identity_rate_bucket" -> "created_at,updated_at,dimension,key_hash,window_start,attempts";
            default -> "created_at,updated_at";
        };
    }

    private static void seed(TestDatabase db, TestSalon salon) {
        var j = db.asOwner();
        UUID id = salon.id();
        java.sql.Timestamp at = java.sql.Timestamp.from(Instant.now());
        j.update(
                "INSERT INTO"
                        + " identity_staff(id,tenant_id,created_at,updated_at,email,password_hash,role,active,professional_grant,version)"
                        + " VALUES(?,?,?,?,?,'unusable','MANAGER',true,false,0)",
                id,
                id,
                at,
                at,
                UUID.randomUUID() + "@example.test");
        j.update(
                "INSERT INTO"
                        + " identity_session(id,tenant_id,created_at,updated_at,account_id,kind,refresh_hash,device_label,expires_at,generation,mfa_verified)"
                        + " VALUES(?,?,?,?,?,'staff','unusable','test',?,0,false)",
                id,
                id,
                at,
                at,
                id,
                java.sql.Timestamp.from(at.toInstant().plusSeconds(60)));
        j.update(
                "INSERT INTO"
                        + " identity_refresh_history(id,tenant_id,created_at,updated_at,session_id,credential_hash)"
                        + " VALUES(?,?,?,?,?,'unusable')",
                id,
                id,
                at,
                at,
                id);
        j.update(
                "INSERT INTO"
                        + " identity_mfa_challenge(id,tenant_id,created_at,updated_at,account_id,session_id,purpose,credential_hash,device_label,expires_at,attempts)"
                        + " VALUES(?,?,?,?,?,?,'verify_mfa','unusable','test',?,0)",
                id,
                id,
                at,
                at,
                id,
                id,
                java.sql.Timestamp.from(at.toInstant().plusSeconds(60)));
        j.update(
                "INSERT INTO identity_mfa_step(id,tenant_id,created_at,updated_at,account_id,step)"
                        + " VALUES(?,?,?,?,?,0)",
                id,
                id,
                at,
                at,
                id);
        j.update(
                "INSERT INTO"
                        + " identity_rate_bucket(id,tenant_id,created_at,updated_at,dimension,key_hash,window_start,attempts)"
                        + " VALUES(?,?,?,?,'test','unusable',?,0)",
                id,
                id,
                at,
                at,
                at);
        j.update("INSERT INTO identity_guard(id,tenant_id,created_at,updated_at) VALUES(?,?,?,?)", id, id, at, at);
    }
}
