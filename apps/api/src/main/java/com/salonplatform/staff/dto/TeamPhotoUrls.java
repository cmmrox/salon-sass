package com.salonplatform.staff.dto;

import java.util.UUID;

/** Where a team member's photo is served, relative to the salon's hostname; null for none. */
final class TeamPhotoUrls {

    private TeamPhotoUrls() {}

    static String of(UUID photoId) {
        return photoId == null ? null : "/api/v1/team/photos/" + photoId;
    }
}
