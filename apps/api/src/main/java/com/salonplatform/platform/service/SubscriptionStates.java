package com.salonplatform.platform.service;

import com.salonplatform.platform.domain.BillingCalendar;
import com.salonplatform.platform.domain.BillingCalendar.State;
import com.salonplatform.platform.domain.BillingTerms;
import com.salonplatform.platform.repository.BillingLedger;
import com.salonplatform.platform.repository.BillingLedger.NewSubscription;
import com.salonplatform.platform.repository.BillingLedger.Salon;
import com.salonplatform.platform.repository.BillingLedger.Subscription;
import com.salonplatform.platform.repository.InvoiceStore;
import com.salonplatform.platform.repository.InvoiceStore.Invoice;
import com.salonplatform.platform.repository.InvoiceStore.NewPayment;
import com.salonplatform.platform.repository.InvoiceStore.Payment;
import com.salonplatform.platform.repository.PlatformAudit;
import com.salonplatform.shared.persistence.PlatformDatabase;
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
    private final InvoiceStore invoices;
    private final BillingNotices notices;

    public SubscriptionStates(
            PlatformDatabase database,
            PlatformAudit audit,
            BillingLedger ledger,
            InvoiceStore invoices,
            BillingNotices notices) {
        this.database = database;
        this.audit = audit;
        this.ledger = ledger;
        this.invoices = invoices;
        this.notices = notices;
    }

    /**
     * When a past-due subscription becomes, or a read-only one became, read-only: 14 days after the
     * due instant of its oldest unpaid invoice past due. Null when nothing is past due.
     */
    public Instant readOnlyAt(Subscription subscription, Instant now) {
        Instant pastDueSince = pastDueSince(subscription, now);
        return pastDueSince == null ? null : BillingCalendar.readOnlyAt(pastDueSince);
    }

    /** The due instant of the oldest unpaid invoice past due, or null. */
    public Instant pastDueSince(Subscription subscription, Instant now) {
        return invoices.openInvoices(subscription.id()).stream()
                .map(Invoice::dueAt)
                .filter(due -> !now.isBefore(due))
                .min(Instant::compareTo)
                .orElse(null);
    }

    /** Starts a trialing subscription for a salon that has none (the caller checked). */
    public Subscription start(
            Salon salon, long amount, String currency, String method, Instant start, String actorKind, String actorId) {
        database.requireTransaction();
        UUID id = UUID.randomUUID();
        Instant trialEnd = BillingCalendar.trialEnd(start);
        LocalDateTime anchor = BillingCalendar.anchor(trialEnd, salon.zone());
        ledger.startSubscription(
                id, new NewSubscription(salon.id(), amount, currency, method, start, trialEnd, anchor), start);
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
            Subscription subscription, Instant now, String actorKind, String actorId, BillingChanges changes) {
        List<Invoice> open = invoices.openInvoices(subscription.id());
        State next = BillingCalendar.stateAt(
                now,
                subscription.invoicesIssued(),
                open.stream().map(Invoice::dueAt).toList());
        if (next == subscription.state()) {
            return subscription;
        }
        ledger.changeState(subscription.id(), next, now);
        ledger.setAccessState(subscription.salonId(), next == State.READ_ONLY ? "read_only" : "full", now);
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
        invoices.pay(id, invoice, new NewPayment(method, reference, providerEventId, receivedOn, recordedBy), now);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("paymentId", id.toString());
        details.put("number", BillingTerms.invoiceNumber(invoice.number()));
        details.put("method", method);
        details.put("amount", invoice.amount());
        details.put("currency", invoice.currency());
        audit.record(
                actorKind, actorId, "payment.recorded", "invoice", invoice.id().toString(), details, now);
        applyState(
                ledger.subscription(subscription.id(), false).orElseThrow(),
                now,
                actorKind,
                actorId,
                BillingChanges.none());
        return invoices.payments(invoice.id()).stream()
                .filter(p -> p.id().equals(id))
                .findFirst()
                .orElseThrow();
    }
}
