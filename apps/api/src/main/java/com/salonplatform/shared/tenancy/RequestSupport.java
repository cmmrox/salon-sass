package com.salonplatform.shared.tenancy;

import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * The support session of the current request, when its credential is a support credential (F03-S04).
 * Bound by the request filter after verification, for the request only; no reused-thread state.
 */
public final class RequestSupport {

    private static final ScopedValue<SupportGrant> VALUE = ScopedValue.newInstance();

    private RequestSupport() {}

    /** The support session behind this request, if the request is made under one. */
    public static Optional<SupportGrant> current() {
        return VALUE.isBound() ? Optional.of(VALUE.get()) : Optional.empty();
    }

    public static <T> T callAs(SupportGrant grant, Callable<T> work) throws Exception {
        return ScopedValue.where(VALUE, grant).call(work::call);
    }
}
