package com.salonplatform.booking.domain;

import java.util.List;
import java.util.Optional;

/**
 * Every change of a booking's state (BKG-06). The staff actions have the names of their endpoints
 * ({@code POST /api/v1/bookings/{id}/<name>}) and of {@code allowedActions}; the others are reached only by the job
 * and the in-process contracts of F08 and F10.
 */
public enum BookingAction {
    CONFIRM("confirm", Permission.MANAGE),
    RELEASE("release", Permission.MANAGE),
    ARRIVE("arrive", Permission.ATTEND),
    START("start", Permission.ATTEND),
    CANCEL("cancel", Permission.MANAGE),
    NO_SHOW("no-show", Permission.MANAGE),
    UNDO_NO_SHOW("undo-no-show", Permission.MANAGE),
    /** The job, or a placement that needs the time: a hold past its expiry ends. */
    EXPIRE(null, null),
    /** F08: a payment starts on a hold, which is then never expired (BKG-04). */
    BEGIN_PAYMENT(null, null),
    /** F08: the payment succeeded. */
    SETTLE_PAYMENT(null, null),
    /** F08: the payment did not go through, so the time is a hold again. */
    ABANDON_PAYMENT(null, null),
    /** F10: checkout, the only way to completed. */
    COMPLETE(null, null);

    /** Who may take a staff action (decision 108(b)). */
    public enum Permission {
        /** {@code booking.manage}: Owner, Manager, Receptionist. */
        MANAGE,
        /** {@code booking.attend}: those and a Professional on their own bookings. */
        ATTEND
    }

    /** The staff actions, in the order {@code allowedActions} lists them. */
    public static final List<BookingAction> STAFF =
            List.of(CONFIRM, RELEASE, ARRIVE, START, CANCEL, NO_SHOW, UNDO_NO_SHOW);

    private final String apiName;
    private final Permission permission;

    BookingAction(String apiName, Permission permission) {
        this.apiName = apiName;
        this.permission = permission;
    }

    /** The endpoint's name for a staff action. */
    public String apiName() {
        if (apiName == null) {
            throw new IllegalStateException(name() + " is not a staff action");
        }
        return apiName;
    }

    public Permission permission() {
        if (permission == null) {
            throw new IllegalStateException(name() + " is not a staff action");
        }
        return permission;
    }

    public static Optional<BookingAction> staff(String apiName) {
        return STAFF.stream().filter(action -> action.apiName.equals(apiName)).findFirst();
    }
}
