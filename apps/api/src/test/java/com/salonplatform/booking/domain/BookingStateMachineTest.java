package com.salonplatform.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.booking.domain.BookingStateMachine.Allowed;
import com.salonplatform.booking.domain.BookingStateMachine.Decision;
import com.salonplatform.booking.domain.BookingStateMachine.Reason;
import com.salonplatform.booking.domain.BookingStateMachine.Refused;
import com.salonplatform.booking.domain.BookingStateMachine.Situation;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every action from every state, and each rule at its boundary (BKG-06, decision 075): the start minute, the salon's
 * midnight, the expiry second, and a day the clocks change. The clock is fixed: each case names its own "now".
 */
class BookingStateMachineTest {

    private static final ZoneId COLOMBO = ZoneId.of("Asia/Colombo");

    /** 10:00 in Colombo on 15 June 2086. */
    private static final Instant START = Instant.parse("2086-06-15T04:30:00Z");

    /** A hold placed at 09:00, running out at 09:10. */
    private static final Instant EXPIRY = Instant.parse("2086-06-15T03:40:00Z");

    /** The only changes the machine allows, each from the one state it allows it from, and where it leads. */
    private static final Map<BookingAction, Map<BookingState, BookingState>> ALLOWED = allowed();

    private static Map<BookingAction, Map<BookingState, BookingState>> allowed() {
        Map<BookingAction, Map<BookingState, BookingState>> allowed = new EnumMap<>(BookingAction.class);
        allowed.put(BookingAction.CONFIRM, Map.of(BookingState.HELD, BookingState.CONFIRMED));
        allowed.put(BookingAction.BEGIN_PAYMENT, Map.of(BookingState.HELD, BookingState.PENDING_PAYMENT));
        allowed.put(BookingAction.RELEASE, Map.of(BookingState.HELD, BookingState.RELEASED));
        allowed.put(BookingAction.EXPIRE, Map.of(BookingState.HELD, BookingState.EXPIRED));
        allowed.put(BookingAction.ARRIVE, Map.of(BookingState.CONFIRMED, BookingState.ARRIVED));
        allowed.put(BookingAction.START, Map.of(BookingState.ARRIVED, BookingState.IN_SERVICE));
        allowed.put(BookingAction.CANCEL, Map.of(BookingState.CONFIRMED, BookingState.CANCELLED));
        allowed.put(BookingAction.NO_SHOW, Map.of(BookingState.CONFIRMED, BookingState.NO_SHOW));
        allowed.put(BookingAction.UNDO_NO_SHOW, Map.of(BookingState.NO_SHOW, BookingState.CONFIRMED));
        allowed.put(BookingAction.SETTLE_PAYMENT, Map.of(BookingState.PENDING_PAYMENT, BookingState.CONFIRMED));
        allowed.put(BookingAction.ABANDON_PAYMENT, Map.of(BookingState.PENDING_PAYMENT, BookingState.HELD));
        allowed.put(
                BookingAction.COMPLETE,
                Map.of(BookingState.ARRIVED, BookingState.COMPLETED, BookingState.IN_SERVICE, BookingState.COMPLETED));
        return allowed;
    }

    /** A moment at which the action's time rule, if it has one, is met. */
    private static Instant goodMoment(BookingAction action) {
        return switch (action) {
            case EXPIRE -> EXPIRY;
            case NO_SHOW -> START;
            default -> EXPIRY.minusSeconds(1);
        };
    }

    static Stream<Arguments> everyActionFromEveryState() {
        return Stream.of(BookingAction.values())
                .flatMap(action -> Stream.of(BookingState.values()).map(state -> Arguments.of(action, state)));
    }

    @ParameterizedTest(name = "{0} from {1}")
    @MethodSource("everyActionFromEveryState")
    void onlyTheListedChangesAreAllowedAndEveryOtherIsRefusedWithARule(BookingAction action, BookingState state) {
        Decision decision = BookingStateMachine.decide(action, at(state), goodMoment(action));

        BookingState to = ALLOWED.get(action).get(state);
        if (to != null) {
            assertThat(decision).isEqualTo(new Allowed(to));
        } else {
            assertThat(decision).isInstanceOf(Refused.class);
            Refused refused = (Refused) decision;
            assertThat(refused.detail()).isNotBlank();
            boolean expiredHoldConfirmed = state == BookingState.EXPIRED
                    && (action == BookingAction.CONFIRM || action == BookingAction.BEGIN_PAYMENT);
            assertThat(refused.reason()).isEqualTo(expiredHoldConfirmed ? Reason.HOLD_EXPIRED : Reason.INVALID_STATE);
        }
    }

    @Test
    void aHoldCanBeConfirmedUntilTheSecondBeforeItsExpiryAndThenHasRunOut() {
        assertThat(decide(BookingAction.CONFIRM, BookingState.HELD, EXPIRY.minusSeconds(1)))
                .isEqualTo(new Allowed(BookingState.CONFIRMED));
        assertThat(decide(BookingAction.CONFIRM, BookingState.HELD, EXPIRY))
                .isEqualTo(new Refused(Reason.HOLD_EXPIRED, "The hold has run out, and its time is free for others."));
        assertThat(decide(BookingAction.BEGIN_PAYMENT, BookingState.HELD, EXPIRY))
                .extracting(decision -> ((Refused) decision).reason())
                .isEqualTo(Reason.HOLD_EXPIRED);
    }

    @Test
    void aHoldExpiresAtItsExpirySecondAndNotBefore() {
        assertThat(decide(BookingAction.EXPIRE, BookingState.HELD, EXPIRY.minusSeconds(1)))
                .isInstanceOf(Refused.class);
        assertThat(decide(BookingAction.EXPIRE, BookingState.HELD, EXPIRY))
                .isEqualTo(new Allowed(BookingState.EXPIRED));
    }

    @Test
    void aHoldCanBeReleasedOnlyBeforeItRunsOut() {
        assertThat(decide(BookingAction.RELEASE, BookingState.HELD, EXPIRY.minusSeconds(1)))
                .isEqualTo(new Allowed(BookingState.RELEASED));
        assertThat(decide(BookingAction.RELEASE, BookingState.HELD, EXPIRY)).isInstanceOf(Refused.class);
    }

    @Test
    void aHoldThatRanOutIsShownToNoOneAndAnEndedOneNeither() {
        assertThat(BookingStateMachine.shown(at(BookingState.HELD), EXPIRY.minusSeconds(1)))
                .isTrue();
        assertThat(BookingStateMachine.shown(at(BookingState.HELD), EXPIRY)).isFalse();
        assertThat(BookingStateMachine.shown(at(BookingState.RELEASED), EXPIRY.minusSeconds(1)))
                .isFalse();
        assertThat(BookingStateMachine.shown(at(BookingState.EXPIRED), EXPIRY.minusSeconds(1)))
                .isFalse();
        // A booking waiting for its payment keeps its hold's expiry but never runs out (BKG-04).
        assertThat(BookingStateMachine.shown(at(BookingState.PENDING_PAYMENT), EXPIRY.plus(Duration.ofHours(1))))
                .isTrue();
    }

    @Test
    void aConfirmedBookingIsCancelledOnlyBeforeItsStartMinute() {
        assertThat(decide(BookingAction.CANCEL, BookingState.CONFIRMED, START.minusSeconds(1)))
                .isEqualTo(new Allowed(BookingState.CANCELLED));
        assertThat(decide(BookingAction.CANCEL, BookingState.CONFIRMED, START))
                .isEqualTo(new Refused(Reason.INVALID_STATE, "A booking can be cancelled only before it starts."));
    }

    @Test
    void aNoShowIsMarkedOnlyFromItsStartMinute() {
        assertThat(decide(BookingAction.NO_SHOW, BookingState.CONFIRMED, START.minusSeconds(1)))
                .isEqualTo(new Refused(
                        Reason.INVALID_STATE, "A booking can be marked a no-show only once its start time has come."));
        assertThat(decide(BookingAction.NO_SHOW, BookingState.CONFIRMED, START))
                .isEqualTo(new Allowed(BookingState.NO_SHOW));
        assertThat(decide(BookingAction.NO_SHOW, BookingState.CONFIRMED, START.plus(Duration.ofDays(3))))
                .isEqualTo(new Allowed(BookingState.NO_SHOW));
    }

    @Test
    void arrivedAndAnUndoneNoShowAreOnlyOnTheBookingsOwnDateByTheSalonsMidnight() {
        Instant salonMidnight = Instant.parse("2086-06-14T18:30:00Z"); // 00:00 on the 15th in Colombo.
        Instant nextMidnight = Instant.parse("2086-06-15T18:30:00Z");
        for (BookingAction action : new BookingAction[] {BookingAction.ARRIVE, BookingAction.UNDO_NO_SHOW}) {
            BookingState from = action == BookingAction.ARRIVE ? BookingState.CONFIRMED : BookingState.NO_SHOW;
            assertThat(decide(action, from, salonMidnight.minusSeconds(1))).isInstanceOf(Refused.class);
            assertThat(decide(action, from, salonMidnight)).isInstanceOf(Allowed.class);
            assertThat(decide(action, from, nextMidnight.minusSeconds(1))).isInstanceOf(Allowed.class);
            assertThat(decide(action, from, nextMidnight)).isInstanceOf(Refused.class);
        }
    }

    @Test
    void onTheDayLondonsClocksGoForwardTheBookingsDateIsStillTheLocalOne() {
        ZoneId london = ZoneId.of("Europe/London");
        // 29 March 2026: 01:00 GMT becomes 02:00 BST. A booking at 10:00 BST is 09:00 UTC.
        Situation booking = new Situation(BookingState.CONFIRMED, Instant.parse("2026-03-29T09:00:00Z"), null, london);
        assertThat(BookingStateMachine.decide(BookingAction.ARRIVE, booking, Instant.parse("2026-03-28T23:59:59Z")))
                .isInstanceOf(Refused.class);
        assertThat(BookingStateMachine.decide(BookingAction.ARRIVE, booking, Instant.parse("2026-03-29T00:00:00Z")))
                .isInstanceOf(Allowed.class);
        // 23:59:59 BST on the 29th is 22:59:59 UTC, still the booking's date; an hour later it is not.
        assertThat(BookingStateMachine.decide(BookingAction.ARRIVE, booking, Instant.parse("2026-03-29T22:59:59Z")))
                .isInstanceOf(Allowed.class);
        assertThat(BookingStateMachine.decide(BookingAction.ARRIVE, booking, Instant.parse("2026-03-29T23:00:00Z")))
                .isInstanceOf(Refused.class);
    }

    @Test
    void completedIsReachedOnlyFromArrivedOrInService() {
        assertThat(decide(BookingAction.COMPLETE, BookingState.ARRIVED, START))
                .isEqualTo(new Allowed(BookingState.COMPLETED));
        assertThat(decide(BookingAction.COMPLETE, BookingState.IN_SERVICE, START))
                .isEqualTo(new Allowed(BookingState.COMPLETED));
        assertThat(decide(BookingAction.COMPLETE, BookingState.CONFIRMED, START))
                .isInstanceOf(Refused.class);
        assertThat(BookingAction.STAFF).doesNotContain(BookingAction.COMPLETE, BookingAction.BEGIN_PAYMENT);
    }

    private static Decision decide(BookingAction action, BookingState state, Instant now) {
        return BookingStateMachine.decide(action, at(state), now);
    }

    private static Situation at(BookingState state) {
        return new Situation(state, START, EXPIRY, COLOMBO);
    }
}
