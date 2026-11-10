package com.salonplatform.catalogue;

import java.util.List;
import java.util.UUID;

/**
 * The question the catalogue asks before it moves a service to another category (STF-01, F06-S01 AC-8, BUG-F06-002):
 * a person who performs the service's category whole stops performing the service when it leaves that category, and a
 * move may not do that to anyone with an upcoming booking for it, as a team save may not. Implemented by {@code staff},
 * which knows who performs what and asks {@code booking} through its own {@code UpcomingMemberBookings}, so that
 * dependencies point one way ({@code staff} depends on {@code catalogue}; decision 087's pattern).
 *
 * <p>Upcoming means what {@code UpcomingMemberBookings} counts. <strong>The window between the answer and the move is
 * closed by {@link UpcomingServiceBookings#GUARD_LOCK}</strong> (decision 094(f)): the moving transaction holds the
 * salon's transaction lock on the service and asks again inside it; {@code booking}'s transaction that places a hold
 * for the service holds the same lock and checks again, under it, that the person still performs the service. An
 * implementation reads the request's salon, opens its own read-only transactions and does not join the caller's.
 * Who becomes able to perform the service by a move is not asked here.
 */
public interface ServiceMoveBookings {

    /** A team member, by id and by their name as the team lists it. */
    record BookedMember(UUID id, String name) {}

    /**
     * The team members of the request's salon who have an upcoming booking for the service and would no longer perform
     * it once it is in {@code toCategoryId} instead of {@code fromCategoryId}, ordered by name; empty when there are
     * none.
     *
     * @throws com.salonplatform.shared.tenancy.TenantNotFoundException when the request names no salon
     */
    List<BookedMember> losingBookedService(UUID serviceId, UUID fromCategoryId, UUID toCategoryId);
}
