package com.salonplatform.staff.service.impl;

import com.salonplatform.identity.Principal;
import com.salonplatform.shared.audit.Actor;
import com.salonplatform.shared.audit.ActorKind;
import com.salonplatform.shared.audit.AuditAction;
import com.salonplatform.shared.audit.AuditTrail;
import com.salonplatform.shared.tenancy.CurrentTenant;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * How an hours, holiday or closure change is written to the salon's audit trail (F04-S03): the staff actor,
 * the action, the salon as the subject, and the weekdays or dates it changed with the new version where there
 * is one, never a time, a choice's detail or a closure's reason. Joins the caller's transaction.
 */
@Component
class StaffAudit {

    private final AuditTrail audit;

    StaffAudit(AuditTrail audit) {
        this.audit = audit;
    }

    void record(
            CurrentTenant salon,
            Principal actor,
            String action,
            String subjectKind,
            Map<String, ?> details,
            Instant at) {
        audit.record(
                new AuditAction(
                        new Actor(ActorKind.STAFF, actor.staffId().toString()),
                        action,
                        subjectKind,
                        salon.id().toString(),
                        null,
                        null,
                        details),
                at);
    }
}
