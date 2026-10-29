package com.salonplatform.platform.infrastructure;

import static com.salonplatform.platform.infrastructure.PlatformTimes.time;

import com.salonplatform.platform.infrastructure.BillingLedger.Salon;
import com.salonplatform.platform.infrastructure.BillingLedger.Subscription;
import com.salonplatform.shared.infrastructure.PlatformDatabase;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The invoice side of subscription billing on the platform connection: invoice numbers and invoices,
 * charge attempts, reminders, payments and the billing provider's events ({@code invoice_number},
 * {@code invoice}, {@code charge_attempt}, {@code billing_reminder}, {@code invoice_payment},
 * {@code provider_event}). Named direct-SQL exception 1 of docs/engineering/backend.md: platform tables
 * with no {@code tenant_id}. What happens once is held by the database: a charge attempt is unique per
 * invoice and occasion, a reminder per invoice and kind (a failed-charge notice per attempt), and a
 * provider event is handled under a transaction lock on its id. Salons and subscriptions are {@link BillingLedger}.
 */
@Component
public class InvoiceStore {

    /** An invoice row. */
    public record Invoice(
            UUID id,
            long number,
            UUID salonId,
            UUID subscriptionId,
            UUID planId,
            String planKey,
            String planName,
            int periodIndex,
            Instant periodStart,
            Instant periodEnd,
            long amount,
            String currency,
            Instant issuedAt,
            Instant dueAt,
            String status,
            Instant paidAt) {

        public boolean open() {
            return "open".equals(status);
        }
    }

    /** A payment row. */
    public record Payment(
            UUID id,
            UUID invoiceId,
            String method,
            String reference,
            String providerEventId,
            long amount,
            LocalDate receivedOn,
            Instant paidAt) {}

    /** A charge attempt: its invoice and its status ({@code requested}, {@code succeeded}, {@code failed}). */
    public record ChargeAttempt(UUID id, UUID invoiceId, String status) {}

    /** A payment to record against one open invoice, for its whole amount. */
    public record NewPayment(
            String method, String reference, String providerEventId, LocalDate receivedOn, UUID recordedBy) {}

    /** A provider event as received, with the result of handling it. */
    public record ProviderEvent(
            String eventId, UUID invoiceId, UUID attemptKey, String outcome, Instant occurredAt, String result) {}

    private static final String INVOICE_COLUMNS = """
            id, number, salon_id, subscription_id, plan_id, plan_key, plan_name, period_index, period_start,
            period_end, amount, currency, issued_at, due_at, status, paid_at
            """;

    private final PlatformDatabase database;

    InvoiceStore(PlatformDatabase database) {
        this.database = database;
    }

    /** The next number of the platform-wide invoice sequence, taken in the caller's transaction. */
    public long nextInvoiceNumber() {
        return database.jdbc()
                .sql("UPDATE invoice_number SET last_number = last_number + 1 WHERE id RETURNING last_number")
                .query(Long.class)
                .single();
    }

    /** Issues an open invoice for one period of the subscription, at the salon's plan at issue. */
    public void issue(
            UUID id,
            long number,
            Salon salon,
            Subscription subscription,
            int periodIndex,
            Instant periodStart,
            Instant periodEnd,
            Instant due,
            Instant at) {
        database.jdbc()
                .sql("""
                        INSERT INTO invoice (id, number, salon_id, subscription_id, plan_id, plan_key, plan_name, period_index,
                            period_start, period_end, amount, currency, issued_at, due_at, status, paid_at, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'open', NULL, ?, ?)
                        """)
                .params(
                        id,
                        number,
                        salon.id(),
                        subscription.id(),
                        salon.planId(),
                        salon.planKey(),
                        salon.planName(),
                        periodIndex,
                        time(periodStart),
                        time(periodEnd),
                        subscription.monthlyAmount(),
                        subscription.currency(),
                        time(periodStart),
                        time(due),
                        time(at),
                        time(at))
                .update();
    }

    /**
     * Records a requested charge attempt for an invoice and occasion, unless that occasion has one.
     *
     * @return whether this call recorded it
     */
    public boolean requestCharge(UUID id, UUID invoiceId, String occasion, UUID attemptKey, Instant at) {
        return database.jdbc()
                        .sql("""
                        INSERT INTO charge_attempt (id, invoice_id, occasion, attempt_key, status, requested_at, settled_at,
                            created_at, updated_at)
                        VALUES (?, ?, ?, ?, 'requested', ?, NULL, ?, ?)
                        ON CONFLICT (invoice_id, occasion) DO NOTHING
                        """)
                        .params(id, invoiceId, occasion, attemptKey, time(at), time(at), time(at))
                        .update()
                > 0;
    }

    public Optional<ChargeAttempt> chargeAttempt(UUID attemptKey) {
        return database
                .jdbc()
                .sql("SELECT id, invoice_id, status FROM charge_attempt WHERE attempt_key = ?")
                .param(attemptKey)
                .query((row, n) -> new ChargeAttempt(
                        row.getObject("id", UUID.class),
                        row.getObject("invoice_id", UUID.class),
                        row.getString("status")))
                .list()
                .stream()
                .findFirst();
    }

    /** Settles a charge attempt that is still requested; a settled one keeps its first result. */
    public void settleCharge(UUID attemptId, String status, Instant at) {
        database.jdbc().sql("""
                        UPDATE charge_attempt SET status = ?, settled_at = ?, updated_at = ? WHERE id = ? AND status = 'requested'
                        """).params(status, time(at), time(at), attemptId).update();
    }

    /**
     * Records a reminder or notice once: per invoice and kind, or, for a failed-charge notice (with its
     * attempt), once per charge attempt.
     *
     * @param messageId the outbox message of its email, or null when the salon has no Owner contact
     * @return whether this call recorded it
     */
    public boolean recordReminder(
            UUID id, UUID invoiceId, String kind, UUID chargeAttemptId, UUID messageId, Instant at) {
        String once = chargeAttemptId == null
                ? "(invoice_id, kind) WHERE kind <> 'failed_charge'"
                : "(charge_attempt_id) WHERE kind = 'failed_charge'";
        return database.jdbc()
                        .sql("""
                        INSERT INTO billing_reminder (id, invoice_id, kind, charge_attempt_id, message_id, sent_at, created_at,
                            updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT %s DO NOTHING
                        """.formatted(once))
                        .params(id, invoiceId, kind, chargeAttemptId, messageId, time(at), time(at), time(at))
                        .update()
                > 0;
    }

    /** Records the invoice's one payment, for its whole amount, and marks it paid. */
    public void pay(UUID paymentId, Invoice invoice, NewPayment payment, Instant at) {
        database.jdbc()
                .sql("""
                        INSERT INTO invoice_payment (id, invoice_id, method, reference, provider_event_id, amount, received_on,
                            paid_at, recorded_by, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        paymentId,
                        invoice.id(),
                        payment.method(),
                        payment.reference(),
                        payment.providerEventId(),
                        invoice.amount(),
                        payment.receivedOn() == null ? null : java.sql.Date.valueOf(payment.receivedOn()),
                        time(at),
                        payment.recordedBy(),
                        time(at),
                        time(at))
                .update();
        database.jdbc()
                .sql("UPDATE invoice SET status = 'paid', paid_at = ?, updated_at = ? WHERE id = ?")
                .params(time(at), time(at), invoice.id())
                .update();
    }

    /** Holds, until the transaction ends, the lock under which one provider event is handled. */
    public void lockProviderEvent(String eventId) {
        database.jdbc()
                .sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 1))::text")
                .param("provider-event|" + eventId)
                .query(String.class)
                .single();
    }

    public boolean providerEventSeen(String eventId) {
        return database.jdbc()
                        .sql("SELECT count(*) FROM provider_event WHERE event_id = ?")
                        .param(eventId)
                        .query(Integer.class)
                        .single()
                > 0;
    }

    public void recordProviderEvent(ProviderEvent event, Instant at) {
        database.jdbc()
                .sql("""
                        INSERT INTO provider_event (event_id, invoice_id, attempt_key, outcome, occurred_at, result, created_at,
                            updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        event.eventId(),
                        event.invoiceId(),
                        event.attemptKey(),
                        event.outcome(),
                        time(event.occurredAt()),
                        event.result(),
                        time(at),
                        time(at))
                .update();
    }

    public Optional<Invoice> invoiceById(UUID id, boolean lock) {
        database.requireTransaction();
        return database
                .jdbc()
                .sql("SELECT " + INVOICE_COLUMNS + " FROM invoice WHERE id = ?" + (lock ? " FOR UPDATE" : ""))
                .param(id)
                .query((row, n) -> invoice(row))
                .list()
                .stream()
                .findFirst();
    }

    public List<Invoice> openInvoices(UUID subscriptionId) {
        database.requireTransaction();
        return database.jdbc()
                .sql("SELECT " + INVOICE_COLUMNS
                        + " FROM invoice WHERE subscription_id = ? AND status = 'open' ORDER BY due_at, number")
                .param(subscriptionId)
                .query((row, n) -> invoice(row))
                .list();
    }

    public Optional<Invoice> latestInvoice(UUID subscriptionId) {
        database.requireTransaction();
        return database
                .jdbc()
                .sql("SELECT " + INVOICE_COLUMNS
                        + " FROM invoice WHERE subscription_id = ? ORDER BY period_index DESC LIMIT 1")
                .param(subscriptionId)
                .query((row, n) -> invoice(row))
                .list()
                .stream()
                .findFirst();
    }

    /** One page of a salon's invoices, newest first. */
    public List<Invoice> invoicesOf(UUID salonId, int size, long offset) {
        return database.jdbc()
                .sql("SELECT " + INVOICE_COLUMNS
                        + " FROM invoice WHERE salon_id = ? ORDER BY issued_at DESC, number DESC LIMIT ? OFFSET ?")
                .params(salonId, size, offset)
                .query((row, n) -> invoice(row))
                .list();
    }

    public long countInvoicesOf(UUID salonId) {
        return database.jdbc()
                .sql("SELECT count(*) FROM invoice WHERE salon_id = ?")
                .param(salonId)
                .query(Long.class)
                .single();
    }

    public List<Payment> payments(UUID invoiceId) {
        database.requireTransaction();
        return database.jdbc()
                .sql("SELECT * FROM invoice_payment WHERE invoice_id = ? ORDER BY paid_at, id")
                .param(invoiceId)
                .query((row, n) -> payment(row))
                .list();
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Invoice invoice(ResultSet row) throws SQLException {
        return new Invoice(
                row.getObject("id", UUID.class),
                row.getLong("number"),
                row.getObject("salon_id", UUID.class),
                row.getObject("subscription_id", UUID.class),
                row.getObject("plan_id", UUID.class),
                row.getString("plan_key"),
                row.getString("plan_name"),
                row.getInt("period_index"),
                instant(row, "period_start"),
                instant(row, "period_end"),
                row.getLong("amount"),
                row.getString("currency"),
                instant(row, "issued_at"),
                instant(row, "due_at"),
                row.getString("status"),
                instant(row, "paid_at"));
    }

    private static Payment payment(ResultSet row) throws SQLException {
        java.sql.Date received = row.getDate("received_on");
        return new Payment(
                row.getObject("id", UUID.class),
                row.getObject("invoice_id", UUID.class),
                row.getString("method"),
                row.getString("reference"),
                row.getString("provider_event_id"),
                row.getLong("amount"),
                received == null ? null : received.toLocalDate(),
                instant(row, "paid_at"));
    }
}
