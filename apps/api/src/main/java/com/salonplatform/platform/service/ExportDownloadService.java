package com.salonplatform.platform.service;

import com.salonplatform.platform.dto.ExportView;
import com.salonplatform.platform.dto.LinkView;
import java.util.UUID;

/**
 * A closed salon's export, downloaded (PLT-06; docs/architecture/operator-console.md, "Export"): its
 * status and archive for an Admin in the console, and the archive the Owner's emailed link opens until
 * deletion. Every download is audited; any link that does not open a ready archive is not found.
 */
public interface ExportDownloadService {

    /** A downloadable archive: its bytes, the file name to save it as and its checksum. */
    record Archive(byte[] bytes, String fileName, String sha256) {}

    /** The export of a salon, for an Admin; 404 when the salon is unknown or has none. */
    ExportView status(UUID salonId);

    /**
     * The same archive the Owner downloads, for an Admin: 404 when there is none or it was deleted, 409
     * {@code invalid_state} while it is being built. Each download is audited.
     */
    Archive archiveForOperator(UUID salonId);

    /** What the Owner's download page shows for a token; 404 for any token that does not open a ready archive. */
    LinkView linkView(String token);

    /**
     * The archive a download link opens, until deletion; each download is audited. A malformed, wrong,
     * altered or expired token is 404, exactly as one for a salon that never existed.
     */
    Archive archiveForToken(String token);
}
