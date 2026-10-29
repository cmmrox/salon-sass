package com.salonplatform.platform.application;

import com.salonplatform.platform.domain.BillingCalendar;
import com.salonplatform.platform.domain.BillingCalendar.Reminder;
import com.salonplatform.platform.domain.BillingTerms;
import com.salonplatform.platform.infrastructure.BillingLedger;
import com.salonplatform.platform.infrastructure.BillingLedger.Changes;
import com.salonplatform.platform.infrastructure.BillingLedger.Invoice;
import com.salonplatform.platform.infrastructure.BillingLedger.Salon;
import com.salonplatform.platform.infrastructure.BillingLedger.Subscription;
import com.salonplatform.platform.infrastructure.PlatformAudit;
import com.salonplatform.shared.infrastructure.PlatformDatabase;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * A billing pass over one locked subscription (docs/architecture/operator-console.md, "Subscriptions,
 * invoices and entitlements"): the invoices whose issue instant has come, their charge attempts, and the
 * reminders whose boundary has come, each with its charge retry. Runs inside the caller's platform
 * transaction; reminders and charge attempts are unique per invoice and occasion in the database, so a
 * repeated or concurrent run adds nothing.
 */
@Service
public class InvoiceIssuing {

    private final PlatformDatabase database;
    private final PlatformAudit audit;
    private final BillingFaults faults;
    private final BillingLedger ledger;
    private final BillingNotices notices;

    public InvoiceIssuing(
            PlatformDatabase database,
            PlatformAudit audit,
            BillingFaults faults,
            BillingLedger ledger,
            BillingNotices notices) {
        this.database = database;
        this.audit = audit;
        this.faults = faults;
        this.ledger = ledger;
        this.notices = notices;
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
        Salon salon = ledger.salon(subscription.salonId(), false).orElseThrow();
        int index = subscription.invoicesIssued();
        Instant periodStart = subscription.nextInvoiceAt();
        Instant periodEnd = BillingCalendar.periodStart(subscription.anchor(), salon.zone(), index + 1);
        Instant due = BillingCalendar.due(periodStart);
        Long number = database.jdbcTemplate()
                .queryForObject(
                        "UPDATE invoice_number SET last_number = last_number + 1 WHERE id RETURNING last_number",
                        Long.class);
        UUID id = UUID.randomUUID();
        Timestamp at = Timestamp.from(now);
        database.jdbcTemplate()
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
        database.jdbcTemplate().update("""
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
        Invoice invoice = ledger.invoiceById(id, false).orElseThrow();
        if (BillingTerms.CARD.equals(subscription.paymentMethod())) {
            requestCharge(salon, invoice, "issue", now, changes);
        }
        return ledger.subscription(subscription.id(), false).orElseThrow();
    }

    /**
     * Records a charge attempt for an invoice and occasion, once, with the outbox message that makes
     * the provider call afterwards. Nothing when that occasion already has an attempt.
     */
    public boolean requestCharge(Salon salon, Invoice invoice, String occasion, Instant now, Changes changes) {
        UUID id = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        Timestamp at = Timestamp.from(now);
        int inserted = database.jdbcTemplate().update("""
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
        ledger.enqueue(salon.id(), BillingLedger.CHARGE, payload, now);
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
        Salon salon = ledger.salon(subscription.salonId(), false).orElseThrow();
        for (Invoice invoice : ledger.openInvoices(subscription.id())) {
            for (Reminder reminder : BillingCalendar.remindersDue(now, invoice.dueAt())) {
                if (notices.notice(salon, invoice, reminder.kind(), null, now)) {
                    changes.reminders().add(BillingTerms.invoiceNumber(invoice.number()) + ":" + reminder.kind());
                }
                if (BillingTerms.CARD.equals(subscription.paymentMethod())) {
                    requestCharge(salon, invoice, reminder.kind(), now, changes);
                }
            }
        }
    }
}
