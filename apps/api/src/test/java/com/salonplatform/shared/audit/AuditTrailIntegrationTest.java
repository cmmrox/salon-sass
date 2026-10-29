package com.salonplatform.shared.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.testing.IntegrationTest;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import com.salonplatform.testing.TestSalons;
import com.salonplatform.testing.TestSalons.TestSalon;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** NFR-10: how the audit trail is written, through the application, for two salons. */
class AuditTrailIntegrationTest extends IntegrationTest {

    @Autowired
    private AuditTrail audit;

    @Autowired
    private PlatformTransactionManager transactions;

    private final JdbcTemplate owner = TestDatabase.of(TestPostgres.shared()).asOwner();

    private TestSalons salons;

    private TestSalon alpha;

    private TestSalon beta;

    @BeforeEach
    void salons() {
        salons = new TestSalons(owner);
        alpha = salons.create("Audit Alpha", "#111111");
        beta = salons.create("Audit Beta", "#222222");
    }

    @Test
    void anEntryRecordsTheSalonTheActorTheActionTheSubjectAndTheValuesWithoutSecrets() throws Exception {
        UUID id = as(
                alpha,
                () -> write(new AuditAction(
                        Actor.system("test"),
                        "account.changed",
                        "account",
                        "acc-1",
                        "asked by the owner",
                        Map.of("email", "a@example.com", "password", "old-secret"),
                        Map.of("email", "b@example.com", "password", "new-secret", "nested", Map.of("otp", "4321")))));

        Map<String, Object> row = owner.queryForMap(
                "SELECT tenant_id, actor_kind, actor_id, action, subject_kind, "
                        + "subject_id, reason, before_values::text AS before, after_values::text AS after, created_at, updated_at "
                        + "FROM audit_entry WHERE id = ?",
                id);
        assertThat(row)
                .containsEntry("tenant_id", alpha.id())
                .containsEntry("actor_kind", "system")
                .containsEntry("actor_id", "test")
                .containsEntry("action", "account.changed")
                .containsEntry("subject_kind", "account")
                .containsEntry("subject_id", "acc-1")
                .containsEntry("reason", "asked by the owner");
        assertThat(row.get("created_at")).isNotNull().isEqualTo(row.get("updated_at"));
        assertThat((String) row.get("before"))
                .contains("a@example.com")
                .doesNotContain("old-secret")
                .contains("[redacted]");
        assertThat((String) row.get("after"))
                .contains("b@example.com")
                .doesNotContain("new-secret")
                .doesNotContain("4321");
    }

    @Test
    void anEntryIsRefusedOutsideATransactionAndInAReadOnlyOne() throws Exception {
        AuditAction action = new AuditAction(Actor.system("test"), "x.y", "thing", "1", null, null, null);

        assertThatIllegalStateException().isThrownBy(() -> as(alpha, () -> audit.record(action)));
        TransactionTemplate readOnly = new TransactionTemplate(transactions);
        readOnly.setReadOnly(true);
        assertThatIllegalStateException()
                .isThrownBy(() -> as(alpha, () -> readOnly.execute(status -> audit.record(action))));
    }

    @Test
    void anEntryIsRefusedWithNoSalon() {
        AuditAction action = new AuditAction(Actor.system("test"), "x.y", "thing", "1", null, null, null);

        assertThatIllegalStateException()
                .isThrownBy(() -> new TransactionTemplate(transactions).execute(status -> audit.record(action)));
    }

    @Test
    void anEntryExistsOnlyWhenTheChangeItRecordsWasCommitted() throws Exception {
        String subject = UUID.randomUUID().toString();
        AuditAction action = new AuditAction(Actor.system("test"), "x.rolled-back", "thing", subject, null, null, null);

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> as(
                        alpha,
                        () -> new TransactionTemplate(transactions).execute(status -> {
                            audit.record(action);
                            throw new IllegalStateException("the change failed");
                        })));

        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM audit_entry WHERE subject_id = ?", Integer.class, subject))
                .isZero();
    }

    @Test
    void aSalonReadsOnlyItsOwnEntries() throws Exception {
        String subject = UUID.randomUUID().toString();
        as(alpha, () -> write(new AuditAction(Actor.system("test"), "x.alpha", "shared", subject, null, null, null)));
        as(beta, () -> write(new AuditAction(Actor.system("test"), "x.beta", "shared", subject, null, null, null)));

        List<AuditRecord> alphas = as(alpha, () -> readOnly(() -> audit.about("shared", subject)));
        List<AuditRecord> betas = as(beta, () -> readOnly(() -> audit.about("shared", subject)));
        List<AuditRecord> nobodys = readOnly(() -> audit.about("shared", subject));

        assertThat(alphas).extracting(AuditRecord::action).containsExactly("x.alpha");
        assertThat(alphas).extracting(AuditRecord::tenantId).containsOnly(alpha.id());
        assertThat(betas).extracting(AuditRecord::action).containsExactly("x.beta");
        assertThat(nobodys).isEmpty();
    }

    private UUID write(AuditAction action) {
        return new TransactionTemplate(transactions).execute(status -> audit.record(action));
    }

    private <T> T readOnly(java.util.function.Supplier<T> work) {
        TransactionTemplate template = new TransactionTemplate(transactions);
        template.setReadOnly(true);
        return template.execute(status -> work.get());
    }

    private <T> T as(TestSalon salon, Callable<T> work) throws Exception {
        return TenantContext.callAs(salons.asCurrent(salon), work);
    }
}
