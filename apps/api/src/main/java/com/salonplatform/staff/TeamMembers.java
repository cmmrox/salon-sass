package com.salonplatform.staff;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Who is on the salon's team and what they perform: the contract F07 books against and F10 uses for a Professional's
 * "own" data (STF-01, decision 100). The salon is the request's, never a parameter, and another salon's ids are "not
 * found" or absent. A person's services are worked out when read, from the whole categories and single services held
 * for them and what {@code catalogue} lists now. Each call opens its own read-only tenant transaction.
 */
public interface TeamMembers {

    /** A member by id, a removed one included, for their name. Empty for an unknown id or another salon's. */
    Optional<TeamMember> member(UUID memberId);

    /** The members still on the team who perform this service, in name order. */
    List<TeamMember> performing(UUID serviceId);

    /** The ids of the services this member performs now. Empty for a removed member or an unknown id. */
    Set<UUID> servicesOf(UUID memberId);

    /** The member still on the team who is linked to this staff account, if any. */
    Optional<TeamMember> linkedTo(UUID accountId);
}
