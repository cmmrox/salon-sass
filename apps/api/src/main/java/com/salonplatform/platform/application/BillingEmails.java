package com.salonplatform.platform.application;

import com.salonplatform.platform.domain.BillingTerms;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The billing emails to a salon's Owner contact, as {@code email} outbox payloads: recipient,
 * subject, kind, plain-text body and links. They name the invoice, its amount and due date in the
 * salon's time zone and link to the workspace's Plan and billing page; they never carry card data,
 * a provider secret or a credential. English only for now (the Owner's language is not yet known
 * to billing; notification templates are F11's).
 */
final class BillingEmails {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.ENGLISH);

    private BillingEmails() {}

    static Map<String, Object> compose(
            String kind, String recipient, BillingLedger.Invoice invoice, ZoneId zone, String link) {
        String number = BillingTerms.invoiceNumber(invoice.number());
        String amount = BillingTerms.plainAmount(invoice.amount(), invoice.currency());
        String due = DATE.format(invoice.dueAt().atZone(zone));
        String readOnly = DATE.format(com.salonplatform.platform.domain.BillingCalendar.readOnlyAt(invoice.dueAt())
                .atZone(zone));
        String subject;
        String body;
        switch (kind) {
            case "failed_charge" -> {
                subject = "We could not charge your card for invoice " + number;
                body = "The charge of " + amount + " for invoice " + number + " did not go through. We will try again "
                        + "on each reminder day. The invoice is due on " + due + ".";
            }
            case "due" -> {
                subject = "Invoice " + number + " is due today";
                body = "Invoice " + number + " for " + amount + " is due today, " + due + ". If it stays unpaid, the "
                        + "salon becomes read-only on " + readOnly + ".";
            }
            case "due_3", "due_10" -> {
                subject = "Invoice " + number + " is overdue";
                body = "Invoice " + number + " for " + amount + " was due on " + due + " and is still unpaid. If it "
                        + "stays unpaid, the salon becomes read-only on " + readOnly + ".";
            }
            case "read_only" -> {
                subject = "Your salon is now read-only";
                body = "Invoice " + number + " for " + amount + " was due on " + due + " and is still unpaid, so the "
                        + "salon is read-only: staff can sign in and read, but not make changes. Pay the overdue "
                        + "invoices by card or bank transfer to restore full access.";
            }
            default -> throw new IllegalArgumentException("unknown billing email");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("recipient", recipient);
        payload.put("subject", subject);
        payload.put("kind", "billing." + kind);
        payload.put("body", body);
        payload.put("links", List.of(link));
        return payload;
    }
}
