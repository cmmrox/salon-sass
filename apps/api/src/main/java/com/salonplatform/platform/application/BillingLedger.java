package com.salonplatform.platform.application;

import com.salonplatform.platform.domain.BillingCalendar;
import com.salonplatform.platform.domain.BillingCalendar.Reminder;
import com.salonplatform.platform.domain.BillingCalendar.State;
import com.salonplatform.platform.domain.BillingTerms;
import com.salonplatform.platform.infrastructure.PlatformAudit;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Subscription billing's records on the platform connection (docs/architecture/operator-console.md,
 * "Subscriptions, invoices and entitlements"). Every method runs inside the caller's platform
 * transaction: an invoice, its charge attempt and their outbox messages commit together, and the
 * provider call itself happens afterwards through the outbox, with the attempt's idempotency key.
 * A subscription is changed only while its row is locked, so the job, operator actions and provider
 * events never interleave on one subscription; reminders and charge attempts are unique per invoice
 * and occasion in the database, so a repeated or concurrent run adds nothing.
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
    private final PlatformAudit audit;
    private final Traces traces;
    private final JsonMapper json;
    private final BillingFaults faults;
    private final String platformDomain;
    private final String linkOrigin;

    public BillingLedger(
            PlatformDatabase database,
            PlatformAudit audit,
            Traces traces,
            JsonMapper json,
            BillingFaults faults,
            @Value("${salon.platform-domain}") String platformDomain,
            @Value("${salon.billing.link-origin:https://%s}") String linkOrigin) {
        this.database = database;
        this.audit = audit;
        this.traces = traces;
        this.json = json;
        this.faults = faults;
        this.platformDomain = platformDomain.strip().toLowerCase(Locale.ROOT);
        this.linkOrigin = linkOrigin;
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

    static Invoice invoice(ResultSet row) throws SQLException {
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

    static Payment payment(ResultSet row) throws SQLException {
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

    static final String INVOICE_COLUMNS = """
            id, number, salon_id, subscription_id, plan_id, plan_key, plan_name, period_index, period_start,
            period_end, amount, currency, issued_at, due_at, status, paid_at
            """;

    /** A salon, optionally locking its registry row (plan moves). */
    public Optional<Salon> salon(UUID id, boolean lock) {
        database.requireTransaction();
        return database.jdbc().query("""
                SELECT t.id, t.slug, t.name, t.time_zone, t.currency, t.owner_email, t.plan_id, t.status,
                       t.access_state, t.version, p.key AS plan_key, p.name AS plan_name
                  FROM tenant t LEFT JOIN plan p ON p.id = t.plan_id WHERE t.id = ?
                """ + (lock ? " FOR UPDATE OF t" : ""), (row, n) -> salon(row), id).stream()
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
                .jdbc()
                .queryForList("SELECT status FROM tenant WHERE id = ? FOR SHARE", String.class, salonId)
                .stream()
                .findFirst()
                .map(status -> !"closed".equals(status) && !"deleted".equals(status))
                .orElse(false);
    }

    public Optional<Subscription> subscriptionOf(UUID salonId, boolean lock) {
        database.requireTransaction();
        return database
                .jdbc()
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
                .jdbc()
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
                .jdbc()
                .query(
                        "SELECT " + INVOICE_COLUMNS + " FROM invoice WHERE id = ?" + (lock ? " FOR UPDATE" : ""),
                        (row, n) -> invoice(row),
                        id)
                .stream()
                .findFirst();
    }

    public List<Invoice> openInvoices(UUID subscriptionId) {
        database.requireTransaction();
        return database.jdbc()
                .query(
                        "SELECT " + INVOICE_COLUMNS
                                + " FROM invoice WHERE subscription_id = ? AND status = 'open' ORDER BY due_at, number",
                        (row, n) -> invoice(row),
                        subscriptionId);
    }

    public Optional<Invoice> latestInvoice(UUID subscriptionId) {
        database.requireTransaction();
        return database
                .jdbc()
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
        return database.jdbc()
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

    // --- writes ---------------------------------------------------------------------------------

    /** Starts a trialing subscription for a salon that has none (the caller checked). */
    public Subscription start(
            Salon salon, long amount, String currency, String method, Instant start, String actorKind, String actorId) {
        database.requireTransaction();
        UUID id = UUID.randomUUID();
        Instant trialEnd = BillingCalendar.trialEnd(start);
        LocalDateTime anchor = BillingCalendar.anchor(trialEnd, salon.zone());
        Timestamp at = Timestamp.from(start);
        database.jdbc()
                .update(
                        """
                INSERT INTO subscription (id, salon_id, state, monthly_amount, currency, payment_method, started_at,
                    trial_ends_at, anchor_local, invoices_issued, next_invoice_at, version, created_at, updated_at)
                VALUES (?, ?, 'trialing', ?, ?, ?, ?, ?, ?, 0, ?, 0, ?, ?)
                """,
                        id,
                        salon.id(),
                        amount,
                        currency,
                        method,
                        at,
                        Timestamp.from(trialEnd),
                        Timestamp.valueOf(anchor),
                        Timestamp.from(trialEnd),
                        at,
                        at);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("subscriptionId", id.toString());
        details.put("monthlyAmount", amount);
        details.put("currency", currency);
        details.put("paymentMethod", method);
        details.put("trialEndsAt", trialEnd.toString());
        audit.record(
                actorKind, actorId, "subscription.started", "salon", salon.id().toString(), details, start);
        return subscription(id, false).orElseThrow();
    }

    /**
     * Issues every invoice whose issue instant has come, one period at a time, each with its charge
     * attempt (card) in the same transaction. The amount is the subscription's at issue and the plan
     * the salon's at issue; an issued invoice never changes.
     */
    public Subscription issueDue(Subscription locked, Instant now, Changes changes) {
        Subscription current = locked;
        int guard = 0;
        while (!current.nextInvoiceAt().isAfter(now) && guard++ < 36) {
            current = issue(current, now, changes);
        }
        return current;
    }

    private Subscription issue(Subscription subscription, Instant now, Changes changes) {
        Salon salon = salon(subscription.salonId(), false).orElseThrow();
        int index = subscription.invoicesIssued();
        Instant periodStart = subscription.nextInvoiceAt();
        Instant periodEnd = BillingCalendar.periodStart(subscription.anchor(), salon.zone(), index + 1);
        Instant due = BillingCalendar.due(periodStart);
        Long number = database.jdbc()
                .queryForObject(
                        "UPDATE invoice_number SET last_number = last_number + 1 WHERE id RETURNING last_number",
                        Long.class);
        UUID id = UUID.randomUUID();
        Timestamp at = Timestamp.from(now);
        database.jdbc()
                .update(
                        """
                INSERT INTO invoice (id, number, salon_id, subscription_id, plan_id, plan_key, plan_name, period_index,
                    period_start, period_end, amount, currency, issued_at, due_at, status, paid_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'open', NULL, ?, ?)
                """,
                        id,
                        number,
                        salon.id(),
                        subscription.id(),
                        salon.planId(),
                        salon.planKey(),
                        salon.planName(),
                        index,
                        Timestamp.from(periodStart),
                        Timestamp.from(periodEnd),
                        subscription.monthlyAmount(),
                        subscription.currency(),
                        Timestamp.from(periodStart),
                        Timestamp.from(due),
                        at,
                        at);
        database.jdbc().update("""
                UPDATE subscription SET invoices_issued = ?, next_invoice_at = ?, updated_at = ? WHERE id = ?
                """, index + 1, Timestamp.from(periodEnd), at, subscription.id());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("salonId", salon.id().toString());
        details.put("number", BillingTerms.invoiceNumber(number));
        details.put("planKey", salon.planKey());
        details.put("amount", subscription.monthlyAmount());
        details.put("currency", subscription.currency());
        details.put("periodStart", periodStart.toString());
        details.put("periodEnd", periodEnd.toString());
        details.put("dueAt", due.toString());
        audit.record(PlatformAudit.SYSTEM, "billing", "invoice.issued", "invoice", id.toString(), details, now);
        changes.issued().add(BillingTerms.invoiceNumber(number));
        faults.reached(BillingFaults.Point.AFTER_INVOICE_ISSUE);
        Invoice invoice = invoiceById(id, false).orElseThrow();
        if (BillingTerms.CARD.equals(subscription.paymentMethod())) {
            requestCharge(salon, invoice, "issue", now, changes);
        }
        return subscription(subscription.id(), false).orElseThrow();
    }

    /**
     * Records a charge attempt for an invoice and occasion, once, with the outbox message that makes
     * the provider call afterwards. Nothing when that occasion already has an attempt.
     */
    public boolean requestCharge(Salon salon, Invoice invoice, String occasion, Instant now, Changes changes) {
        UUID id = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        Timestamp at = Timestamp.from(now);
        int inserted = database.jdbc().update("""
                INSERT INTO charge_attempt (id, invoice_id, occasion, attempt_key, status, requested_at, settled_at,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, 'requested', ?, NULL, ?, ?)
                ON CONFLICT (invoice_id, occasion) DO NOTHING
                """, id, invoice.id(), occasion, key, at, at, at);
        if (inserted == 0) {
            return false;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("attemptKey", key.toString());
        payload.put("invoiceId", invoice.id().toString());
        payload.put("amount", invoice.amount());
        payload.put("currency", invoice.currency());
        enqueue(salon.id(), CHARGE, payload, now);
        audit.record(
                PlatformAudit.SYSTEM,
                "billing",
                "charge.requested",
                "invoice",
                invoice.id().toString(),
                Map.of("attemptId", id.toString(), "occasion", occasion),
                now);
        changes.charges().add(BillingTerms.invoiceNumber(invoice.number()) + ":" + occasion);
        return true;
    }

    /**
     * Sends the reminders whose boundary has come for every unpaid invoice (once per invoice and
     * kind), with a retry of the charge on each reminder day for a card subscription.
     */
    public void remind(Subscription subscription, Instant now, Changes changes) {
        Salon salon = salon(subscription.salonId(), false).orElseThrow();
        for (Invoice invoice : openInvoices(subscription.id())) {
            for (Reminder reminder : BillingCalendar.remindersDue(now, invoice.dueAt())) {
                if (notice(salon, invoice, reminder.kind(), null, now)) {
                    changes.reminders().add(BillingTerms.invoiceNumber(invoice.number()) + ":" + reminder.kind());
                }
                if (BillingTerms.CARD.equals(subscription.paymentMethod())) {
                    requestCharge(salon, invoice, reminder.kind(), now, changes);
                }
            }
        }
    }

    /**
     * Records a reminder or notice once per invoice and kind (a failed-charge notice once per charge
     * attempt) and writes its email to the Owner contact. A salon without an Owner contact (a legacy
     * salon) has the reminder recorded with no email.
     *
     * @return whether it was recorded now (false: it already had been)
     */
    public boolean notice(Salon salon, Invoice invoice, String kind, UUID attemptId, Instant now) {
        UUID id = UUID.randomUUID();
        UUID message = salon.ownerEmail() == null ? null : UUID.randomUUID();
        Timestamp at = Timestamp.from(now);
        String conflict = attemptId == null
                ? "(invoice_id, kind) WHERE kind <> 'failed_charge'"
                : "(charge_attempt_id) WHERE kind = 'failed_charge'";
        int inserted =
                database.jdbc().update("""
                INSERT INTO billing_reminder (id, invoice_id, kind, charge_attempt_id, message_id, sent_at, created_at,
                    updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT %s DO NOTHING
                """.formatted(conflict), id, invoice.id(), kind, attemptId, message, at, at, at);
        if (inserted == 0) {
            return false;
        }
        if (message != null) {
            enqueueWithId(
                    salon.id(),
                    message,
                    EMAIL,
                    BillingEmails.compose(kind, salon.ownerEmail(), invoice, salon.zone(), link(salon)),
                    now);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("kind", kind);
        details.put("number", BillingTerms.invoiceNumber(invoice.number()));
        details.put("recipient", message == null ? "none" : "owner");
        audit.record(
                PlatformAudit.SYSTEM,
                "billing",
                "billing.reminder_sent",
                "invoice",
                invoice.id().toString(),
                details,
                now);
        return true;
    }

    private String link(Salon salon) {
        return String.format(Locale.ROOT, linkOrigin, salon.slug() + "." + platformDomain) + "/workspace/billing";
    }

    /**
     * Brings the subscription's state, and the registry's access state, to what its unpaid invoices
     * imply at {@code now}. On becoming read-only the read-only notice is sent once. A payment can only
     * lower the state; the job raises it.
     */
    public Subscription applyState(
            Subscription subscription, Instant now, String actorKind, String actorId, Changes changes) {
        List<Invoice> open = openInvoices(subscription.id());
        State next = BillingCalendar.stateAt(
                now,
                subscription.invoicesIssued(),
                open.stream().map(Invoice::dueAt).toList());
        if (next == subscription.state()) {
            return subscription;
        }
        Timestamp at = Timestamp.from(now);
        database.jdbc()
                .update(
                        "UPDATE subscription SET state = ?, updated_at = ? WHERE id = ?",
                        next.value(),
                        at,
                        subscription.id());
        String access = next == State.READ_ONLY ? "read_only" : "full";
        database.jdbc()
                .update(
                        "UPDATE tenant SET access_state = ?, updated_at = ? WHERE id = ? AND access_state <> ?",
                        access,
                        at,
                        subscription.salonId(),
                        access);
        audit.record(
                actorKind,
                actorId,
                "subscription.state_changed",
                "salon",
                subscription.salonId().toString(),
                Map.of("from", subscription.state().value(), "to", next.value()),
                now);
        changes.states().add(subscription.state().value() + "->" + next.value());
        if (next == State.READ_ONLY) {
            Salon salon = salon(subscription.salonId(), false).orElseThrow();
            open.stream()
                    .filter(invoice -> !now.isBefore(BillingCalendar.readOnlyAt(invoice.dueAt())))
                    .min(java.util.Comparator.comparing(Invoice::dueAt))
                    .ifPresent(invoice -> {
                        if (notice(salon, invoice, "read_only", null, now)) {
                            changes.reminders().add(BillingTerms.invoiceNumber(invoice.number()) + ":read_only");
                        }
                    });
        }
        return subscription(subscription.id(), false).orElseThrow();
    }

    /**
     * Pays an open invoice with its one payment (the caller holds the subscription and invoice
     * locks and checked the invoice is open), then lowers the state at once.
     */
    public Payment pay(
            Subscription subscription,
            Invoice invoice,
            String method,
            String reference,
            String providerEventId,
            LocalDate receivedOn,
            UUID recordedBy,
            Instant now,
            String actorKind,
            String actorId) {
        UUID id = UUID.randomUUID();
        Timestamp at = Timestamp.from(now);
        database.jdbc()
                .update(
                        """
                INSERT INTO invoice_payment (id, invoice_id, method, reference, provider_event_id, amount, received_on,
                    paid_at, recorded_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                        id,
                        invoice.id(),
                        method,
                        reference,
                        providerEventId,
                        invoice.amount(),
                        receivedOn == null ? null : java.sql.Date.valueOf(receivedOn),
                        at,
                        recordedBy,
                        at,
                        at);
        database.jdbc()
                .update(
                        "UPDATE invoice SET status = 'paid', paid_at = ?, updated_at = ? WHERE id = ?",
                        at,
                        at,
                        invoice.id());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("paymentId", id.toString());
        details.put("number", BillingTerms.invoiceNumber(invoice.number()));
        details.put("method", method);
        details.put("amount", invoice.amount());
        details.put("currency", invoice.currency());
        audit.record(
                actorKind, actorId, "payment.recorded", "invoice", invoice.id().toString(), details, now);
        applyState(subscription(subscription.id(), false).orElseThrow(), now, actorKind, actorId, Changes.none());
        return payments(invoice.id()).stream()
                .filter(p -> p.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private void enqueue(UUID salon, String channel, Map<String, Object> payload, Instant now) {
        enqueueWithId(salon, UUID.randomUUID(), channel, payload, now);
    }

    private void enqueueWithId(UUID salon, UUID id, String channel, Map<String, Object> payload, Instant now) {
        database.jdbc()
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
