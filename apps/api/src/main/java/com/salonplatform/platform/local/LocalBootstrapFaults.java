package com.salonplatform.platform.local;

import com.salonplatform.platform.service.BootstrapFaults;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** {@code local} only: the controlled bootstrap failure the fixture hook sets, for rollback checks. */
@Component
@Profile("local")
class LocalBootstrapFaults implements BootstrapFaults {

    private final AtomicReference<Step> failAt = new AtomicReference<>();

    void set(Step step) {
        failAt.set(step);
    }

    Step current() {
        return failAt.get();
    }

    @Override
    public void reached(Step step) {
        if (step == failAt.get()) {
            throw new IllegalStateException("Controlled local bootstrap failure at " + step);
        }
    }
}
