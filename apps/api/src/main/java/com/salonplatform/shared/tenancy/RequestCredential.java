package com.salonplatform.shared.tenancy;

import java.util.concurrent.Callable;

/** The bearer credential bound only for the current request; no reused-thread state. */
public final class RequestCredential {
    private static final ScopedValue<String> VALUE = ScopedValue.newInstance();

    private RequestCredential() {}

    public static String require() {
        return VALUE.isBound() ? VALUE.get() : "";
    }

    public static <T> T callWith(String credential, Callable<T> work) throws Exception {
        return ScopedValue.where(VALUE, credential == null ? "" : credential).call(work::call);
    }
}
