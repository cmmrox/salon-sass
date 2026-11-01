package com.salonplatform.catalogue.service;

import com.salonplatform.catalogue.service.SalonServices.ServiceView;
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
     * one. 400 on {@code file} with the reason, the service keeping its previous photo.
     */
    ServiceView upload(UUID serviceId, byte[] content);

    /** Removes the service's photo. 409 {@code invalid_state} when it has none. */
    ServiceView remove(UUID serviceId);

    /** A photo of the request's salon. 404 for any other id. */
    Stored read(UUID photoId);
}
