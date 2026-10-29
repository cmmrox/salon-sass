package com.salonplatform.platform.service;

import com.salonplatform.platform.domain.BillingTerms;
import com.salonplatform.platform.repository.BillingLedger;
import com.salonplatform.platform.repository.BillingLedger.Salon;
import com.salonplatform.platform.repository.InvoiceStore;
import com.salonplatform.platform.repository.InvoiceStore.Invoice;
import com.salonplatform.platform.repository.PlatformAudit;
import com.salonplatform.platform.repository.PlatformOutbox;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Billing's reminders and notices to a salon's Owner contact (docs/architecture/operator-console.md,
 * "Subscriptions, invoices and entitlements"): each recorded once per invoice and kind, with its email
 * written to the outbox in the caller's platform transaction.
 */
@Service
class BillingNotices {

    private final InvoiceStore invoices;
    private final PlatformAudit audit;
    private final PlatformOutbox outbox;
    private final String platformDomain;
    private final String linkOrigin;

    public BillingNotices(
            InvoiceStore invoices,
            PlatformAudit audit,
            PlatformOutbox outbox,
            @Value("${salon.platform-domain}") String platformDomain,
            @Value("${salon.billing.link-origin:https://%s}") String linkOrigin) {
        this.invoices = invoices;
        this.audit = audit;
        this.outbox = outbox;
        this.platformDomain = platformDomain.strip().toLowerCase(Locale.ROOT);
        this.linkOrigin = linkOrigin;
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
        boolean recorded = invoices.recordReminder(id, invoice.id(), kind, attemptId, message, now);
        if (!recorded) {
            return false;
        }
        if (message != null) {
            outbox.enqueueWithId(
                    salon.id(),
                    message,
                    BillingLedger.EMAIL,
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
}
