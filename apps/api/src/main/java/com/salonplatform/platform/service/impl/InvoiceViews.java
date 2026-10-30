package com.salonplatform.platform.service.impl;

import com.salonplatform.platform.domain.BillingTerms;
import com.salonplatform.platform.domain.Paging;
import com.salonplatform.platform.dto.InvoiceDetail;
import com.salonplatform.platform.dto.InvoicePage;
import com.salonplatform.platform.dto.InvoiceSummary;
import com.salonplatform.platform.dto.PaymentView;
import com.salonplatform.platform.repository.InvoiceStore;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The views of invoices and their payments that the operator and the salon read alike. */
@Component
class InvoiceViews {

    private final InvoiceStore invoices;

    InvoiceViews(InvoiceStore invoices) {
        this.invoices = invoices;
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
                        .map(InvoiceViews::payment)
                        .toList());
    }

    /** A salon's invoices, newest first, inside a platform transaction. */
    InvoicePage page(UUID salonId, Paging paging) {
        List<InvoiceSummary> items = invoices.invoicesOf(salonId, paging.size(), paging.offset()).stream()
                .map(InvoiceViews::summary)
                .toList();
        long total = invoices.countInvoicesOf(salonId);
        return new InvoicePage(items, paging.page(), paging.size(), total);
    }
}
