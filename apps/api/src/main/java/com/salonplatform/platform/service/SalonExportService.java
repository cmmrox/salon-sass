package com.salonplatform.platform.service;

import com.salonplatform.platform.dto.ExportJobPass;
import com.salonplatform.shared.lifecycle.TenantDataModule;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A closed salon's export, built (PLT-06; docs/architecture/operator-console.md, "Export"). The export job
 * builds it table by table: each registered module's exporter reads one of its tables in a read-only
 * tenant transaction with that one salon set, and the rows are stored, encrypted, as one part in a
 * platform transaction of their own, so a run that stops part-way resumes from the next table. Once
 * every table has its part, one platform transaction holding the export's row lock writes the ZIP
 * (a JSON file per table and a manifest of tables, row counts, the salon's profile, the export instant
 * and the excluded secret columns), stores it encrypted with its SHA-256, issues the download token
 * (only its hash is kept), writes the Owner's email to the outbox and records the platform audit. A
 * repeated or concurrent run finds the export ready and does nothing more, so there is one archive.
 * The archive is downloaded through {@link ExportDownloadService}.
 */
public interface SalonExportService {

    /** Every registered tenant table, in the order exported and listed in the manifest. */
    List<Map.Entry<TenantDataModule, TenantDataModule.Table>> tables();

    /** One pass of the export job at {@code now}: every pending export of a closed salon. */
    ExportJobPass run(Instant now);

    /** The export-ready email's links, opened with the export key (for the delivering channel). */
    List<String> openLinks(String exportId, String sealed);
}
