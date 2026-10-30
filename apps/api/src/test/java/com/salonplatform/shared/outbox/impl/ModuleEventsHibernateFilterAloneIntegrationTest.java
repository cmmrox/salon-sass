package com.salonplatform.shared.outbox.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.shared.outbox.Outbox;
import com.salonplatform.shared.outbox.impl.EventProbes.Reach;
import com.salonplatform.shared.outbox.impl.EventProbes.Reached;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.testing.StartedApplication;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import com.salonplatform.testing.TestSalons;
import com.salonplatform.testing.TestSalons.TestSalon;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The first layer on its own for a subscriber: Hibernate's tenant filter ({@code @TenantId}) with row-level
 * security out of the way. In this database the policies of {@code outbox_message} and
 * {@code shared_processed_event} are disabled, so that while a subscriber handles alpha's event, Hibernate and
 * nothing else stands between it and beta's message; the reaching subscriber's direct SQL, which Hibernate
 * never sees, shows that the database really holds nothing back here. {@code ModuleEventsIntegrationTest} is
 * the other layer on its own.
 */
class ModuleEventsHibernateFilterAloneIntegrationTest {

    private static TestDatabase database;

    private static StartedApplication application;

    @BeforeAll
    static void applicationOnADatabaseWithoutRowLevelSecurity() {
        database = TestDatabase.createEmpty(TestPostgres.shared());
        application = database.startWith(
                EventProbes.Subscribers.class, "--salon.outbox.enabled=true", "--salon.outbox.poll-interval=20ms");
        JdbcTemplate owner = database.asOwner();
        for (String table : new String[] {"outbox_message", "shared_processed_event"}) {
            owner.execute("ALTER TABLE " + table + " NO FORCE ROW LEVEL SECURITY");
            owner.execute("ALTER TABLE " + table + " DISABLE ROW LEVEL SECURITY");
        }
    }

    @AfterAll
    static void stop() {
        application.close();
    }

    @Test
    void aSubscriberHandlingOneSalonsEventReadsAndWritesOnlyThatSalonsRowsThroughHibernate() throws Exception {
        TestSalons salons = new TestSalons(database.asOwner());
        TestSalon alpha = salons.create("Filter Events Alpha", "#111111");
        TestSalon beta = salons.create("Filter Events Beta", "#222222");
        Outbox outbox = application.bean(Outbox.class);
        TransactionTemplate transaction = new TransactionTemplate(application.bean(PlatformTransactionManager.class));
        UUID betasMessage =
                as(salons, beta, () -> transaction.execute(status -> outbox.enqueue("unreachable", Map.of())));
        UUID mark = UUID.randomUUID();

        as(salons, alpha, () -> transaction.execute(status -> outbox.publish(new Reached(mark, betasMessage))));

        Reach reach = awaitReach(mark);
        assertThat(reach.salon()).isEqualTo(alpha.id());
        assertThat(reach.sqlSalons()).as("row-level security is out of the way").contains(beta.id());
        assertThat(reach.jpaSalons()).containsExactly(alpha.id());
        assertThat(reach.jpaFoundOthers()).isFalse();
        assertThat(reach.written()).isEqualTo(alpha.id());
    }

    private static Reach awaitReach(UUID mark) throws InterruptedException {
        Instant until = Instant.now().plusSeconds(15);
        while (EventProbes.reach(mark) == null) {
            if (Instant.now().isAfter(until)) {
                throw new AssertionError("The reaching subscriber did not run");
            }
            Thread.sleep(20);
        }
        return EventProbes.reach(mark);
    }

    private static <T> T as(TestSalons salons, TestSalon salon, Callable<T> work) throws Exception {
        return TenantContext.callAs(salons.asCurrent(salon), work);
    }
}
