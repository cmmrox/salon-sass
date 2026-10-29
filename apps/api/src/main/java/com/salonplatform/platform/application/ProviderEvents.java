package com.salonplatform.platform.application;

import com.salonplatform.platform.infrastructure.BillingProviderKey;
import com.salonplatform.platform.infrastructure.PlatformAudit;
import com.salonplatform.shared.api.ApplicationProblemException;
import com.salonplatform.shared.api.ErrorCode;
import com.salonplatform.shared.api.FieldProblem;
import com.salonplatform.shared.api.NotFoundException;
import com.salonplatform.shared.infrastructure.PlatformDatabase;
import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.Timestamp;
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

    @Schema(requiredProperties = {"eventId", "result"})
    public record Acknowledged(
            String eventId,

            @Schema(allowableValues = {"paid", "failed", "already_paid", "unknown", "duplicate"})
            String result) {}

    private record Event(String eventId, UUID invoiceId, UUID attemptKey, String outcome, Instant occurredAt) {}

    private static final Set<String> FIELDS = Set.of("eventId", "invoiceId", "attemptKey", "outcome", "occurredAt");

    private final PlatformDatabase database;
    private final BillingLedger ledger;
    private final BillingProviderKey key;
    private final PlatformAudit audit;
    private final JsonMapper json;
    private final Clock clock;

    public ProviderEvents(
            PlatformDatabase database,
            BillingLedger ledger,
            BillingProviderKey key,
            PlatformAudit audit,
            JsonMapper json,
            Clock clock) {
        this.audit = audit;
        this.database = database;
        this.ledger = ledger;
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
        database.jdbc()
                .queryForObject(
                        "SELECT pg_advisory_xact_lock(hashtextextended(?, 1))::text",
                        String.class,
                        "provider-event|" + event.eventId());
        Integer seen = database.jdbc()
                .queryForObject(
                        "SELECT count(*) FROM provider_event WHERE event_id = ?", Integer.class, event.eventId());
        if (seen != null && seen > 0) {
            return new Acknowledged(event.eventId(), "duplicate");
        }
        Instant now = clock.instant();
        String result = outcome(event, now);
        Timestamp at = Timestamp.from(now);
        database.jdbc()
                .update(
                        """
                INSERT INTO provider_event (event_id, invoice_id, attempt_key, outcome, occurred_at, result, created_at,
                    updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                        event.eventId(),
                        event.invoiceId(),
                        event.attemptKey(),
                        event.outcome(),
                        Timestamp.from(event.occurredAt()),
                        result,
                        at,
                        at);
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
        var attempt = database
                .jdbc()
                .query(
                        "SELECT id, invoice_id, status FROM charge_attempt WHERE attempt_key = ?",
                        (row, n) -> new Object[] {
                            row.getObject("id", UUID.class),
                            row.getObject("invoice_id", UUID.class),
                            row.getString("status")
                        },
                        event.attemptKey())
                .stream()
                .findFirst()
                .orElse(null);
        if (attempt == null || !event.invoiceId().equals(attempt[1])) {
            return "unknown";
        }
        BillingLedger.Invoice found =
                ledger.invoiceById(event.invoiceId(), false).orElse(null);
        if (found == null) {
            return "unknown";
        }
        BillingLedger.Subscription subscription =
                ledger.subscription(found.subscriptionId(), true).orElseThrow();
        BillingLedger.Invoice invoice = ledger.invoiceById(found.id(), true).orElseThrow();
        UUID attemptId = (UUID) attempt[0];
        boolean pending = "requested".equals(attempt[2]);
        if (!invoice.open()) {
            if (pending) {
                settle(attemptId, "success".equals(event.outcome()) ? "succeeded" : "failed", now);
            }
            return "already_paid";
        }
        if ("success".equals(event.outcome())) {
            settle(attemptId, "succeeded", now);
            ledger.pay(
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
        ledger.notice(salon, invoice, "failed_charge", attemptId, now);
        return "failed";
    }

    private void settle(UUID attemptId, String status, Instant now) {
        Timestamp at = Timestamp.from(now);
        database.jdbc().update("""
                UPDATE charge_attempt SET status = ?, settled_at = ?, updated_at = ? WHERE id = ? AND status = 'requested'
                """, status, at, at, attemptId);
    }
}
