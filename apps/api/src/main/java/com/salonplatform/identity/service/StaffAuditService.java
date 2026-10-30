package com.salonplatform.identity.service;

import com.salonplatform.identity.dto.AuditView;
import com.salonplatform.identity.dto.Page;

/**
 * The salon's identity and support-session audit entries, as the Owner and Manager read them: only nonsecret
 * values, allowlisted per action (docs/architecture/identity-and-access.md).
 */
public interface StaffAuditService {

    /**
     * A page of the salon's identity and support-session audit entries.
     *
     * @param subjectId only the entries about this subject, or null for all
     */
    Page<AuditView> list(int page, int size, String subjectId);
}
