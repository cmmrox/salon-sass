package com.salonplatform.platform.application;

import com.salonplatform.platform.infrastructure.BillingLedger;
import com.salonplatform.platform.infrastructure.PlatformAudit;
import com.salonplatform.shared.infrastructure.PlatformDatabase;
import com.salonplatform.shared.observability.Traces;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * One pass of the idempotent billing job (docs/architecture/operator-console.md): for every
 * subscription with an invoice to issue or an unpaid invoice, in a platform transaction of its own
 * holding that subscription's lock, it issues the invoices whose time has come, sends the reminders
 * and charge retries whose boundary has come and applies the state the unpaid invoices imply. Every
 * decision compares instants with the application clock, so a late, repeated or concurrent pass
 * neither skips nor repeats a transition. A failure for one subscription rolls back only that
 * subscription's work, which the next pass resumes.
 */
@Service
public class BillingJob {

    private static final Logger log = LoggerFactory.getLogger(BillingJob.class);

    /** What one pass did: per subscription that changed, and the subscriptions that failed. */
    @Schema(hidden = true)
    public record Pass(Instant at, List<SubscriptionPass> changed, List<UUID> failed) {}

    public record SubscriptionPass(
            UUID salonId, List<String> issued, List<String> reminders, List<String> charges, List<String> states) {}

    private final PlatformDatabase database;
    private final BillingLedger ledger;
    private final InvoiceIssuing issuing;
    private final SubscriptionStates states;
    private final Traces traces;

    public BillingJob(
            PlatformDatabase database,
            BillingLedger ledger,
            InvoiceIssuing issuing,
            SubscriptionStates states,
            Traces traces) {
        this.database = database;
        this.ledger = ledger;
        this.issuing = issuing;
        this.states = states;
        this.traces = traces;
    }

    public Pass run(Instant now) {
        try {
            return traces.inPlatformSpan("job.billing", () -> pass(now));
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Pass pass(Instant now) {
        List<UUID> candidates = database.inTransaction(status -> ledger.subscriptionsToBill(now));
        List<SubscriptionPass> changed = new ArrayList<>();
        List<UUID> failed = new ArrayList<>();
        for (UUID id : candidates) {
            try {
                SubscriptionPass result = database.inTransaction(status -> once(id, now));
                if (result != null) {
                    changed.add(result);
                }
            } catch (RuntimeException ex) {
                failed.add(id);
                log.error("The billing job failed for one subscription; the next pass resumes it", ex);
            }
        }
        log.info(
                "Billing pass: {} subscriptions considered, {} changed, {} failed",
                candidates.size(),
                changed.size(),
                failed.size());
        return new Pass(now, changed, failed);
    }

    private SubscriptionPass once(UUID id, Instant now) {
        // The registry row is share-locked before the subscription row, the order a lifecycle action
        // takes them in, so the two never wait on each other.
        BillingLedger.Subscription unlocked = ledger.subscription(id, false).orElse(null);
        if (unlocked == null || !ledger.billed(unlocked.salonId())) {
            // A closed salon's billing has stopped (F03-S03): its open invoices stay as records, and no
            // invoice, reminder or charge follows. A suspended salon is still billed.
            return null;
        }
        BillingLedger.Subscription subscription = ledger.subscription(id, true).orElse(null);
        if (subscription == null) {
            return null;
        }
        BillingChanges changes = BillingChanges.none();
        subscription = issuing.issueDue(subscription, now, changes);
        issuing.remind(subscription, now, changes);
        states.applyState(subscription, now, PlatformAudit.SYSTEM, "billing", changes);
        return changes.any()
                ? new SubscriptionPass(
                        subscription.salonId(),
                        changes.issued(),
                        changes.reminders(),
                        changes.charges(),
                        changes.states())
                : null;
    }
}
