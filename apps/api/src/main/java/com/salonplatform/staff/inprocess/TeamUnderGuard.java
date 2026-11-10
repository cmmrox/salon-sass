package com.salonplatform.staff.inprocess;

import com.salonplatform.catalogue.CatalogueListing;
import com.salonplatform.staff.TeamMember;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The team as {@code booking} checks it again under {@link UpcomingMemberBookings#GUARD_LOCK}, inside the writing
 * transaction that places a hold or undoes a no-show (decision 094(f)): whether the professional is still on the team
 * and still performs the service. The same answers as {@code staff.TeamMembers}, for the guard.
 *
 * <p><strong>In-process only</strong> (decision 119): the check must be made under the lock, in the caller's
 * transaction, so an implementation joins it, and is given the catalogue's listing, which the caller read under the
 * same lock, rather than asking {@code catalogue} for it inside that transaction. {@code staff.TeamMembers} reads in a
 * transaction of its own and refuses to be called inside a writing one. If {@code staff} is ever extracted, the guard
 * becomes an event with a compensating action, not a remote call.
 */
public interface TeamUnderGuard {

    /**
     * The member of the request's salon, removed or not; empty when there is none.
     *
     * @throws IllegalStateException outside a transaction
     */
    Optional<TeamMember> member(UUID memberId);

    /**
     * The services the member performs today, given the catalogue's listing: none when they were removed or there is
     * no such member.
     *
     * @throws IllegalStateException outside a transaction
     */
    Set<UUID> servicesOf(UUID memberId, CatalogueListing listing);
}
