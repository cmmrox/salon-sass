package com.salonplatform.catalogue.service;

import com.salonplatform.catalogue.service.SalonServices.ServiceView;
import com.salonplatform.shared.images.ImageUpload;
import java.io.IOException;
import java.util.UUID;

/**
 * A service's one photo (CAT-01, decision 093): uploaded and removed with {@code catalogue.price.change}, served to
 * anyone by its id on the salon's hostname.
 */
public interface ServicePhotos {

    /** A photo's bytes, as kept, with their media type. */
    record Stored(byte[] bytes, String mediaType) {}

    /**
     * Checks an uploaded file by its content, remakes it and makes it the service's photo, replacing any earlier
     * one. The caller's permission, the service and the declared size are checked before the file is read (AC-20).
     * 400 on {@code file} with the reason, the service keeping its previous photo.
     *
     * @throws IOException when the file cannot be read
     */
    ServiceView upload(UUID serviceId, ImageUpload upload) throws IOException;

    /** Removes the service's photo. 409 {@code invalid_state} when it has none. */
    ServiceView remove(UUID serviceId);

    /** A photo of the request's salon. 404 for any other id. */
    Stored read(UUID photoId);
}
