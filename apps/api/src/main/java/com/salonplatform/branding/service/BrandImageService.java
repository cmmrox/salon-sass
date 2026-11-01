package com.salonplatform.branding.service;

import java.util.UUID;

/**
 * The salon's brand images (F04-S02, decision 086): uploading one, which does not change the draft, and
 * serving one by id.
 */
public interface BrandImageService {

    /** An image's bytes, as kept, with their media type. */
    record Stored(byte[] bytes, String mediaType) {}

    /**
     * Checks an uploaded file by its content, remakes it and keeps it as a new image of the salon.
     *
     * @param kind {@code logo} or {@code cover}
     * @param content the file's bytes
     * @throws com.salonplatform.shared.web.ApplicationProblemException 403 {@code permission_denied}
     *     without {@code settings.manage}; 400 {@code bad_request} on {@code kind} or {@code file}, with
     *     nothing kept
     */
    BrandImageView upload(String kind, byte[] content);

    /**
     * An image of the request's salon: to anyone when the published brand uses it, otherwise only to the
     * salon's settings staff.
     *
     * @throws com.salonplatform.shared.web.NotFoundException when the salon has no such image, or the caller
     *     may not see it; the two cannot be told apart
     */
    Stored read(UUID id);
}
