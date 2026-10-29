package com.salonplatform.shared.tenancy;

import java.io.Serial;

/**
 * A tenant endpoint was called on the hostname of a salon that is suspended. Answered 403
 * {@code salon_suspended}, with nothing of the salon in the body. The salon is never bound for the
 * request, so nothing of its data can be read on the way to this answer.
 */
public class TenantSuspendedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public TenantSuspendedException() {
        super("The request's hostname names a suspended salon", null, false, false);
    }
}
