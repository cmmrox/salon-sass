package com.salonplatform.platform.local;

import com.salonplatform.platform.service.BillingFaults;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** {@code local} only: a one-shot failure of the billing job at a named point, armed by the hook. */
@Component
@Profile("local")
class LocalBillingFaults implements BillingFaults {

    private final AtomicReference<Point> armed = new AtomicReference<>();

    void arm(Point point) {
        armed.set(point);
    }

    Point current() {
        return armed.get();
    }

    @Override
    public void reached(Point point) {
        if (armed.compareAndSet(point, null)) {
            throw new IllegalStateException("Controlled local billing job interruption at " + point);
        }
    }
}
