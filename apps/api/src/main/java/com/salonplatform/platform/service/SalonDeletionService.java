package com.salonplatform.platform.service;

import com.salonplatform.platform.dto.DeletionJobPass;
import java.time.Instant;

/**
 * The retention job (PLT-06; docs/architecture/operator-console.md, "Retention"): once a closed salon's
 * deletion instant (30 days after closure) has come by the application clock, every tenant row of it
 * is removed through each owning module's registered eraser, table by table in bounded batches, each
 * batch a platform transaction of its own, so a deletion that stops part-way resumes where it was on
 * the next run. Then, in one transaction holding the registry row lock, the archive and its token hash
 * are removed, the registry row becomes the {@code deleted} tombstone with its retired slug, and the
 * lifecycle event and platform audit are written; a repeated or concurrent run finds the salon deleted
 * and does nothing more. Invoices, payments, the lifecycle history and the platform audit are platform
 * records and remain. The erasers' database functions refuse any salon whose retention has not run
 * out, so nothing of another salon, or of this one a second early, can be removed.
 *
 * <p>A salon whose export is still being built waits for it: the Owner's archive is produced before
 * the data it is made from is removed.
 */
public interface SalonDeletionService {

    /** One pass at {@code now}: every closed salon whose deletion instant is at or before it. */
    DeletionJobPass run(Instant now);
}
