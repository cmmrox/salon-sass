package com.salonplatform.platform.service;

import com.salonplatform.platform.domain.Paging;
import com.salonplatform.platform.dto.InvoiceDetail;
import com.salonplatform.platform.dto.InvoicePage;
import com.salonplatform.platform.dto.PaymentView;
import java.util.UUID;

/**
 * Invoices (docs/architecture/operator-console.md, "Invoices"): an Admin's paginated list per salon,
 * an invoice's detail with its payment, and a recorded bank transfer against one open invoice with
 * its reference, the exact amount and the received date (not in the future, in the salon's time
 * zone). Recording is idempotent by {@code Idempotency-Key}; an invoice is paid at most once, by a
 * transfer or by a provider event, whichever comes first.
 */
public interface InvoiceBillingService {

    /** A transfer as received. */
    record TransferInput(String reference, Long amount, String receivedOn) {}

    /** The result of recording: whether it replayed an earlier one, and the payment. */
    record Recorded(boolean replayed, PaymentView payment) {}

    /** A salon's invoices, newest first. */
    InvoicePage list(UUID salonId, Paging paging);

    /** One invoice with its payments. */
    InvoiceDetail get(UUID invoiceId);

    /**
     * Records a bank transfer. Authorization, the key and the input's form come first; then a replay of
     * the same key and input answers the original payment; then the invoice must exist (404), be open
     * (409 {@code invalid_state}), match the amount exactly and have been received by today (400).
     */
    Recorded recordTransfer(UUID invoiceId, String idempotencyKey, TransferInput input);
}
