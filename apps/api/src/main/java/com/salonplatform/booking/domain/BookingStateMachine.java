package com.salonplatform.booking.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/**
 * The one place a booking's state changes are decided (BKG-06, decision 075). Pure: the booking's state and times, the
 * salon's zone and the application's "now" in, a decision out.
 *
 * <ul>
 *   <li>A hold is confirmed, released or put into payment only before its expiry; at the expiry instant it has run
 *       out, and only then may it expire.
 *   <li>Arrived only on the booking's own local date, from confirmed; in service only from arrived.
 *   <li>Cancelled only from confirmed and only before the start instant; no-show only from confirmed and only at or
 *       after it; a no-show undone only on the booking's local date.
 *   <li>Pending payment is entered from a live hold and left for confirmed or back to held (F08); completed only from
 *       arrived or in service, through checkout (F10).
 * </ul>
 *
 * Whether the professional's time is still free when a booking becomes live again is the database's question (the
 * exclusion constraint), not this one's.
 */
public final class BookingStateMachine {

    private BookingStateMachine() {}

    /** What a booking is now, as far as the rules need it. */
    public record Situation(BookingState state, Instant startsAt, Instant holdExpiresAt, ZoneId zone) {

        public Situation {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(startsAt, "startsAt");
            Objects.requireNonNull(zone, "zone");
        }
    }

    /** A decision. */
    public sealed interface Decision {}

    /** The change is allowed and leads to this state. */
    public record Allowed(BookingState to) implements Decision {}

    /** The change is refused for this reason, with a sentence naming the rule. */
    public record Refused(Reason reason, String detail) implements Decision {}

    /** Why a change is refused. */
    public enum Reason {
        /** Not possible from this state, or not at this time. */
        INVALID_STATE,
        /** The hold has run out. */
        HOLD_EXPIRED
    }

    public static Decision decide(BookingAction action, Situation booking, Instant now) {
        Objects.requireNonNull(now, "now");
        BookingState state = booking.state();
        return switch (action) {
            case CONFIRM -> fromLiveHold(booking, now, BookingState.CONFIRMED, "Only a hold can be confirmed.");
            case BEGIN_PAYMENT ->
                fromLiveHold(booking, now, BookingState.PENDING_PAYMENT, "Only a hold can wait for a payment.");
            case RELEASE ->
                state == BookingState.HELD && !runOut(booking, now)
                        ? new Allowed(BookingState.RELEASED)
                        : refused("Only a hold can be released.");
            case EXPIRE ->
                state == BookingState.HELD && runOut(booking, now)
                        ? new Allowed(BookingState.EXPIRED)
                        : refused("Only a hold whose time has run out expires.");
            case ARRIVE -> {
                if (state != BookingState.CONFIRMED) {
                    yield refused("Only a confirmed booking can be marked arrived.");
                }
                yield onItsDate(booking, now)
                        ? new Allowed(BookingState.ARRIVED)
                        : refused("A customer can be marked arrived only on the day of the booking.");
            }
            case START ->
                state == BookingState.ARRIVED
                        ? new Allowed(BookingState.IN_SERVICE)
                        : refused("Only a customer who has arrived can start their service.");
            case CANCEL -> {
                if (state != BookingState.CONFIRMED) {
                    yield refused("Only a confirmed booking can be cancelled.");
                }
                yield now.isBefore(booking.startsAt())
                        ? new Allowed(BookingState.CANCELLED)
                        : refused("A booking can be cancelled only before it starts.");
            }
            case NO_SHOW -> {
                if (state != BookingState.CONFIRMED) {
                    yield refused("Only a confirmed booking can be marked a no-show.");
                }
                yield now.isBefore(booking.startsAt())
                        ? refused("A booking can be marked a no-show only once its start time has come.")
                        : new Allowed(BookingState.NO_SHOW);
            }
            case UNDO_NO_SHOW -> {
                if (state != BookingState.NO_SHOW) {
                    yield refused("Only a no-show can be undone.");
                }
                yield onItsDate(booking, now)
                        ? new Allowed(BookingState.CONFIRMED)
                        : refused("A no-show can be undone only on the day of the booking.");
            }
            case SETTLE_PAYMENT ->
                state == BookingState.PENDING_PAYMENT
                        ? new Allowed(BookingState.CONFIRMED)
                        : refused("Only a booking waiting for its payment can be confirmed by one.");
            case ABANDON_PAYMENT ->
                state == BookingState.PENDING_PAYMENT
                        ? new Allowed(BookingState.HELD)
                        : refused("Only a booking waiting for its payment can go back to being held.");
            case COMPLETE ->
                state == BookingState.ARRIVED || state == BookingState.IN_SERVICE
                        ? new Allowed(BookingState.COMPLETED)
                        : refused("Only a booking whose customer has arrived can be completed.");
        };
    }

    /** Whether the hold has run out: at its expiry instant and after. A booking that is not a hold never has. */
    public static boolean runOut(Situation booking, Instant now) {
        return booking.state() == BookingState.HELD
                && booking.holdExpiresAt() != null
                && !now.isBefore(booking.holdExpiresAt());
    }

    /** Whether a caller may see it: never a hold that ended or whose time has run out. */
    public static boolean shown(Situation booking, Instant now) {
        return BookingState.SHOWN.contains(booking.state()) && !runOut(booking, now);
    }

    private static Decision fromLiveHold(Situation booking, Instant now, BookingState to, String otherwise) {
        if (booking.state() == BookingState.EXPIRED || runOut(booking, now)) {
            return new Refused(Reason.HOLD_EXPIRED, "The hold has run out, and its time is free for others.");
        }
        return booking.state() == BookingState.HELD ? new Allowed(to) : refused(otherwise);
    }

    private static boolean onItsDate(Situation booking, Instant now) {
        LocalDate today = now.atZone(booking.zone()).toLocalDate();
        return today.equals(booking.startsAt().atZone(booking.zone()).toLocalDate());
    }

    private static Refused refused(String detail) {
        return new Refused(Reason.INVALID_STATE, detail);
    }
}
