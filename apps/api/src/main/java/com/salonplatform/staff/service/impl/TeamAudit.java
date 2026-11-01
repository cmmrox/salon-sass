package com.salonplatform.staff.service.impl;

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
 * How a change to a team member is written to the salon's audit trail (F06-S01 AC-15): the actor, the time, the
 * member as the subject, and the values before and after. Joins the caller's transaction, which sets the salon.
 */
@Component
class TeamAudit {

    static final String ADDED = "staff.team_member_added";
    static final String CHANGED = "staff.team_member_changed";
    static final String REMOVED = "staff.team_member_removed";
    static final String PHOTO_CHANGED = "staff.team_member_photo_changed";
    static final String PHOTO_REMOVED = "staff.team_member_photo_removed";
    static final String LINK_CLEARED = "staff.team_member_link_cleared";

    private static final String MEMBER = "staff_team_member";

    private final AuditTrail audit;

    TeamAudit(AuditTrail audit) {
        this.audit = audit;
    }

    /**
     * @param before the values before, or null for an addition
     * @param after the values after, or null for a removal
     */
    void record(
            Principal actor, String action, UUID memberId, Map<String, ?> before, Map<String, ?> after, Instant at) {
        write(new Actor(ActorKind.STAFF, actor.staffId().toString()), action, memberId, before, after, at);
    }

    /** An account's removal cleared the link: its remover acts, or the platform when a local test hook did it. */
    void linkCleared(UUID memberId, Map<String, ?> before, Map<String, ?> after, UUID removedBy, Instant at) {
        Actor actor = removedBy == null
                ? new Actor(ActorKind.SYSTEM, "local-reset")
                : new Actor(ActorKind.STAFF, removedBy.toString());
        write(actor, LINK_CLEARED, memberId, before, after, at);
    }

    private void write(
            Actor actor, String action, UUID memberId, Map<String, ?> before, Map<String, ?> after, Instant at) {
        audit.record(new AuditAction(actor, action, MEMBER, memberId.toString(), null, before, after), at);
    }
}
