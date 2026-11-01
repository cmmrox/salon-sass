package com.salonplatform.staff.service;

import java.util.List;
import java.util.UUID;

/**
 * The team as anyone may read it, with no sign-in (F06-S01 AC-10): the people shown for online booking who perform at
 * least one service bookable online. Never a hidden or removed person, an account or a version.
 */
public interface PublicTeamService {

    /**
     * One person's public profile.
     *
     * @param photoId their photo, or null for none
     * @param serviceIds the services bookable online that they perform
     */
    record PublicMember(
            UUID id, String name, String title, String highlights, String bio, UUID photoId, List<UUID> serviceIds) {}

    /** The request's salon's public team, in name order. */
    List<PublicMember> team();
}
