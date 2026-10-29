package com.salonplatform.shared.api;

import java.io.Serial;

/**
 * What was asked for does not exist for this salon: answered 404 {@code not_found}, exactly as for
 * a record that does not exist at all, so that another salon's records cannot be probed for.
 */
public class NotFoundException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public NotFoundException() {
        super("Not found", null, false, false);
    }
}
