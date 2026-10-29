package com.salonplatform.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.testing.StartedApplication;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import com.salonplatform.testing.TestSalons;
import com.salonplatform.testing.TestSalons.TestSalon;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The processed-event record ({@link ProcessedEventStore}), direct SQL that Hibernate's filter never sees: run
 * in alpha's transaction naming beta, row-level security alone refuses it, and with no salon set nothing can
 * be recorded at all. Naming alpha, the same statement records once and then finds the record.
 */
class ProcessedEventStoreRowLevelSecurityAloneIntegrationTest {

    private static TestDatabase database;

    private static StartedApplication application;

    private static JdbcTemplate owner;

    private static TestSalons salons;

    private static ProcessedEventStore store;

    private static TransactionTemplate transaction;

    @BeforeAll
    static void applicationWithRowLevelSecurityOn() {
        database = TestDatabase.createEmpty(TestPostgres.shared());
        application = database.start();
        owner = database.asOwner();
        salons = new TestSalons(owner);
        store = application.bean(ProcessedEventStore.class);
        transaction = new TransactionTemplate(application.bean(PlatformTransactionManager.class));
    }

    @AfterAll
    static void stop() {
        application.close();
    }

    @Test
    void aSalonsTransactionCannotRecordAnotherSalonsEvent() {
        TestSalon alpha = salons.create("Processed Alpha", "#111111");
        TestSalon beta = salons.create("Processed Beta", "#222222");
        UUID betasEvent = event(beta);

        assertThatExceptionOfType(DataAccessException.class)
                .isThrownBy(() ->
                        inTransactionOf(alpha, () -> store.record(beta.id(), betasEvent, "probe.first", Instant.now())))
                .satisfies(refused ->
                        assertThat(refused.getMostSpecificCause().getMessage()).contains("row-level security"));
        assertThat(processedRecords(betasEvent)).isZero();
    }

    @Test
    void withNoSalonSetNothingIsRecorded() {
        TestSalon alpha = salons.create("Processed Nobody", "#333333");
        UUID event = event(alpha);

        assertThatExceptionOfType(DataAccessException.class)
                .isThrownBy(() ->
                        transaction.execute(status -> store.record(alpha.id(), event, "probe.first", Instant.now())));
        assertThat(processedRecords(event)).isZero();
    }

    @Test
    void aSalonRecordsItsOwnEventOnceAndThenFindsTheRecord() {
        TestSalon alpha = salons.create("Processed Own", "#444444");
        UUID event = event(alpha);

        boolean first = inTransactionOf(alpha, () -> store.record(alpha.id(), event, "probe.first", Instant.now()));
        boolean again = inTransactionOf(alpha, () -> store.record(alpha.id(), event, "probe.first", Instant.now()));

        assertThat(first).isTrue();
        assertThat(again).isFalse();
        assertThat(processedRecords(event)).isEqualTo(1);
    }

    @Test
    void theRecordsOfAnEventGoWithIt() {
        TestSalon alpha = salons.create("Processed Removed", "#555555");
        UUID event = event(alpha);
        inTransactionOf(alpha, () -> store.record(alpha.id(), event, "probe.first", Instant.now()));

        owner.update("DELETE FROM outbox_message WHERE id = ?", event);

        assertThat(processedRecords(event)).isZero();
    }

    private static UUID event(TestSalon salon) {
        UUID id = UUID.randomUUID();
        owner.update(
                "INSERT INTO outbox_message (tenant_id, id, kind, channel, event_type, payload, status, attempts,"
                        + " next_attempt_at, created_at, updated_at, sent_at)"
                        + " VALUES (?, ?, 'event', 'event', 'probe.Noted.v1', '{}', 'sent', 1, now(), now(), now(),"
                        + " now())",
                salon.id(),
                id);
        return id;
    }

    private static int processedRecords(UUID event) {
        return owner.queryForObject(
                "SELECT count(*) FROM shared_processed_event WHERE event_id = ?", Integer.class, event);
    }

    private static <T> T inTransactionOf(TestSalon salon, Supplier<T> work) {
        try {
            return TenantContext.callAs(salons.asCurrent(salon), () -> transaction.execute(status -> work.get()));
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
