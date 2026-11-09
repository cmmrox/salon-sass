package com.salonplatform.booking.service.impl;

import com.salonplatform.booking.domain.BookingAction;
import com.salonplatform.booking.domain.BookingState;
import com.salonplatform.booking.domain.CancellationReason;
import com.salonplatform.booking.entity.Booking;
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
 * How booking's changes are written to the salon's audit trail. A save of the booking rules (F04-S04 AC-9): the staff
 * actor, the time, the version it created as the subject ({@code booking_rules_version}), and the version's number with
 * the names of the rules it changed, never a value. A change of a booking's state (F07-S01 AC-9): the staff account or
 * the system, the booking as the subject ({@code booking_appointment}), a cancellation's reason, and the state before
 * and after; the placing of a hold records what it holds, never the customer's name or mobile number. Joins the caller's
 * transaction, which sets the salon.
 */
@Component
class BookingAudit {

    static final String SAVED = "booking.rules_saved";
    static final String SUBJECT = "booking_rules_version";
    static final String BOOKING = "booking_appointment";
    static final String PLACED = "booking.hold_placed";

    private final AuditTrail audit;

    BookingAudit(AuditTrail audit) {
        this.audit = audit;
    }

    /** The hold was placed. */
    void placed(Booking booking, UUID staffId, Instant at) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("reference", booking.getReference());
        after.put("state", booking.state().value());
        after.put("memberId", booking.getMemberId().toString());
        after.put("serviceId", booking.getServiceId().toString());
        after.put("startsAt", booking.getStartsAt().toString());
        after.put("channel", booking.channel().value());
        audit.record(
                new AuditAction(actor(staffId), PLACED, BOOKING, booking.getId().toString(), null, null, after), at);
    }

    /** The booking's state changed, by a member of staff or, when {@code staffId} is null, the system. */
    void changed(
            Booking booking,
            BookingAction action,
            BookingState from,
            BookingState to,
            UUID staffId,
            CancellationReason reason,
            Instant at) {
        audit.record(
                new AuditAction(
                        actor(staffId),
                        ACTIONS.get(action),
                        BOOKING,
                        booking.getId().toString(),
                        reason == null ? null : reason.value(),
                        Map.of("state", from.value()),
                        Map.of("state", to.value())),
                at);
    }

    private static Actor actor(UUID staffId) {
        return staffId == null ? Actor.system("booking") : new Actor(ActorKind.STAFF, staffId.toString());
    }

    private static final Map<BookingAction, String> ACTIONS = Map.ofEntries(
            Map.entry(BookingAction.CONFIRM, "booking.confirmed"),
            Map.entry(BookingAction.RELEASE, "booking.hold_released"),
            Map.entry(BookingAction.EXPIRE, "booking.hold_expired"),
            Map.entry(BookingAction.ARRIVE, "booking.arrived"),
            Map.entry(BookingAction.START, "booking.service_started"),
            Map.entry(BookingAction.CANCEL, "booking.cancelled"),
            Map.entry(BookingAction.NO_SHOW, "booking.no_show_marked"),
            Map.entry(BookingAction.UNDO_NO_SHOW, "booking.no_show_undone"),
            Map.entry(BookingAction.BEGIN_PAYMENT, "booking.payment_started"),
            Map.entry(BookingAction.SETTLE_PAYMENT, "booking.payment_settled"),
            Map.entry(BookingAction.ABANDON_PAYMENT, "booking.payment_abandoned"),
            Map.entry(BookingAction.COMPLETE, "booking.completed"));

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
