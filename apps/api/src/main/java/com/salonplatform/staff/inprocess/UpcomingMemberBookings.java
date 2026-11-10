package com.salonplatform.staff.inprocess;

import java.util.Set;
import java.util.UUID;

/**
 * The questions the team asks about bookings before it removes a person or stops them performing a service (STF-01,
 * decision 100), implemented by {@code booking} (F07-S01), so that dependencies point one way ({@code booking} depends on
 * {@code staff}; decision 087's pattern).
 *
 * <p>Upcoming means a hold that has not run out, a booking waiting for its payment, confirmed, arrived or in service,
 * whose end has not yet passed; cancelled, no-show and completed bookings count for nothing.
 *
 * <p><strong>The window between the answer and the change is closed by {@link #GUARD_LOCK}</strong> (decision 094(f)):
 * a transaction that removes the person or takes services from them first holds the salon's transaction lock on the
 * person ({@code shared.persistence.TransactionLocks}, this purpose and the member's id) and asks again inside it;
 * {@code booking}'s transaction that places a hold for the person holds the same lock and checks again, under it, that
 * the person is still on the team and performs the service. Of the two, the second waits for the first to commit and
 * then sees it, so never both succeed.
 *
 * <p><strong>In-process only</strong> (decision 119): the team asks again inside its own writing transaction, under the
 * lock, so an implementation joins the caller's transaction (a read-only one of its own when there is none) rather
 * than opening a second one, which would hold a second pooled connection while the lock is held. If {@code booking}
 * is ever extracted, the guard becomes an event with a compensating action, not a remote call.
 */
public interface UpcomingMemberBookings {

    /** The purpose of the salon's transaction lock on a team member that both sides of the guard hold. */
    String GUARD_LOCK = "staff.member-bookings";

    /**
     * How many bookings of the request's salon that have not yet taken place, and are not cancelled, are for this
     * member.
     *
     * @throws com.salonplatform.shared.tenancy.TenantNotFoundException when the request names no salon
     */
    long countFor(UUID memberId);

    /**
     * Those of the given services that this member has such a booking for.
     *
     * @throws com.salonplatform.shared.tenancy.TenantNotFoundException when the request names no salon
     */
    Set<UUID> servicesBookedFor(UUID memberId, Set<UUID> serviceIds);
}
