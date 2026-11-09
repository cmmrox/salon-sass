package com.salonplatform.booking.service.impl;

import com.salonplatform.booking.domain.BookingAction;
import com.salonplatform.booking.entity.Booking;
import com.salonplatform.branding.SalonBranches;
import com.salonplatform.identity.Principal;
import com.salonplatform.identity.Principals;
import com.salonplatform.shared.tenancy.CurrentTenant;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.staff.TeamMember;
import com.salonplatform.staff.TeamMembers;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Who calls a booking use case, settled before its transaction opens (docs/architecture/multi-tenancy.md): the salon,
 * the principal and its permission (decision 108(b)), the salon's default branch through branding's
 * {@link SalonBranches}, and which bookings the caller reaches. A Professional whose data scope is {@code own} reaches
 * only the bookings of the team member linked to their account, and none when no member is; with the IAM-08 grant
 * (scope {@code salon}) they read the salon's. Arrived and in service are a Professional's only on their own bookings,
 * granted or not.
 */
@Component
class BookingCallers {

    static final String READ = "calendar.read";
    static final String MANAGE = "booking.manage";
    static final String ATTEND = "booking.attend";

    private final Principals principals;
    private final SalonBranches branches;
    private final TeamMembers team;

    BookingCallers(Principals principals, SalonBranches branches, TeamMembers team) {
        this.principals = principals;
        this.branches = branches;
        this.team = team;
    }

    /**
     * A caller of a booking use case.
     *
     * @param ownMember the member linked to the caller's account, when the caller has one
     * @param readsOwnOnly whether the caller reads only {@code ownMember}'s bookings (data scope {@code own})
     */
    record Caller(CurrentTenant salon, Principal actor, UUID branch, Optional<UUID> ownMember, boolean readsOwnOnly) {

        /** Whether the caller may read the booking. */
        boolean reads(Booking booking) {
            return !readsOwnOnly
                    || ownMember.filter(booking.getMemberId()::equals).isPresent();
        }

        /** Whether the caller holds what the action needs, on this booking. */
        boolean mayTake(BookingAction action, Booking booking) {
            return switch (action.permission()) {
                case MANAGE -> actor.permissions().contains(MANAGE);
                case ATTEND ->
                    actor.permissions().contains(MANAGE)
                            || (actor.permissions().contains(ATTEND)
                                    && ownMember
                                            .filter(booking.getMemberId()::equals)
                                            .isPresent());
            };
        }

        /** Whether the caller's permission for the action reaches this booking at all; when not, it is not found. */
        boolean reaches(BookingAction action, Booking booking) {
            if (!reads(booking)) {
                return false;
            }
            return action.permission() == BookingAction.Permission.MANAGE
                    || actor.permissions().contains(MANAGE)
                    || ownMember.filter(booking.getMemberId()::equals).isPresent();
        }

        UUID staffId() {
            return actor.staffId();
        }
    }

    /** The caller, when it holds the permission; 403 {@code permission_denied} otherwise. */
    Caller of(String permission) {
        CurrentTenant salon = TenantContext.require();
        Principal actor = principals.require(permission);
        UUID branch = branches.defaultBranch().id();
        Optional<UUID> ownMember = actor.staffId() == null
                ? Optional.empty()
                : team.linkedTo(actor.staffId()).map(TeamMember::id);
        return new Caller(salon, actor, branch, ownMember, "own".equals(actor.scope()));
    }

    /** The caller of a staff action, holding the permission the action needs. */
    Caller forAction(BookingAction action) {
        return of(action.permission() == BookingAction.Permission.MANAGE ? MANAGE : ATTEND);
    }
}
