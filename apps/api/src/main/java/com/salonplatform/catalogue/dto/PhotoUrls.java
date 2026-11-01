package com.salonplatform.catalogue.dto;

import java.util.UUID;

/** Where a service photo is served, relative to the salon's hostname; null for none. */
final class PhotoUrls {

    private PhotoUrls() {}

    static String of(UUID photoId) {
        return photoId == null ? null : "/api/v1/catalogue/photos/" + photoId;
    }
}
