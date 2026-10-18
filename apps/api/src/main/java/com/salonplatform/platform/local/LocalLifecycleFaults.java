package com.salonplatform.platform.local;

import java.util.concurrent.atomic.AtomicReference;

import com.salonplatform.platform.application.LifecycleFaults;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * {@code local} only: one-shot failures of the export job after N stored tables and of the deletion job
 * after N committed batches, armed by the hook. Each fires once, in the next run that reaches it.
 */
@Component
@Profile("local")
class LocalLifecycleFaults implements LifecycleFaults {

    private final AtomicReference<Integer> exportAfter = new AtomicReference<>();
    private final AtomicReference<Integer> deletionAfter = new AtomicReference<>();

    void armExport(Integer tables) {
        exportAfter.set(tables);
    }

    void armDeletion(Integer batches) {
        deletionAfter.set(batches);
    }

    Integer export() {
        return exportAfter.get();
    }

    Integer deletion() {
        return deletionAfter.get();
    }

    @Override
    public void exportTableStored(int written) {
        Integer armed = exportAfter.get();
        if (armed != null && armed == written && exportAfter.compareAndSet(armed, null)) {
            throw new IllegalStateException("Controlled local export interruption after " + written + " tables");
        }
    }

    @Override
    public void deletionBatchCommitted(int batches) {
        Integer armed = deletionAfter.get();
        if (armed != null && armed == batches && deletionAfter.compareAndSet(armed, null)) {
            throw new IllegalStateException("Controlled local deletion interruption after " + batches + " batches");
        }
    }
}
