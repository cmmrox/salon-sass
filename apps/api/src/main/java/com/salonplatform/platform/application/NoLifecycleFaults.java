package com.salonplatform.platform.application;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Every environment but {@code local}: the export and deletion jobs never fail on purpose. */
@Component
@Profile("!local")
class NoLifecycleFaults implements LifecycleFaults {

    @Override
    public void exportTableStored(int written) {
        // Nothing is ever armed outside local.
    }

    @Override
    public void deletionBatchCommitted(int batches) {
        // Nothing is ever armed outside local.
    }
}
