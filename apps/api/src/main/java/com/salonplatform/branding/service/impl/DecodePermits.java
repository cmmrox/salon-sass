package com.salonplatform.branding.service.impl;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Branding's own bound on the brand image decodes running at once on this instance
 * ({@code salon.branding.images.concurrent-decodes}), through the shared permits (decision 093): a decode beyond
 * it waits briefly and is then refused 503 {@code service_unavailable}.
 */
final class DecodePermits {

    private final com.salonplatform.shared.images.DecodePermits permits;

    DecodePermits(int concurrent, Duration wait) {
        this.permits = new com.salonplatform.shared.images.DecodePermits(concurrent, wait);
    }

    <T> T run(Supplier<T> decode) {
        return permits.run(decode);
    }
}
