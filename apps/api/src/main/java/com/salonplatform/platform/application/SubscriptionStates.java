package com.salonplatform.platform.application;

import com.salonplatform.platform.application.BillingLedger.Changes;
import com.salonplatform.platform.application.BillingLedger.Invoice;
import com.salonplatform.platform.application.BillingLedger.Payment;
import com.salonplatform.platform.application.BillingLedger.Salon;
import com.salonplatform.platform.application.BillingLedger.Subscription;
import com.salonplatform.platform.domain.BillingCalendar;
import com.salonplatform.platform.domain.BillingCalendar.State;
import com.salonplatform.platform.domain.BillingTerms;
import com.salonplatform.platform.infrastructure.PlatformAudit;
import com.salonplatform.shared.infrastructure.PlatformDatabase;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * A subscription's life on the platform connection (docs/architecture/operator-console.md,
 * "Subscriptions, invoices and entitlements"): its start, the state its unpaid invoices imply, and the
 * one payment that settles an invoice. Runs inside the caller's platform transaction, with the
 * subscription's row locked by the caller.
 */
@Service
public class SubscriptionStates {

    private final PlatformDatabase database;
    private final PlatformAudit audit;
    private final BillingLedger ledger;
    private final BillingNotices notices;

    public SubscriptionStates(
            PlatformDatabase database, PlatformAudit audit, BillingLedger ledger, BillingNotices notices) {
        this.database = database;
        this.audit = audit;
        this.ledger = ledger;
        this.notices = notices;
    }

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
        return ledger.subscription(id, false).orElseThrow();
    }

    /**
     * Brings the subscription's state, and the registry's access state, to what its unpaid invoices
     * imply at {@code now}. On becoming read-only the read-only notice is sent once. A payment can only
     * lower the state; the job raises it.
     */
    public Subscription applyState(
            Subscription subscription, Instant now, String actorKind, String actorId, Changes changes) {
        List<Invoice> open = ledger.openInvoices(subscription.id());
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
            Salon salon = ledger.salon(subscription.salonId(), false).orElseThrow();
            open.stream()
                    .filter(invoice -> !now.isBefore(BillingCalendar.readOnlyAt(invoice.dueAt())))
                    .min(java.util.Comparator.comparing(Invoice::dueAt))
                    .ifPresent(invoice -> {
                        if (notices.notice(salon, invoice, "read_only", null, now)) {
                            changes.reminders().add(BillingTerms.invoiceNumber(invoice.number()) + ":read_only");
                        }
                    });
        }
        return ledger.subscription(subscription.id(), false).orElseThrow();
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
        applyState(
                ledger.subscription(subscription.id(), false).orElseThrow(), now, actorKind, actorId, Changes.none());
        return ledger.payments(invoice.id()).stream()
                .filter(p -> p.id().equals(id))
                .findFirst()
                .orElseThrow();
    }
}
