package com.salonplatform.booking.service.impl;

import com.salonplatform.identity.Principal;
import com.salonplatform.shared.audit.Actor;
import com.salonplatform.shared.audit.ActorKind;
import com.salonplatform.shared.audit.AuditAction;
import com.salonplatform.shared.audit.AuditTrail;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * How a save of the booking rules is written to the salon's audit trail (F04-S04 AC-9): the staff actor, the
 * time, the version it created as the subject ({@code booking_rules_version}), and the version's number with the
 * names of the rules it changed, never a value. Joins the caller's transaction, which sets the salon.
 */
@Component
class BookingAudit {

    static final String SAVED = "booking.rules_saved";
    static final String SUBJECT = "booking_rules_version";

    private final AuditTrail audit;

    BookingAudit(AuditTrail audit) {
        this.audit = audit;
    }

    void saved(Principal actor, UUID versionId, long version, List<String> changedFields, Instant at) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("version", version);
        details.put("changedFields", changedFields);
        audit.record(
                new AuditAction(
                        new Actor(ActorKind.STAFF, actor.staffId().toString()),
                        SAVED,
                        SUBJECT,
                        versionId.toString(),
                        null,
                        null,
                        details),
                at);
    }
}
