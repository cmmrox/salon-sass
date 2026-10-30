package com.salonplatform.platform.service.impl;

import com.salonplatform.platform.domain.BillingTerms;
import com.salonplatform.platform.domain.IdempotencyKey;
import com.salonplatform.platform.domain.OperatorPermission;
import com.salonplatform.platform.domain.Paging;
import com.salonplatform.platform.domain.PlainText;
import com.salonplatform.platform.dto.InvoiceDetail;
import com.salonplatform.platform.dto.InvoicePage;
import com.salonplatform.platform.dto.PaymentView;
import com.salonplatform.platform.repository.BillingLedger;
import com.salonplatform.platform.repository.InvoiceStore;
import com.salonplatform.platform.repository.PlatformAudit;
import com.salonplatform.platform.service.InvoiceBillingService;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import com.salonplatform.shared.web.NotFoundException;
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

@Service
class InvoiceBillingServiceImpl implements InvoiceBillingService {

    static final String RECORD = "payment.record";

    private final OperatorWork work;
    private final OperatorAccess access;
    private final ProvisioningReceipts receipts;
    private final BillingLedger ledger;
    private final InvoiceStore invoices;
    private final InvoiceViews views;
    private final SubscriptionStates states;
    private final JsonMapper json;
    private final Clock clock;

    InvoiceBillingServiceImpl(
            OperatorWork work,
            OperatorAccess access,
            ProvisioningReceipts receipts,
            BillingLedger ledger,
            InvoiceStore invoices,
            InvoiceViews views,
            SubscriptionStates states,
            JsonMapper json,
            Clock clock) {
        this.work = work;
        this.access = access;
        this.receipts = receipts;
        this.ledger = ledger;
        this.invoices = invoices;
        this.views = views;
        this.states = states;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public InvoicePage list(UUID salonId, Paging paging) {
        return work.run(() -> {
            access.require(OperatorPermission.BILLING_READ);
            ledger.salon(salonId, false).orElseThrow(NotFoundException::new);
            return views.page(salonId, paging);
        });
    }

    @Override
    public InvoiceDetail get(UUID invoiceId) {
        return work.run(() -> {
            access.require(OperatorPermission.BILLING_READ);
            return views.detail(invoices.invoiceById(invoiceId, false).orElseThrow(NotFoundException::new));
        });
    }

    @Override
    public Recorded recordTransfer(UUID invoiceId, String idempotencyKey, TransferInput input) {
        access.check(OperatorPermission.BILLING_MANAGE);
        UUID key = IdempotencyKey.parse(idempotencyKey);
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
            PaymentView view = InvoiceViews.payment(payment);
            // Jackson converts to a raw Map; a view is a JSON object, so every key is a string.
            @SuppressWarnings("unchecked")
            Map<String, Object> stored = json.convertValue(view, Map.class);
            receipts.store(operator.id(), RECORD, key, fingerprint, payment.id(), stored, now);
            return new Recorded(false, view);
        });
    }
}
