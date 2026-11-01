package com.salonplatform.catalogue.controller;

import com.salonplatform.shared.web.NotFoundException;
import java.util.UUID;

/** An id in a path that is not a UUID names nothing: 404, exactly as an id that names no record. */
final class PathIds {

    private PathIds() {}

    static UUID of(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException ex) {
            throw new NotFoundException();
        }
    }
}
