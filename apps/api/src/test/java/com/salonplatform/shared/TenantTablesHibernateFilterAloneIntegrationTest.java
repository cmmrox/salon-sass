package com.salonplatform.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.salonplatform.shared.audit.Actor;
import com.salonplatform.shared.audit.AuditAction;
import com.salonplatform.shared.audit.AuditRecord;
import com.salonplatform.shared.audit.AuditTrail;
import com.salonplatform.shared.outbox.Outbox;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.testing.StartedApplication;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import com.salonplatform.testing.TestSalons;
import com.salonplatform.testing.TestSalons.TestSalon;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The first layer on its own for the two new tenant tables: Hibernate's tenant filter
 * ({@code @TenantId}) with row-level security out of the way. In this database the policies of
 * {@code audit_entry} and {@code outbox_message} are disabled, so that Hibernate and nothing else
 * stands between one salon and another; the first test proves the database no longer holds anything
 * back. {@code TenantTablesIsolationIntegrationTest} is the other layer on its own.
 */
class TenantTablesHibernateFilterAloneIntegrationTest {

    private static final String SUBJECT_KIND = "filter-alone";

    private static TestDatabase database;

    private static StartedApplication application;

    private static TestSalons salons;

    private static TestSalon alpha;

    private static TestSalon beta;

    private static AuditTrail audit;

    private static Outbox outbox;

    private static EntityManager entityManager;

    private static TransactionTemplate transaction;

    private static UUID alphasMessage;

    private static UUID betasMessage;

    private static String subject;

    @BeforeAll
    static void applicationOnADatabaseWithoutRowLevelSecurity() throws Exception {
        database = TestDatabase.createEmpty(TestPostgres.shared());
        application = database.start();
        JdbcTemplate owner = database.asOwner();
        for (String table : new String[] {"audit_entry", "outbox_message"}) {
            owner.execute("ALTER TABLE " + table + " NO FORCE ROW LEVEL SECURITY");
            owner.execute("ALTER TABLE " + table + " DISABLE ROW LEVEL SECURITY");
        }
        salons = new TestSalons(owner);
        alpha = salons.create("Filter Alpha", "#111111");
        beta = salons.create("Filter Beta", "#222222");

        audit = application.bean(AuditTrail.class);
        outbox = application.bean(Outbox.class);
        entityManager =
                SharedEntityManagerCreator.createSharedEntityManager(application.bean(EntityManagerFactory.class));
        transaction = new TransactionTemplate(application.bean(PlatformTransactionManager.class));

        subject = UUID.randomUUID().toString();
        alphasMessage = as(
                alpha,
                () -> inTransaction(() -> {
                    audit.record(
                            new AuditAction(Actor.system("test"), "x.alpha", SUBJECT_KIND, subject, null, null, null));
                    return outbox.enqueue("filter-alone", Map.of("of", "alpha"));
                }));
        betasMessage = as(
                beta,
                () -> inTransaction(() -> {
                    audit.record(
                            new AuditAction(Actor.system("test"), "x.beta", SUBJECT_KIND, subject, null, null, null));
                    return outbox.enqueue("filter-alone", Map.of("of", "beta"));
                }));
    }

    @AfterAll
    static void stop() {
        application.close();
    }

    @Test
    void rowLevelSecurityIsReallyOutOfTheWayHere() {
        // The application's own role, outside any transaction and with no salon set: every row.
        for (String table : new String[] {"audit_entry", "outbox_message"}) {
            assertThat(application.jdbc().queryForList("SELECT DISTINCT tenant_id FROM " + table, UUID.class))
                    .as(table)
                    .containsExactlyInAnyOrder(alpha.id(), beta.id());
        }
    }

    @Test
    void anInsertIsStampedWithTheCurrentSalon() {
        JdbcTemplate owner = database.asOwner();

        assertThat(owner.queryForObject("SELECT tenant_id FROM outbox_message WHERE id = ?", UUID.class, alphasMessage))
                .isEqualTo(alpha.id());
        assertThat(owner.queryForObject("SELECT tenant_id FROM outbox_message WHERE id = ?", UUID.class, betasMessage))
                .isEqualTo(beta.id());
        assertThat(owner.queryForList(
                        "SELECT tenant_id FROM audit_entry WHERE subject_id = ? AND action = 'x.alpha'",
                        UUID.class,
                        subject))
                .containsExactly(alpha.id());
        assertThat(owner.queryForList(
                        "SELECT tenant_id FROM audit_entry WHERE subject_id = ? AND action = 'x.beta'",
                        UUID.class,
                        subject))
                .containsExactly(beta.id());
    }

    @Test
    void alphaReadsOnlyAlphasRowsAndBetasAreNotFound() throws Exception {
        as(
                alpha,
                () -> inTransaction(() -> {
                    assertThat(outbox.find(alphasMessage)).isPresent();
                    assertThat(outbox.find(betasMessage))
                            .as("beta's message, by its id")
                            .isEmpty();
                    assertThat(audit.about(SUBJECT_KIND, subject))
                            .extracting(AuditRecord::action)
                            .containsExactly("x.alpha");
                    assertThat(audit.about(SUBJECT_KIND, subject))
                            .extracting(AuditRecord::tenantId)
                            .containsOnly(alpha.id());
                    assertThat(entityManager
                                    .createQuery("select count(m) from OutboxMessage m", Long.class)
                                    .getSingleResult())
                            .isEqualTo(1);
                    assertThat(entityManager
                                    .createQuery("select count(e) from AuditEntry e", Long.class)
                                    .getSingleResult())
                            .isEqualTo(1);
                    return null;
                }));
    }

    @Test
    void alphaCannotChangeOrRemoveBetasMessage() throws Exception {
        as(
                alpha,
                () -> inTransaction(() -> {
                    assertThat(entityManager
                                    .createQuery("update OutboxMessage m set m.lastError = 'taken' where m.id = :id")
                                    .setParameter("id", betasMessage)
                                    .executeUpdate())
                            .as("rows updated")
                            .isZero();
                    assertThat(entityManager
                                    .createQuery("delete from OutboxMessage m where m.id = :id")
                                    .setParameter("id", betasMessage)
                                    .executeUpdate())
                            .as("rows deleted")
                            .isZero();
                    return null;
                }));

        assertThat(database.asOwner()
                        .queryForMap("SELECT status, last_error FROM outbox_message WHERE id = ?", betasMessage))
                .containsEntry("status", "pending")
                .containsEntry("last_error", null);
    }

    @Test
    void withNoSalonNothingIsFoundAndNothingCanBeWritten() {
        assertThat(inTransaction(() -> outbox.find(alphasMessage))).isEmpty();
        List<AuditRecord> entries = inTransaction(() -> audit.about(SUBJECT_KIND, subject));
        assertThat(entries).isEmpty();
        assertThat(inTransaction(() -> entityManager
                        .createQuery("select count(m) from OutboxMessage m", Long.class)
                        .getSingleResult()))
                .isZero();

        assertThatIllegalStateException()
                .isThrownBy(() -> inTransaction(() -> outbox.enqueue("filter-alone", Map.of())));
        assertThatIllegalStateException()
                .isThrownBy(() -> inTransaction(() -> audit.record(
                        new AuditAction(Actor.system("test"), "x.nobody", SUBJECT_KIND, subject, null, null, null))));
        assertThat(database.asOwner()
                        .queryForObject("SELECT count(*) FROM audit_entry WHERE action = 'x.nobody'", Integer.class))
                .isZero();
    }

    private static <T> T as(TestSalon salon, Callable<T> work) throws Exception {
        return TenantContext.callAs(salons.asCurrent(salon), work);
    }

    private static <T> T inTransaction(Callable<T> work) {
        return transaction.execute(status -> {
            try {
                return work.call();
            } catch (RuntimeException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
    }
}
