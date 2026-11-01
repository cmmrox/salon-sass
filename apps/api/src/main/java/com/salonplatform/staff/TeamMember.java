package com.salonplatform.staff;

import java.util.UUID;

/**
 * A team member, as another module refers to one (F06-S01).
 *
 * @param id the member
 * @param name their name, kept after they are removed so that past bookings keep it
 * @param removed whether they have been removed from the team
 * @param shownOnline whether customers can see them for online booking
 */
public record TeamMember(UUID id, String name, boolean removed, boolean shownOnline) {}
