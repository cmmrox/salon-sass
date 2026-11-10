package com.salonplatform.catalogue.inprocess;

import java.util.UUID;

/**
 * The question the catalogue asks about bookings before it deletes a service (CAT-01, decision 093): a service can
 * be deleted only when no upcoming booking uses it; otherwise it can only be hidden. Implemented by {@code booking}
 * (F07-S01), so that dependencies point one way ({@code booking} depends on {@code catalogue}; decision 087's pattern).
 * Upcoming means a hold that has not run out, a booking waiting for its payment, confirmed, arrived or in service,
 * whose end has not yet passed.
 *
 * <p><strong>The window between the answer and the delete is closed by {@link #GUARD_LOCK}</strong> (decision
 * 094(f)): the deleting transaction first holds the salon's transaction lock on the service
 * ({@code shared.persistence.TransactionLocks}, this purpose and the service's id) and asks again inside it;
 * {@code booking}'s transaction that places a hold for the service holds the same lock and checks again, under it, that
 * the service is still listed. Of the two, the second waits for the first to commit and then sees it, so never both
 * succeed.
 *
 * <p><strong>In-process only</strong> (decision 119): the catalogue asks again inside its own writing transaction,
 * under the lock, so an implementation joins the caller's transaction (a read-only one of its own when there is none)
 * rather than holding a second pooled connection while the lock is held.
 */
public interface UpcomingServiceBookings {

    /** The purpose of the salon's transaction lock on a service that both sides of the guard hold. */
    String GUARD_LOCK = "catalogue.service-bookings";

    /**
     * Whether any booking of the request's salon that has not yet taken place, and is not cancelled, uses the
     * service.
     *
     * @throws com.salonplatform.shared.tenancy.TenantNotFoundException when the request names no salon
     */
    boolean anyFor(UUID serviceId);
}
