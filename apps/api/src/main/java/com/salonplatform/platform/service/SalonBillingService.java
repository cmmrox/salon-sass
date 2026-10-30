package com.salonplatform.platform.service;

import com.salonplatform.platform.domain.Paging;
import com.salonplatform.platform.dto.Entitlements;
import com.salonplatform.platform.dto.InvoiceDetail;
import com.salonplatform.platform.dto.InvoicePage;
import java.util.UUID;

/**
 * What a salon's staff read about its plan and billing (PLT-04, PLT-05), on the salon's own host and
 * for that salon only: every staff member reads the plan's features and limits, staff-seat usage and
 * the subscription state ({@code entitlement.read}); the Owner alone reads its invoices
 * ({@code invoice.read}). The salon comes from the request's host, never from input; another salon's
 * invoice is not found. The customer principal has neither permission.
 */
public interface SalonBillingService {

    /** The current salon's plan, limits, usage and subscription state. */
    Entitlements entitlements();

    /** The current salon's invoices, newest first. */
    InvoicePage invoices(Paging paging);

    /** One of the current salon's invoices; another salon's is not found. */
    InvoiceDetail invoice(UUID id);
}
