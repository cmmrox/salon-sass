package com.salonplatform.identity;

import static org.assertj.core.api.Assertions.*;

import com.salonplatform.identity.domain.*;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.testing.*;
import com.salonplatform.testing.TestSalons.TestSalon;
import jakarta.persistence.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Each new customer table is checked with Hibernate or RLS independently, including principal-kind
 * FKs.
 */
class CustomerIsolationIntegrationTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bothCustomerEntitiesRemainIsolatedWithEachLayerAlone(boolean hibernateOnly) throws Exception {
        TestDatabase db = TestDatabase.createEmpty(TestPostgres.shared());
        try (StartedApplication app = db.start()) {
            var owner = db.asOwner();
            TestSalons salons = new TestSalons(owner);
            TestSalon a = salons.create("Customer A", "#111111"), b = salons.create("Customer B", "#222222");
            seed(db, a);
            seed(db, b);
            List<String> tables = List.of("identity_customer", "identity_customer_challenge");
            for (String t : tables)
                assertThat(owner.queryForMap(
                                "SELECT relrowsecurity,relforcerowsecurity FROM pg_class" + " WHERE relname=?", t))
                        .containsEntry("relrowsecurity", true)
                        .containsEntry("relforcerowsecurity", true);
            if (hibernateOnly) {
                for (String t : tables) {
                    owner.execute("ALTER TABLE " + t + " NO FORCE ROW LEVEL SECURITY");
                    owner.execute("ALTER TABLE " + t + " DISABLE ROW LEVEL SECURITY");
                    assertThat(app.jdbc().queryForObject("SELECT count(*) FROM " + t, Long.class))
                            .isEqualTo(2);
                }
                EntityManager em =
                        SharedEntityManagerCreator.createSharedEntityManager(app.bean(EntityManagerFactory.class));
                TransactionTemplate tx = new TransactionTemplate(app.bean(PlatformTransactionManager.class));
                TenantContext.callAs(
                        salons.asCurrent(a),
                        () -> tx.execute(status -> {
                            for (Class<?> c : List.of(CustomerAccount.class, CustomerChallenge.class)) {
                                String name = c.getSimpleName();
                                assertThat(em.createQuery("select e from " + name + " e", c)
                                                .getResultList())
                                        .hasSize(1);
                                assertThat(em.createQuery("select e from " + name + " e where" + " e.id=:id", c)
                                                .setParameter("id", b.id())
                                                .getResultList())
                                        .isEmpty();
                                assertThat(em.createQuery("update "
                                                        + name
                                                        + " e set"
                                                        + " e.updatedAt=:now"
                                                        + " where"
                                                        + " e.id=:id")
                                                .setParameter("now", Instant.now())
                                                .setParameter("id", b.id())
                                                .executeUpdate())
                                        .isZero();
                                assertThat(em.createQuery("delete from " + name + " e where" + " e.id=:id")
                                                .setParameter("id", b.id())
                                                .executeUpdate())
                                        .isZero();
                            }
                            return null;
                        }));
                tx.execute(status -> {
                    for (Class<?> c : List.of(CustomerAccount.class, CustomerChallenge.class))
                        assertThat(em.createQuery("select e from " + c.getSimpleName() + " e", c)
                                        .getResultList())
                                .isEmpty();
                    return null;
                });
                assertThatThrownBy(() -> TenantContext.callAs(
                                salons.asCurrent(a),
                                () -> tx.execute(status -> {
                                    AccessSession s = AccessSession.forCustomer(
                                            b.id(), "test", Instant.now(), Duration.ofSeconds(60));
                                    s.rotate("unusable", Instant.now());
                                    em.persist(s);
                                    em.flush();
                                    return null;
                                })))
                        .hasMessageContaining("session_customer");
            } else
                try (Connection connection = db.connectAsApplication();
                        Statement sql = connection.createStatement()) {
                    connection.setAutoCommit(false);
                    for (String table : tables) count(sql, table, 0);
                    sql.execute("SELECT set_config('app.tenant_id','" + a.id() + "',true)");
                    for (String table : tables) {
                        count(sql, table, 1);
                        assertThat(sql.executeUpdate(
                                        "UPDATE " + table + " SET updated_at=updated_at WHERE id='" + b.id() + "'"))
                                .isZero();
                        assertThat(sql.executeUpdate("DELETE FROM " + table + " WHERE id='" + b.id() + "'"))
                                .isZero();
                    }
                    Savepoint point = connection.setSavepoint();
                    assertThatThrownBy(() -> sql.execute("INSERT INTO identity_customer VALUES ('"
                                    + b.id()
                                    + "','"
                                    + UUID.randomUUID()
                                    + "','+94770009999','',true,now(),now(),0)"))
                            .hasMessageContaining("row-level security");
                    connection.rollback(point);
                    point = connection.setSavepoint();
                    assertThatThrownBy(() -> sql.execute("INSERT INTO identity_customer_challenge SELECT"
                                    + " '"
                                    + b.id()
                                    + "','"
                                    + UUID.randomUUID()
                                    + "',number_hash,encrypted_recipient,code_hash,device_hash,device_label,channel,message_id,expires_at,attempts,invalidated_at,consumed_at,created_at,updated_at"
                                    + " FROM identity_customer_challenge"))
                            .hasMessageContaining("row-level security");
                    connection.rollback(point);
                    point = connection.setSavepoint();
                    assertThatThrownBy(() -> sql.execute("INSERT INTO"
                                    + " identity_session(tenant_id,id,created_at,updated_at,customer_account_id,kind,refresh_hash,device_label,expires_at,generation,mfa_verified)"
                                    + " VALUES('"
                                    + a.id()
                                    + "','"
                                    + UUID.randomUUID()
                                    + "',now(),now(),'"
                                    + b.id()
                                    + "','customer','unusable','test',now()+interval"
                                    + " '1 minute',1,false)"))
                            .hasMessageContaining("session_customer");
                    connection.rollback(point);
                    point = connection.setSavepoint();
                    assertThatThrownBy(() -> sql.execute("INSERT INTO"
                                    + " identity_session(tenant_id,id,created_at,updated_at,customer_account_id,kind,refresh_hash,device_label,expires_at,generation,mfa_verified)"
                                    + " VALUES('"
                                    + a.id()
                                    + "','"
                                    + UUID.randomUUID()
                                    + "',now(),now(),'"
                                    + a.id()
                                    + "','staff','unusable','test',now()+interval"
                                    + " '1 minute',1,false)"))
                            .hasMessageContaining("session_kind_owner");
                    connection.rollback(point);
                    connection.commit();
                    for (String table : tables) count(sql, table, 0);
                }
        }
    }

    private static void count(Statement sql, String table, int expected) throws Exception {
        try (ResultSet rows = sql.executeQuery("SELECT count(*) FROM " + table)) {
            rows.next();
            assertThat(rows.getInt(1)).isEqualTo(expected);
        }
    }

    private static void seed(TestDatabase db, TestSalon salon) {
        Timestamp now = Timestamp.from(Instant.now());
        db.asOwner()
                .update(
                        "INSERT INTO"
                                + " identity_customer(tenant_id,id,mobile,name,active,created_at,updated_at,version)"
                                + " VALUES(?,?,?,'',true,?,?,0)",
                        salon.id(),
                        salon.id(),
                        "+94770000001",
                        now,
                        now);
        db.asOwner()
                .update(
                        "INSERT INTO"
                                + " identity_customer_challenge(tenant_id,id,number_hash,encrypted_recipient,code_hash,device_hash,device_label,channel,message_id,expires_at,attempts,created_at,updated_at)"
                                + " VALUES(?,?,'unusable','unusable','unusable','unusable','test','sms',?,?,0,?,?)",
                        salon.id(),
                        salon.id(),
                        UUID.randomUUID(),
                        now,
                        now,
                        now);
    }
}
