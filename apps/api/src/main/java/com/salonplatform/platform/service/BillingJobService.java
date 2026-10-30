package com.salonplatform.platform.service;

import com.salonplatform.platform.dto.BillingJobPass;
import java.time.Instant;

/**
 * One pass of the idempotent billing job (docs/architecture/operator-console.md): for every
 * subscription with an invoice to issue or an unpaid invoice, in a platform transaction of its own
 * holding that subscription's lock, it issues the invoices whose time has come, sends the reminders
 * and charge retries whose boundary has come and applies the state the unpaid invoices imply. Every
 * decision compares instants with the application clock, so a late, repeated or concurrent pass
 * neither skips nor repeats a transition. A failure for one subscription rolls back only that
 * subscription's work, which the next pass resumes.
 */
public interface BillingJobService {

    /** Runs one billing pass at the given time and reports what it changed. */
    BillingJobPass run(Instant now);
}
