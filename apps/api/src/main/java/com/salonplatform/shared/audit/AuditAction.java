package com.salonplatform.shared.audit;

import java.util.Map;
import java.util.Objects;

/**
 * A sensitive action to record (NFR-10). The salon, the time and the trace are not given: they are
 * the current salon's, the application clock's and the current trace's.
 *
 * @param actor who acted
 * @param action what was done, a stable dotted name such as {@code booking.refunded}
 * @param subjectKind the kind of thing it was done to
 * @param subjectId that thing's id
 * @param reason why, when the actor gave a reason, or null
 * @param before the values before, or null when there were none
 * @param after the values after, or null when there are none
 */
public record AuditAction(
        Actor actor,
        String action,
        String subjectKind,
        String subjectId,
        String reason,
        Map<String, ?> before,
        Map<String, ?> after) {

    public AuditAction {
        Objects.requireNonNull(actor, "actor");
        for (String required : new String[] {action, subjectKind, subjectId}) {
            if (required == null || required.isBlank()) {
                throw new IllegalArgumentException("an audit entry has an action and a subject");
            }
        }
    }
}
