package com.salonplatform.platform.service;

/**
 * A seam for the interrupted-job faults the local hooks arm (docs/architecture/operator-console.md,
 * "Local test hooks"): the export job reports each table it has stored, the deletion job each batch
 * it has committed, so a one-shot failure can stop either part-way and the next run must resume.
 * Outside {@code local} it never fails.
 */
public interface LifecycleFaults {

    /** After an export run stored its {@code written}-th table (counted from 1 in this run). */
    void exportTableStored(int written);

    /** After a deletion run committed its {@code batches}-th non-empty batch (counted from 1 in this run). */
    void deletionBatchCommitted(int batches);
}
