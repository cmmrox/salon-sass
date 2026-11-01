package com.salonplatform.branding.service.impl;

import com.salonplatform.identity.Principal;
import com.salonplatform.shared.audit.Actor;
import com.salonplatform.shared.audit.ActorKind;
import com.salonplatform.shared.audit.AuditAction;
import com.salonplatform.shared.audit.AuditTrail;
import com.salonplatform.shared.tenancy.CurrentTenant;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * How a settings change is written to the salon's audit trail (F04-S01, F04-S02): the staff actor, the
 * action, the salon as the subject, and the names of the fields it changed with the draft's new version,
 * never a value. Joins the caller's transaction.
 */
@Component
@RequiredArgsConstructor
class SettingsAudit {

    private final AuditTrail audit;

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

    /** A draft change: the fields it changed and the draft's version after it. */
    static Map<String, Object> changed(List<String> fields, long version) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("changedFields", fields);
        after.put("version", version);
        return after;
    }
}
