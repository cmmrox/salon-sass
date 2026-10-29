package com.salonplatform.platform.service;

/**
 * A seam for the interrupted-job fault the local hooks arm (docs/architecture/operator-console.md,
 * "Local test hooks"): called at named points of the billing job, inside its transaction. Outside
 * {@code local} it never fails.
 */
public interface BillingFaults {

    /** The points a one-shot failure can be armed at. */
    enum Point {
        /** After an invoice is inserted and before its charge attempt is recorded. */
        AFTER_INVOICE_ISSUE
    }

    void reached(Point point);
}
