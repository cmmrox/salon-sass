package com.salonplatform.platform.service;

import com.salonplatform.platform.domain.Paging;
import com.salonplatform.platform.dto.SalonDetail;
import com.salonplatform.platform.dto.SalonPage;
import java.util.UUID;

/**
 * The salons as the operator console lists and shows them (PLT-01; docs/architecture/operator-console.md):
 * the registry row, its provisioning profile and its plan, read with the platform role.
 */
public interface SalonDirectoryService {

    /** The salons, a page at a time. */
    SalonPage list(Paging paging);

    /** One salon's detail. */
    SalonDetail get(UUID id);
}
