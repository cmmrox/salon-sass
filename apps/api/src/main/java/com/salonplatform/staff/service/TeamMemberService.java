package com.salonplatform.staff.service;

import com.salonplatform.staff.domain.WorkDay;
import java.util.List;
import java.util.UUID;

/**
 * The salon's team as its staff work with it (STF-01, decision 100). Reading needs {@code team.read}, and every change
 * {@code team.manage}. Who a member's account is, and the list of accounts, are for a caller with {@code staff.list}
 * only.
 */
public interface TeamMemberService {

    /** A person's fields as a request sends them; nothing here has been checked. */
    record MemberInput(
            String name,
            String title,
            String highlights,
            String bio,
            Boolean shownOnline,
            List<UUID> categoryIds,
            List<UUID> serviceIds,
            UUID signInAccountId) {}

    /**
     * A person as a caller may see them.
     *
     * @param signInVisible whether the caller may see the account, with {@code staff.list}
     * @param signInAccountId the linked account, or null for none or when the caller may not see it
     * @param signInLabel that account's label, or null likewise
     * @param weekly their week, the seven weekdays Monday first
     */
    record MemberView(
            UUID id,
            String name,
            String title,
            String highlights,
            String bio,
            boolean shownOnline,
            UUID photoId,
            List<UUID> categoryIds,
            List<UUID> serviceIds,
            boolean signInVisible,
            UUID signInAccountId,
            String signInLabel,
            List<WorkDay> weekly,
            long version) {}

    /**
     * An active account, and the member linked to it.
     *
     * @param memberId the member, or null for none
     */
    record AccountView(UUID id, String label, String role, UUID memberId) {}

    /** Everyone on the team, in name order (at most 200). */
    List<MemberView> list();

    /** A person still on the team. 404 for any other id. */
    MemberView get(UUID id);

    /**
     * Adds a person. A person with no sign-in takes a staff seat; one linked to a free account does not. Their week is
     * copied once from the salon's weekly opening hours, and is not linked to them afterwards (decision 102).
     *
     * @throws com.salonplatform.shared.web.ApplicationProblemException 400 naming each field; 409
     *     {@code limit_reached} at 200 people; 409 {@code entitlement_exceeded} naming {@code staffSeats}
     */
    MemberView add(MemberInput input);

    /**
     * Saves every field of a person, from the version the caller read. Unlinking an account that stays takes a seat.
     *
     * @throws com.salonplatform.shared.web.ApplicationProblemException 400 naming each field; 404; 409 {@code conflict}
     *     for a stale version; 409 {@code invalid_state} when nothing changes; 409 {@code entitlement_exceeded} naming
     *     {@code staffSeats}; 409 {@code in_use} with one {@code services} item per service still booked
     */
    MemberView save(UUID id, MemberInput input, Long version);

    /**
     * Removes a person from the team. Their name stays for past bookings, and their account stays active.
     *
     * @throws com.salonplatform.shared.web.ApplicationProblemException 404; 409 {@code conflict} for a stale version;
     *     409 {@code in_use} with an {@code upcomingBookings} item giving how many
     */
    void remove(UUID id, Long version);

    /** The salon's active accounts, with who each is linked to. Needs {@code team.manage} and {@code staff.list}. */
    List<AccountView> signInAccounts();
}
