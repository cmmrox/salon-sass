package com.salonplatform.platform.application;

import com.salonplatform.platform.application.BillingLedger.Invoice;
import com.salonplatform.platform.application.BillingLedger.Salon;
import com.salonplatform.platform.domain.BillingTerms;
import com.salonplatform.platform.infrastructure.PlatformAudit;
import com.salonplatform.shared.infrastructure.PlatformDatabase;
import java.sql.Timestamp;
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
public class BillingNotices {

    private final PlatformDatabase database;
    private final PlatformAudit audit;
    private final BillingLedger ledger;
    private final String platformDomain;
    private final String linkOrigin;

    public BillingNotices(
            PlatformDatabase database,
            PlatformAudit audit,
            BillingLedger ledger,
            @Value("${salon.platform-domain}") String platformDomain,
            @Value("${salon.billing.link-origin:https://%s}") String linkOrigin) {
        this.database = database;
        this.audit = audit;
        this.ledger = ledger;
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
        Timestamp at = Timestamp.from(now);
        String conflict = attemptId == null
                ? "(invoice_id, kind) WHERE kind <> 'failed_charge'"
                : "(charge_attempt_id) WHERE kind = 'failed_charge'";
        int inserted = database.jdbcTemplate()
                .update("""
                INSERT INTO billing_reminder (id, invoice_id, kind, charge_attempt_id, message_id, sent_at, created_at,
                    updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT %s DO NOTHING
                """.formatted(conflict), id, invoice.id(), kind, attemptId, message, at, at, at);
        if (inserted == 0) {
            return false;
        }
        if (message != null) {
            ledger.enqueueWithId(
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
