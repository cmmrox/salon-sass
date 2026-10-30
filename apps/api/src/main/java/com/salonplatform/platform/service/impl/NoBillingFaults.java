package com.salonplatform.platform.service.impl;

import com.salonplatform.platform.service.BillingFaults;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Every environment but {@code local}: the billing job never fails on purpose. */
@Component
@Profile("!local")
class NoBillingFaults implements BillingFaults {

    @Override
    public void reached(Point point) {
        // Nothing is ever armed outside local.
    }
}
