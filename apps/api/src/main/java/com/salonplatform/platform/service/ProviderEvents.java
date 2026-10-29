package com.salonplatform.platform.service;

import com.salonplatform.platform.adapter.BillingProviderKey;
import com.salonplatform.platform.dto.Acknowledged;
import com.salonplatform.platform.repository.BillingLedger;
import com.salonplatform.platform.repository.InvoiceStore;
import com.salonplatform.platform.repository.InvoiceStore.ChargeAttempt;
import com.salonplatform.platform.repository.InvoiceStore.ProviderEvent;
import com.salonplatform.platform.repository.PlatformAudit;
import com.salonplatform.shared.persistence.PlatformDatabase;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import com.salonplatform.shared.web.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The billing provider's signed events (docs/architecture/operator-console.md, "Payment"): JSON
 * {@code {eventId,invoiceId,attemptKey,outcome,occurredAt}} with an HMAC-SHA256 of the exact body.
 * An unsigned or wrongly signed event is refused 401 {@code invalid_credentials} before it is read,
 * with no effect. A repeated event id is acknowledged without effect; an event for an unknown attempt
 * or invoice, or for an invoice already paid (a late success after a manual payment), is recorded
 * without changing it. A success pays the invoice once; a failure sends one failed-charge notice per
 * attempt. Events for one id are serialized, and the payment is unique per invoice in the database.
 */
@Service
public class ProviderEvents {

    private record Event(String eventId, UUID invoiceId, UUID attemptKey, String outcome, Instant occurredAt) {}

    private static final Set<String> FIELDS = Set.of("eventId", "invoiceId", "attemptKey", "outcome", "occurredAt");

    private final PlatformDatabase database;
    private final BillingLedger ledger;
    private final InvoiceStore invoices;
    private final BillingNotices notices;
    private final SubscriptionStates states;
    private final BillingProviderKey key;
    private final PlatformAudit audit;
    private final JsonMapper json;
    private final Clock clock;

    public ProviderEvents(
            PlatformDatabase database,
            BillingLedger ledger,
            InvoiceStore invoices,
            BillingNotices notices,
            SubscriptionStates states,
            BillingProviderKey key,
            PlatformAudit audit,
            JsonMapper json,
            Clock clock) {
        this.audit = audit;
        this.database = database;
        this.ledger = ledger;
        this.invoices = invoices;
        this.notices = notices;
        this.states = states;
        this.key = key;
        this.json = json;
        this.clock = clock;
    }

    public Acknowledged receive(byte[] body, String signature) {
        if (!key.configured()) {
            throw new NotFoundException();
        }
        if (!key.verify(body, signature)) {
            throw new ApplicationProblemException(401, ErrorCode.INVALID_CREDENTIALS);
        }
        Event event = parse(body);
        return database.inTransaction(status -> apply(event));
    }

    private Event parse(byte[] body) {
        JsonNode node;
        try {
            node = json.readTree(body);
        } catch (RuntimeException ex) {
            throw new ApplicationProblemException(400, ErrorCode.BAD_REQUEST);
        }
        if (node == null || !node.isObject()) {
            throw new ApplicationProblemException(400, ErrorCode.BAD_REQUEST);
        }
        List<FieldProblem> errors = new ArrayList<>();
        for (String name : node.propertyNames()) {
            if (!FIELDS.contains(name)) {
                errors.add(new FieldProblem(name, "is not allowed"));
            }
        }
        String eventId = text(node, "eventId");
        if (eventId == null || !eventId.matches("[A-Za-z0-9_.:-]{1,100}")) {
            errors.add(new FieldProblem("eventId", "must be 1 to 100 letters, digits or _.:-"));
        }
        UUID invoiceId = uuid(node, "invoiceId", errors);
        UUID attemptKey = uuid(node, "attemptKey", errors);
        String outcome = text(node, "outcome");
        if (!"success".equals(outcome) && !"failure".equals(outcome)) {
            errors.add(new FieldProblem("outcome", "must be success or failure"));
        }
        Instant occurredAt = null;
        try {
            String value = text(node, "occurredAt");
            occurredAt = value == null ? null : Instant.parse(value);
        } catch (DateTimeParseException ex) {
            occurredAt = null;
        }
        if (occurredAt == null) {
            errors.add(new FieldProblem("occurredAt", "must be an ISO-8601 instant"));
        }
        if (!errors.isEmpty()) {
            errors.sort(java.util.Comparator.comparing(FieldProblem::field));
            throw new ApplicationProblemException(400, ErrorCode.BAD_REQUEST, errors);
        }
        return new Event(eventId, invoiceId, attemptKey, outcome, occurredAt);
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value != null && value.isString() ? value.asString() : null;
    }

    private static UUID uuid(JsonNode node, String name, List<FieldProblem> errors) {
        String value = text(node, name);
        if (value != null
                && value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            return UUID.fromString(value);
        }
        errors.add(new FieldProblem(name, "must be a UUID"));
        return null;
    }

    private Acknowledged apply(Event event) {
        invoices.lockProviderEvent(event.eventId());
        if (invoices.providerEventSeen(event.eventId())) {
            return new Acknowledged(event.eventId(), "duplicate");
        }
        Instant now = clock.instant();
        String result = outcome(event, now);
        invoices.recordProviderEvent(
                new ProviderEvent(
                        event.eventId(),
                        event.invoiceId(),
                        event.attemptKey(),
                        event.outcome(),
                        event.occurredAt(),
                        result),
                now);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("eventId", event.eventId());
        details.put("outcome", event.outcome());
        details.put("result", result);
        audit.record(
                PlatformAudit.SYSTEM,
                "billing-provider",
                "provider_event.received",
                "invoice",
                event.invoiceId().toString(),
                details,
                now);
        return new Acknowledged(event.eventId(), result);
    }

    private String outcome(Event event, Instant now) {
        ChargeAttempt attempt = invoices.chargeAttempt(event.attemptKey()).orElse(null);
        if (attempt == null || !event.invoiceId().equals(attempt.invoiceId())) {
            return "unknown";
        }
        InvoiceStore.Invoice found =
                invoices.invoiceById(event.invoiceId(), false).orElse(null);
        if (found == null) {
            return "unknown";
        }
        BillingLedger.Subscription subscription =
                ledger.subscription(found.subscriptionId(), true).orElseThrow();
        InvoiceStore.Invoice invoice = invoices.invoiceById(found.id(), true).orElseThrow();
        UUID attemptId = attempt.id();
        boolean pending = "requested".equals(attempt.status());
        if (!invoice.open()) {
            if (pending) {
                settle(attemptId, "success".equals(event.outcome()) ? "succeeded" : "failed", now);
            }
            return "already_paid";
        }
        if ("success".equals(event.outcome())) {
            settle(attemptId, "succeeded", now);
            states.pay(
                    subscription,
                    invoice,
                    "card",
                    null,
                    event.eventId(),
                    null,
                    null,
                    now,
                    PlatformAudit.SYSTEM,
                    "billing-provider");
            return "paid";
        }
        if (pending) {
            settle(attemptId, "failed", now);
            audit.record(
                    PlatformAudit.SYSTEM,
                    "billing-provider",
                    "charge.failed",
                    "invoice",
                    invoice.id().toString(),
                    Map.of("attemptId", attemptId.toString()),
                    now);
        }
        BillingLedger.Salon salon = ledger.salon(invoice.salonId(), false).orElseThrow();
        notices.notice(salon, invoice, "failed_charge", attemptId, now);
        return "failed";
    }

    private void settle(UUID attemptId, String status, Instant now) {
        invoices.settleCharge(attemptId, status, now);
    }
}
