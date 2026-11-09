package com.salonplatform.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.salonplatform.shared.tenancy.CurrentTenant;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.testing.IntegrationTest;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The guard lock (F07-S01): taken only inside a writable transaction, where it lasts until that transaction ends, and
 * keyed by purpose, salon and subject. That two transactions naming one key take turns is proved where it matters, by
 * the guard races of {@code BookingConcurrencyIntegrationTest}.
 */
class TransactionLocksIntegrationTest extends IntegrationTest {

    private static final CurrentTenant SALON = new CurrentTenant(UUID.randomUUID(), "locks", ZoneId.of("Asia/Colombo"));

    @Autowired
    TransactionLocks locks;

    @Autowired
    PlatformTransactionManager transactions;

    @Test
    void outsideATransactionOrInAReadOnlyOneTheLockIsRefused() throws Exception {
        TenantContext.callAs(SALON, () -> {
            assertThatIllegalStateException().isThrownBy(() -> locks.hold("staff.member-bookings", UUID.randomUUID()));
            TransactionTemplate reading = new TransactionTemplate(transactions);
            reading.setReadOnly(true);
            reading.executeWithoutResult(status -> assertThatIllegalStateException()
                    .isThrownBy(() -> locks.hold("staff.member-bookings", UUID.randomUUID())));
            return null;
        });
    }

    @Test
    void insideAWritableTransactionTheLockIsTaken() throws Exception {
        boolean held = TenantContext.callAs(
                SALON,
                () -> new TransactionTemplate(transactions).execute(status -> {
                    locks.hold("catalogue.service-bookings", UUID.randomUUID());
                    return true;
                }));
        assertThat(held).isTrue();
    }

    @Test
    void aKeyNamesPurposeSalonAndSubjectAndAPurposeIsAPlainName() {
        UUID salon = UUID.randomUUID();
        UUID subject = UUID.randomUUID();
        assertThat(TransactionLocks.key("staff.member-bookings", salon, subject))
                .isEqualTo("staff.member-bookings/" + salon + "/" + subject)
                .isNotEqualTo(TransactionLocks.key("catalogue.service-bookings", salon, subject));
        assertThatIllegalArgumentException().isThrownBy(() -> TransactionLocks.key("Staff Member", salon, subject));
    }
}
