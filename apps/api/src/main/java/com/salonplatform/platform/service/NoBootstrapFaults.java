package com.salonplatform.platform.service;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Every environment but {@code local}: provisioning never fails on purpose. */
@Component
@Profile("!local")
class NoBootstrapFaults implements BootstrapFaults {

    @Override
    public void reached(Step step) {
        // Nothing is ever set outside local.
    }
}
