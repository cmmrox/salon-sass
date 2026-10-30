package com.salonplatform.platform.service;

import com.salonplatform.platform.domain.Paging;
import com.salonplatform.platform.dto.SalonSupportSessionPage;
import java.util.UUID;

/**
 * A salon's Owner sees and revokes the salon's support sessions on the salon's own host (PLT-07;
 * docs/architecture/operator-console.md, "Support sessions"). Another salon's session is not found.
 */
public interface SalonSupportSessionService {

    /** The salon's own sessions, active and past, for its Owner. */
    SalonSupportSessionPage salonList(Paging paging);

    /** The Owner revokes an active session of the salon; another salon's is not found. */
    void salonRevoke(UUID sessionId);
}
