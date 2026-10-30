package com.salonplatform.platform.service.impl;

import com.salonplatform.platform.dto.BillingJobPass;
import com.salonplatform.platform.dto.SubscriptionPass;
import com.salonplatform.platform.repository.BillingLedger;
import com.salonplatform.platform.repository.PlatformAudit;
import com.salonplatform.platform.service.BillingJobService;
import com.salonplatform.shared.observability.Traces;
import com.salonplatform.shared.persistence.PlatformDatabase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
class BillingJobServiceImpl implements BillingJobService {

    private static final Logger log = LoggerFactory.getLogger(BillingJobServiceImpl.class);

    private final PlatformDatabase database;
    private final BillingLedger ledger;
    private final InvoiceIssuing issuing;
    private final SubscriptionStates states;
    private final Traces traces;

    BillingJobServiceImpl(
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

    @Override
    public BillingJobPass run(Instant now) {
        try {
            return traces.inPlatformSpan("job.billing", () -> pass(now));
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private BillingJobPass pass(Instant now) {
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
        return new BillingJobPass(now, changed, failed);
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
