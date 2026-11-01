package com.salonplatform.catalogue.service.impl;

import com.salonplatform.identity.Principal;
import com.salonplatform.shared.audit.Actor;
import com.salonplatform.shared.audit.ActorKind;
import com.salonplatform.shared.audit.AuditAction;
import com.salonplatform.shared.audit.AuditTrail;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * How a change to the catalogue is written to the salon's audit trail (F05-S01 AC-15): the staff actor, the time,
 * the category or service as the subject, and the values before and after. A change of a service's terms also
 * names the terms version it created. Joins the caller's transaction, which sets the salon.
 */
@Component
class CatalogueAudit {

    static final String CATEGORY = "catalogue_category";
    static final String SERVICE = "catalogue_service";

    private final AuditTrail audit;

    CatalogueAudit(AuditTrail audit) {
        this.audit = audit;
    }

    /**
     * @param action a stable dotted name, such as {@code catalogue.service_updated}
     * @param before the values before, or null for a creation
     * @param after the values after, or null for a deletion
     */
    void record(
            Principal actor,
            String action,
            String subjectKind,
            UUID subjectId,
            Map<String, ?> before,
            Map<String, ?> after,
            Instant at) {
        audit.record(
                new AuditAction(
                        new Actor(ActorKind.STAFF, actor.staffId().toString()),
                        action,
                        subjectKind,
                        subjectId.toString(),
                        null,
                        before,
                        after),
                at);
    }
}
