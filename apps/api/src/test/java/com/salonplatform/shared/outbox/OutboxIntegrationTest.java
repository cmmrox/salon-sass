package com.salonplatform.shared.outbox;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.testing.IntegrationTest;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import com.salonplatform.testing.TestSalons;
import com.salonplatform.testing.TestSalons.TestSalon;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/** Writing to the outbox, through the application, for two salons. The dispatcher is off here. */
class OutboxIntegrationTest extends IntegrationTest {

    @Autowired
    private Outbox outbox;

    @Autowired
    private PlatformTransactionManager transactions;

    private final JdbcTemplate owner = TestDatabase.of(TestPostgres.shared()).asOwner();

    private TestSalons salons;

    private TestSalon alpha;

    private TestSalon beta;

    @BeforeEach
    void salons() {
        salons = new TestSalons(owner);
        alpha = salons.create("Outbox Alpha", "#111111");
        beta = salons.create("Outbox Beta", "#222222");
    }

    @Test
    void aMessageIsWrittenPendingDueAtOnceForTheCurrentSalon() throws Exception {
        UUID id = as(alpha, () -> write(() -> outbox.enqueue("probe", Map.of("to", "x"))));

        Map<String, Object> row = owner.queryForMap("SELECT tenant_id, channel, payload::text AS payload, status, "
                + "attempts, next_attempt_at = created_at AS due_at_once FROM outbox_message WHERE id = ?", id);
        assertThat(row).containsEntry("tenant_id", alpha.id()).containsEntry("channel", "probe")
                .containsEntry("status", "pending").containsEntry("attempts", 0).containsEntry("due_at_once", true);
        assertThat((String) row.get("payload")).contains("\"to\"");
    }

    @Test
    void aMessageExistsOnlyWhenTheChangeThatCausedItWasCommitted() {
        int before = owner.queryForObject("SELECT count(*) FROM outbox_message WHERE tenant_id = ?", Integer.class, alpha.id());

        assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> as(alpha, () -> write(() -> {
            outbox.enqueue("probe", Map.of());
            throw new IllegalStateException("the change failed");
        })));

        assertThat(owner.queryForObject("SELECT count(*) FROM outbox_message WHERE tenant_id = ?", Integer.class, alpha.id()))
                .isEqualTo(before);
    }

    @Test
    void aMessageIsRefusedOutsideATransactionAndWithNoSalon() {
        assertThatIllegalStateException().isThrownBy(() -> as(alpha, () -> outbox.enqueue("probe", Map.of())));
        assertThatIllegalStateException().isThrownBy(() -> write(() -> outbox.enqueue("probe", Map.of())));
    }

    @Test
    void aSalonFindsOnlyItsOwnMessages() throws Exception {
        UUID alphas = as(alpha, () -> write(() -> outbox.enqueue("probe", Map.of())));

        assertThat(as(alpha, () -> write(() -> outbox.find(alphas)))).isPresent();
        assertThat(as(beta, () -> write(() -> outbox.find(alphas)))).isEmpty();
        assertThat(write(() -> outbox.find(alphas))).isEmpty();
    }

    private <T> T write(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(transactions).execute(status -> work.get());
    }

    private <T> T as(TestSalon salon, Callable<T> work) throws Exception {
        return TenantContext.callAs(salons.asCurrent(salon), work);
    }
}
