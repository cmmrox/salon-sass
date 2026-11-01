package com.salonplatform.branding.service.impl;

import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Bounds the image decodes running at once on this instance (security review, F04-S02): each can hold up to
 * about 64 MB, so simultaneous uploads must not multiply that without limit. A decode waits briefly for a
 * permit and is otherwise refused 503 {@code service_unavailable}, which a client may try again.
 */
final class DecodePermits {

    private final Semaphore permits;
    private final Duration wait;

    DecodePermits(int concurrent, Duration wait) {
        this.permits = new Semaphore(concurrent, true);
        this.wait = wait;
    }

    <T> T run(Supplier<T> decode) {
        boolean permitted;
        try {
            permitted = permits.tryAcquire(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            permitted = false;
        }
        if (!permitted) {
            throw new ApplicationProblemException(503, ErrorCode.SERVICE_UNAVAILABLE);
        }
        try {
            return decode.get();
        } finally {
            permits.release();
        }
    }
}
