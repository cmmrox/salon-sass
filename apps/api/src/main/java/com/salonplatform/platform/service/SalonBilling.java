package com.salonplatform.platform.service;

import com.salonplatform.identity.Principals;
import com.salonplatform.identity.inprocess.IdentityProvisioning;
import com.salonplatform.platform.dto.Entitlements;
import com.salonplatform.platform.dto.Features;
import com.salonplatform.platform.dto.InvoiceDetail;
import com.salonplatform.platform.dto.InvoicePage;
import com.salonplatform.platform.dto.Limits;
import com.salonplatform.platform.dto.PlanRef;
import com.salonplatform.platform.dto.SubscriptionState;
import com.salonplatform.platform.dto.Usage;
import com.salonplatform.platform.repository.BillingLedger;
import com.salonplatform.platform.repository.InvoiceStore;
import com.salonplatform.shared.persistence.PlatformDatabase;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.shared.web.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * What a salon's staff read about its plan and billing (PLT-04, PLT-05), on the salon's own host and
 * for that salon only: every staff member reads the plan's features and limits, staff-seat usage and
 * the subscription state ({@code entitlement.read}); the Owner alone reads its invoices
 * ({@code invoice.read}). The salon comes from the request's host, never from input; another salon's
 * invoice is not found. The customer principal has neither permission.
 */
@Service
public class SalonBilling {

    /** The trial-ending banner shows from three days before trial end. */
    static final java.time.Duration TRIAL_NOTICE = java.time.Duration.ofDays(3);

    static String notice(String state, Instant trialEndsAt, Instant now) {
        return switch (state) {
            case "read_only" -> "read_only";
            case "past_due" -> "past_due";
            case "trialing" -> now.isBefore(trialEndsAt.minus(TRIAL_NOTICE)) ? null : "trial_ending";
            default -> null;
        };
    }

    private final PlatformDatabase database;
    private final BillingLedger ledger;
    private final InvoiceStore invoiceStore;
    private final SubscriptionStates states;
    private final InvoiceBilling invoices;
    private final Principals principals;
    private final IdentityProvisioning provisioning;
    private final Clock clock;

    public SalonBilling(
            PlatformDatabase database,
            BillingLedger ledger,
            InvoiceStore invoiceStore,
            SubscriptionStates states,
            InvoiceBilling invoices,
            Principals principals,
            IdentityProvisioning provisioning,
            Clock clock) {
        this.database = database;
        this.ledger = ledger;
        this.invoiceStore = invoiceStore;
        this.states = states;
        this.invoices = invoices;
        this.principals = principals;
        this.provisioning = provisioning;
        this.clock = clock;
    }

    public Entitlements entitlements() {
        UUID salonId = TenantContext.require().id();
        principals.require("entitlement.read");
        return database.inTransaction(status -> {
            Instant now = clock.instant();
            var limits = ledger.planOf(salonId)
                    .map(plan -> new Entitlements(
                            new PlanRef(plan.key(), plan.name()),
                            new Limits(plan.staffSeats(), plan.branches(), plan.messageAllowance()),
                            new Features(plan.customDomain(), plan.dedicatedApp()),
                            null,
                            null))
                    .orElse(new Entitlements(null, null, null, null, null));
            SubscriptionState state = ledger.subscriptionOf(salonId, false)
                    .map(subscription -> new SubscriptionState(
                            subscription.state().value(),
                            subscription.trialEndsAt(),
                            states.readOnlyAt(subscription, now),
                            states.pastDueSince(subscription, now),
                            notice(subscription.state().value(), subscription.trialEndsAt(), now)))
                    .orElse(null);
            return new Entitlements(
                    limits.plan(),
                    limits.limits(),
                    limits.features(),
                    new Usage(provisioning.activeStaffCount(salonId)),
                    state);
        });
    }

    public InvoicePage invoices(Paging paging) {
        UUID salonId = TenantContext.require().id();
        principals.require("invoice.read");
        return database.inTransaction(status -> invoices.page(salonId, paging));
    }

    public InvoiceDetail invoice(UUID id) {
        UUID salonId = TenantContext.require().id();
        principals.require("invoice.read");
        return database.inTransaction(status -> invoiceStore
                .invoiceById(id, false)
                .filter(invoice -> invoice.salonId().equals(salonId))
                .map(invoices::detail)
                .orElseThrow(NotFoundException::new));
    }
}
