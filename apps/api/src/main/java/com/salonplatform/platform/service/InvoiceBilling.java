package com.salonplatform.platform.service;

import com.salonplatform.platform.domain.BillingTerms;
import com.salonplatform.platform.domain.OperatorPermission;
import com.salonplatform.platform.domain.PlainText;
import com.salonplatform.platform.repository.BillingLedger;
import com.salonplatform.platform.repository.InvoiceStore;
import com.salonplatform.platform.repository.PlatformAudit;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import com.salonplatform.shared.web.NotFoundException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Invoices (docs/architecture/operator-console.md, "Invoices"): an Admin's paginated list per salon,
 * an invoice's detail with its payment, and a recorded bank transfer against one open invoice with
 * its reference, the exact amount and the received date (not in the future, in the salon's time
 * zone). Recording is idempotent by {@code Idempotency-Key}; an invoice is paid at most once, by a
 * transfer or by a provider event, whichever comes first.
 */
@Service
public class InvoiceBilling {

    static final String RECORD = "payment.record";

    @Schema(
            requiredProperties = {
                "id",
                "number",
                "periodStart",
                "periodEnd",
                "amount",
                "currency",
                "status",
                "issuedAt",
                "dueAt",
                "paidAt"
            })
    public record InvoiceSummary(
            UUID id,
            String number,
            Instant periodStart,
            Instant periodEnd,
            long amount,
            String currency,
            @Schema(allowableValues = {"open", "paid"}) String status,
            Instant issuedAt,
            Instant dueAt,

            @Schema(
                    types = {"string", "null"},
                    format = "date-time",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            Instant paidAt) {}

    @Schema(requiredProperties = {"items", "page", "size", "total"})
    public record InvoicePage(List<InvoiceSummary> items, int page, int size, long total) {}

    @Schema(requiredProperties = {"id", "method", "reference", "providerEventId", "amount", "receivedOn", "paidAt"})
    public record PaymentView(
            UUID id,

            @Schema(allowableValues = {"card", "bank_transfer"})
            String method,

            @Schema(
                    types = {"string", "null"},
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String reference,

            @Schema(
                    types = {"string", "null"},
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String providerEventId,

            long amount,

            @Schema(
                    types = {"string", "null"},
                    format = "date",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            LocalDate receivedOn,

            Instant paidAt) {}

    @Schema(
            requiredProperties = {
                "id",
                "number",
                "salonId",
                "planKey",
                "planName",
                "periodStart",
                "periodEnd",
                "amount",
                "currency",
                "status",
                "issuedAt",
                "dueAt",
                "paidAt",
                "payments"
            })
    public record InvoiceDetail(
            UUID id,
            String number,
            UUID salonId,

            @Schema(
                    types = {"string", "null"},
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String planKey,

            @Schema(
                    types = {"string", "null"},
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String planName,

            Instant periodStart,
            Instant periodEnd,
            long amount,
            String currency,
            @Schema(allowableValues = {"open", "paid"}) String status,
            Instant issuedAt,
            Instant dueAt,

            @Schema(
                    types = {"string", "null"},
                    format = "date-time",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            Instant paidAt,

            List<PaymentView> payments) {}

    /** A transfer as received. */
    public record TransferInput(String reference, Long amount, String receivedOn) {}

    /** The result of recording: whether it replayed an earlier one, and the payment. */
    public record Recorded(boolean replayed, PaymentView payment) {}

    private final OperatorWork work;
    private final OperatorAccess access;
    private final ProvisioningReceipts receipts;
    private final BillingLedger ledger;
    private final InvoiceStore invoices;
    private final SubscriptionStates states;
    private final JsonMapper json;
    private final Clock clock;

    public InvoiceBilling(
            OperatorWork work,
            OperatorAccess access,
            ProvisioningReceipts receipts,
            BillingLedger ledger,
            InvoiceStore invoices,
            SubscriptionStates states,
            JsonMapper json,
            Clock clock) {
        this.work = work;
        this.access = access;
        this.receipts = receipts;
        this.ledger = ledger;
        this.invoices = invoices;
        this.states = states;
        this.json = json;
        this.clock = clock;
    }

    static InvoiceSummary summary(InvoiceStore.Invoice invoice) {
        return new InvoiceSummary(
                invoice.id(),
                BillingTerms.invoiceNumber(invoice.number()),
                invoice.periodStart(),
                invoice.periodEnd(),
                invoice.amount(),
                invoice.currency(),
                invoice.status(),
                invoice.issuedAt(),
                invoice.dueAt(),
                invoice.paidAt());
    }

    static PaymentView payment(InvoiceStore.Payment payment) {
        return new PaymentView(
                payment.id(),
                payment.method(),
                payment.reference(),
                payment.providerEventId(),
                payment.amount(),
                payment.receivedOn(),
                payment.paidAt());
    }

    InvoiceDetail detail(InvoiceStore.Invoice invoice) {
        return new InvoiceDetail(
                invoice.id(),
                BillingTerms.invoiceNumber(invoice.number()),
                invoice.salonId(),
                invoice.planKey(),
                invoice.planName(),
                invoice.periodStart(),
                invoice.periodEnd(),
                invoice.amount(),
                invoice.currency(),
                invoice.status(),
                invoice.issuedAt(),
                invoice.dueAt(),
                invoice.paidAt(),
                invoices.payments(invoice.id()).stream()
                        .map(InvoiceBilling::payment)
                        .toList());
    }

    /** A salon's invoices, newest first, inside a platform transaction. */
    InvoicePage page(UUID salonId, Paging paging) {
        List<InvoiceSummary> items = invoices.invoicesOf(salonId, paging.size(), paging.offset()).stream()
                .map(InvoiceBilling::summary)
                .toList();
        long total = invoices.countInvoicesOf(salonId);
        return new InvoicePage(items, paging.page(), paging.size(), total);
    }

    public InvoicePage list(UUID salonId, Paging paging) {
        return work.run(() -> {
            access.require(OperatorPermission.BILLING_READ);
            ledger.salon(salonId, false).orElseThrow(NotFoundException::new);
            return page(salonId, paging);
        });
    }

    public InvoiceDetail get(UUID invoiceId) {
        return work.run(() -> {
            access.require(OperatorPermission.BILLING_READ);
            return detail(invoices.invoiceById(invoiceId, false).orElseThrow(NotFoundException::new));
        });
    }

    /**
     * Records a bank transfer. Authorization, the key and the input's form come first; then a replay of
     * the same key and input answers the original payment; then the invoice must exist (404), be open
     * (409 {@code invalid_state}), match the amount exactly and have been received by today (400).
     */
    public Recorded recordTransfer(UUID invoiceId, String idempotencyKey, TransferInput input) {
        access.check(OperatorPermission.BILLING_MANAGE);
        UUID key = ProvisioningReceipts.key(idempotencyKey);
        List<FieldProblem> errors = new ArrayList<>();
        String reference =
                input.reference() == null ? null : PlainText.line(input.reference(), BillingTerms.REFERENCE_MAX);
        if (reference == null) {
            errors.add(new FieldProblem("reference", "must be plain text between 1 and 120 characters"));
        }
        if (input.amount() == null || input.amount() < 0 || input.amount() > BillingTerms.MAX_AMOUNT) {
            errors.add(new FieldProblem("amount", "must be the invoice's exact amount in minor units"));
        }
        LocalDate received = null;
        try {
            if (input.receivedOn() != null && input.receivedOn().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                received = LocalDate.parse(input.receivedOn());
            }
        } catch (DateTimeParseException ex) {
            received = null;
        }
        if (received == null) {
            errors.add(new FieldProblem("receivedOn", "must be a date written YYYY-MM-DD"));
        }
        if (!errors.isEmpty()) {
            throw new ApplicationProblemException(400, ErrorCode.BAD_REQUEST, errors);
        }
        LocalDate receivedOn = received;
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("invoiceId", invoiceId.toString());
        request.put("reference", reference);
        request.put("amount", input.amount());
        request.put("receivedOn", receivedOn.toString());
        String fingerprint = receipts.fingerprint(RECORD, request);
        return work.run(() -> {
            var operator = access.require(OperatorPermission.BILLING_MANAGE);
            var earlier = receipts.replay(operator.id(), RECORD, key, fingerprint);
            if (earlier.isPresent()) {
                return new Recorded(true, json.convertValue(earlier.get().result(), PaymentView.class));
            }
            InvoiceStore.Invoice found = invoices.invoiceById(invoiceId, false).orElseThrow(NotFoundException::new);
            // The subscription's lock first, as the job and provider events take it, then the invoice's.
            BillingLedger.Subscription subscription =
                    ledger.subscription(found.subscriptionId(), true).orElseThrow();
            InvoiceStore.Invoice invoice = invoices.invoiceById(invoiceId, true).orElseThrow();
            if (!invoice.open()) {
                throw new ApplicationProblemException(409, ErrorCode.INVALID_STATE);
            }
            Instant now = clock.instant();
            BillingLedger.Salon salon = ledger.salon(invoice.salonId(), false).orElseThrow();
            List<FieldProblem> refused = new ArrayList<>();
            if (input.amount() != invoice.amount()) {
                refused.add(new FieldProblem("amount", "must be the invoice's exact amount"));
            }
            if (receivedOn.isAfter(now.atZone(salon.zone()).toLocalDate())) {
                refused.add(new FieldProblem("receivedOn", "must not be in the future"));
            }
            if (!refused.isEmpty()) {
                throw new ApplicationProblemException(400, ErrorCode.BAD_REQUEST, refused);
            }
            InvoiceStore.Payment payment = states.pay(
                    subscription,
                    invoice,
                    BillingTerms.BANK_TRANSFER,
                    reference,
                    null,
                    receivedOn,
                    operator.id(),
                    now,
                    PlatformAudit.OPERATOR,
                    operator.id().toString());
            PaymentView view = payment(payment);
            // Jackson converts to a raw Map; a view is a JSON object, so every key is a string.
            @SuppressWarnings("unchecked")
            Map<String, Object> stored = json.convertValue(view, Map.class);
            receipts.store(operator.id(), RECORD, key, fingerprint, payment.id(), stored, now);
            return new Recorded(false, view);
        });
    }
}
