package com.salonplatform.platform.infrastructure;

import com.salonplatform.platform.domain.BillingCalendar;
import com.salonplatform.platform.domain.BillingCalendar.State;
import com.salonplatform.shared.infrastructure.PlatformDatabase;
import com.salonplatform.shared.observability.Traces;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Subscription billing's records on the platform connection (docs/architecture/operator-console.md,
 * "Subscriptions, invoices and entitlements"): salons, subscriptions, invoices and payments as billing
 * reads them, and the outbox messages billing writes. Every method runs inside the caller's platform
 * transaction: an invoice, its charge attempt and their outbox messages commit together, and the
 * provider call itself happens afterwards through the outbox, with the attempt's idempotency key. A
 * subscription is changed only while its row is locked, so the job, operator actions and provider events
 * never interleave on one subscription ({@link InvoiceIssuing}, {@link BillingNotices},
 * {@link SubscriptionStates}).
 */
@Service
public class BillingLedger {

    public static final String EMAIL = "email";
    public static final String CHARGE = "billing-charge";

    /** A salon as billing needs it. */
    public record Salon(
            UUID id,
            String slug,
            String name,
            ZoneId zone,
            String currency,
            String ownerEmail,
            UUID planId,
            String planKey,
            String planName,
            String status,
            String accessState,
            long version) {}

    /** A subscription row. */
    public record Subscription(
            UUID id,
            UUID salonId,
            State state,
            long monthlyAmount,
            String currency,
            String paymentMethod,
            Instant startedAt,
            Instant trialEndsAt,
            LocalDateTime anchor,
            int invoicesIssued,
            Instant nextInvoiceAt,
            long version,
            Instant createdAt,
            Instant updatedAt) {}

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

    /** What one pass did to one subscription, for the run-now hook's answer and the log. */
    public record Changes(List<String> issued, List<String> reminders, List<String> charges, List<String> states) {

        public static Changes none() {
            return new Changes(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }

        public boolean any() {
            return !issued.isEmpty() || !reminders.isEmpty() || !charges.isEmpty() || !states.isEmpty();
        }
    }

    private final PlatformDatabase database;
    private final Traces traces;
    private final JsonMapper json;

    public BillingLedger(PlatformDatabase database, Traces traces, JsonMapper json) {
        this.database = database;
        this.traces = traces;
        this.json = json;
    }

    // --- reads ---------------------------------------------------------------------------------

    private static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Salon salon(ResultSet row) throws SQLException {
        return new Salon(
                row.getObject("id", UUID.class),
                row.getString("slug"),
                row.getString("name"),
                ZoneId.of(row.getString("time_zone")),
                row.getString("currency"),
                row.getString("owner_email"),
                row.getObject("plan_id", UUID.class),
                row.getString("plan_key"),
                row.getString("plan_name"),
                row.getString("status"),
                row.getString("access_state"),
                row.getLong("version"));
    }

    private static Subscription subscription(ResultSet row) throws SQLException {
        return new Subscription(
                row.getObject("id", UUID.class),
                row.getObject("salon_id", UUID.class),
                State.of(row.getString("state")),
                row.getLong("monthly_amount"),
                row.getString("currency"),
                row.getString("payment_method"),
                instant(row, "started_at"),
                instant(row, "trial_ends_at"),
                row.getTimestamp("anchor_local").toLocalDateTime(),
                row.getInt("invoices_issued"),
                instant(row, "next_invoice_at"),
                row.getLong("version"),
                instant(row, "created_at"),
                instant(row, "updated_at"));
    }

    public static Invoice invoice(ResultSet row) throws SQLException {
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

    public static Payment payment(ResultSet row) throws SQLException {
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

    public static final String INVOICE_COLUMNS = """
            id, number, salon_id, subscription_id, plan_id, plan_key, plan_name, period_index, period_start,
            period_end, amount, currency, issued_at, due_at, status, paid_at
            """;

    /** A salon, optionally locking its registry row (plan moves). */
    public Optional<Salon> salon(UUID id, boolean lock) {
        database.requireTransaction();
        return database
                .jdbcTemplate()
                .query("""
                SELECT t.id, t.slug, t.name, t.time_zone, t.currency, t.owner_email, t.plan_id, t.status,
                       t.access_state, t.version, p.key AS plan_key, p.name AS plan_name
                  FROM tenant t LEFT JOIN plan p ON p.id = t.plan_id WHERE t.id = ?
                """ + (lock ? " FOR UPDATE OF t" : ""), (row, n) -> salon(row), id)
                .stream()
                .findFirst();
    }

    /**
     * Whether a salon is still billed: active or suspended. A closed or deleted salon's billing stopped at
     * its closure (F03-S03). Read with a share lock on its registry row, so a closure waits for a billing
     * pass in progress and a pass that starts after it sees it.
     */
    public boolean billed(UUID salonId) {
        database.requireTransaction();
        return database
                .jdbcTemplate()
                .queryForList("SELECT status FROM tenant WHERE id = ? FOR SHARE", String.class, salonId)
                .stream()
                .findFirst()
                .map(status -> !"closed".equals(status) && !"deleted".equals(status))
                .orElse(false);
    }

    public Optional<Subscription> subscriptionOf(UUID salonId, boolean lock) {
        database.requireTransaction();
        return database
                .jdbcTemplate()
                .query(
                        "SELECT * FROM subscription WHERE salon_id = ?" + (lock ? " FOR UPDATE" : ""),
                        (row, n) -> subscription(row),
                        salonId)
                .stream()
                .findFirst();
    }

    public Optional<Subscription> subscription(UUID id, boolean lock) {
        database.requireTransaction();
        return database
                .jdbcTemplate()
                .query(
                        "SELECT * FROM subscription WHERE id = ?" + (lock ? " FOR UPDATE" : ""),
                        (row, n) -> subscription(row),
                        id)
                .stream()
                .findFirst();
    }

    public Optional<Invoice> invoiceById(UUID id, boolean lock) {
        database.requireTransaction();
        return database
                .jdbcTemplate()
                .query(
                        "SELECT " + INVOICE_COLUMNS + " FROM invoice WHERE id = ?" + (lock ? " FOR UPDATE" : ""),
                        (row, n) -> invoice(row),
                        id)
                .stream()
                .findFirst();
    }

    public List<Invoice> openInvoices(UUID subscriptionId) {
        database.requireTransaction();
        return database.jdbcTemplate()
                .query(
                        "SELECT " + INVOICE_COLUMNS
                                + " FROM invoice WHERE subscription_id = ? AND status = 'open' ORDER BY due_at, number",
                        (row, n) -> invoice(row),
                        subscriptionId);
    }

    public Optional<Invoice> latestInvoice(UUID subscriptionId) {
        database.requireTransaction();
        return database
                .jdbcTemplate()
                .query(
                        "SELECT " + INVOICE_COLUMNS
                                + " FROM invoice WHERE subscription_id = ? ORDER BY period_index DESC LIMIT 1",
                        (row, n) -> invoice(row),
                        subscriptionId)
                .stream()
                .findFirst();
    }

    public List<Payment> payments(UUID invoiceId) {
        database.requireTransaction();
        return database.jdbcTemplate()
                .query(
                        "SELECT * FROM invoice_payment WHERE invoice_id = ? ORDER BY paid_at, id",
                        (row, n) -> payment(row),
                        invoiceId);
    }

    /**
     * When a past-due subscription becomes, or a read-only one became, read-only: 14 days after the
     * due instant of its oldest unpaid invoice past due. Null when nothing is past due.
     */
    public Instant readOnlyAt(Subscription subscription, Instant now) {
        return openInvoices(subscription.id()).stream()
                .map(Invoice::dueAt)
                .filter(due -> !now.isBefore(due))
                .min(Instant::compareTo)
                .map(BillingCalendar::readOnlyAt)
                .orElse(null);
    }

    /** The due instant of the oldest unpaid invoice past due, or null. */
    public Instant pastDueSince(Subscription subscription, Instant now) {
        return openInvoices(subscription.id()).stream()
                .map(Invoice::dueAt)
                .filter(due -> !now.isBefore(due))
                .min(Instant::compareTo)
                .orElse(null);
    }

    // --- outbox ---------------------------------------------------------------------------------

    public void enqueue(UUID salon, String channel, Map<String, Object> payload, Instant now) {
        enqueueWithId(salon, UUID.randomUUID(), channel, payload, now);
    }

    public void enqueueWithId(UUID salon, UUID id, String channel, Map<String, Object> payload, Instant now) {
        database.jdbcTemplate()
                .queryForObject(
                        "SELECT outbox_enqueue_for_salon(?, ?, ?, ?::jsonb, ?, ?)::text",
                        String.class,
                        salon,
                        id,
                        channel,
                        json.writeValueAsString(payload),
                        traces.currentTraceParent().orElse(null),
                        Timestamp.from(now));
    }
}
