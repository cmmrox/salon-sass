package com.salonplatform.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.salonplatform.branding.SalonBranches;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.testing.IntegrationTest;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import com.salonplatform.testing.TestSalons;
import com.salonplatform.testing.TestSalons.TestSalon;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Decision 119: a provided read interface reads in a read-only transaction of its own, never its caller's, and
 * refuses to be called inside a writing transaction, so that a caller reads what it needs before it opens its own.
 */
class ReadOnlyWorkIntegrationTest extends IntegrationTest {

    @Autowired
    ReadOnlyWork reading;

    @Autowired
    SalonBranches branches;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager manager;

    private TestSalons salons;

    private TestSalon salon;

    @BeforeEach
    void aSalon() {
        salons = new TestSalons(TestDatabase.of(TestPostgres.shared()).asOwner());
        salon = salons.create("Read Only Work", "#123456");
    }

    @Test
    void aProvidedReadInterfaceCalledInsideAWritingTransactionIsRefused() {
        assertThatIllegalStateException()
                .isThrownBy(() -> TenantContext.callAs(
                        salons.asCurrent(salon),
                        () -> new TransactionTemplate(manager).execute(status -> branches.defaultBranch())))
                .withMessageContaining("inside a writing transaction");
    }

    @Test
    void outsideATransactionItReadsInAReadOnlyOneOfItsOwn() throws Exception {
        Boolean readOnly = TenantContext.callAs(
                salons.asCurrent(salon),
                () -> reading.read(() -> TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                        && TransactionSynchronizationManager.isActualTransactionActive()));
        assertThat(readOnly).isTrue();
        assertThat(TenantContext.callAs(salons.asCurrent(salon), () -> branches.defaultBranch()))
                .isNotNull();
    }

    @Test
    void insideAReadOnlyTransactionItStillReadsInATransactionOfItsOwn() throws Exception {
        TransactionTemplate readOnly = new TransactionTemplate(manager);
        readOnly.setReadOnly(true);
        Integer[] backends = TenantContext.callAs(
                salons.asCurrent(salon),
                () -> readOnly.execute(status -> {
                    Integer callers = backend();
                    Integer its = reading.read(this::backend);
                    return new Integer[] {callers, its};
                }));
        assertThat(backends[1]).as("another connection, so another transaction").isNotEqualTo(backends[0]);
    }

    private Integer backend() {
        return jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
    }
}
