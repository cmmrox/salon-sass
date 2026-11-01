package com.salonplatform.staff;

import java.util.Set;
import java.util.UUID;

/**
 * The questions the team asks about bookings before it removes a person or stops them performing a service (STF-01,
 * decision 100).
 *
 * <p>Declared here and implemented by {@code booking} in F07, so that dependencies point one way ({@code booking}
 * depends on {@code staff}; decision 087's pattern). Until then {@code staff}'s own implementation answers "none", so
 * nothing is refused; F07 deletes that implementation when {@code booking} provides its own.
 *
 * <p>Both questions are asked before the changing transaction opens, as in decision 094(f), so a booking made between
 * the answer and the commit is not seen: <strong>F07 must close that window</strong>, for example by refusing under a
 * lock or checking again inside the transaction. An implementation reads the request's salon, opens its own
 * transaction and does not join the caller's.
 */
public interface UpcomingMemberBookings {

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
