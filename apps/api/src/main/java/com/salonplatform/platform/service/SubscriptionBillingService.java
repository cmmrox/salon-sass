package com.salonplatform.platform.service;

import com.salonplatform.platform.dto.SalonDetail;
import com.salonplatform.platform.dto.SubscriptionView;
import java.util.UUID;

/**
 * An Admin's subscription actions for one salon (docs/architecture/operator-console.md): read the
 * subscription, start one for an unbilled salon (idempotent; it begins its own 14-day trial), change
 * the monthly amount or payment method (from the next invoice; an issued invoice never changes), and
 * move the salon to another plan, refused while its usage exceeds the new plan's limits. Support
 * operators have none of these permissions.
 */
public interface SubscriptionBillingService {

    /** A start request as received. */
    record StartInput(Long monthlyAmount, String paymentMethod, String currency) {}

    /** A change request as received; absent fields stay as they are. */
    record ChangeInput(Long version, Long monthlyAmount, String paymentMethod) {}

    /** A plan move as received. */
    record PlanMoveInput(Long version, UUID planId) {}

    /** A salon's subscription. */
    SubscriptionView get(UUID salonId);

    /** The result of a start: whether it replayed an earlier one, and the subscription. */
    record Started(boolean replayed, SubscriptionView subscription) {}

    /**
     * Starts the subscription of an unbilled salon. A salon without a currency of its own (a legacy
     * salon) must be given one, stored on the subscription only; a salon with one must not.
     */
    Started start(UUID salonId, String idempotencyKey, StartInput input);

    /** Changes the amount and/or method; a changed amount applies from the next invoice. */
    SubscriptionView change(UUID salonId, ChangeInput input);

    /**
     * Moves the salon to another plan. The salon's registry row is locked, so a staff creation in
     * progress (which share-locks it) finishes first and is counted. The next invoice carries the new
     * plan; issued invoices keep theirs.
     */
    SalonDetail movePlan(UUID salonId, PlanMoveInput input);
}
