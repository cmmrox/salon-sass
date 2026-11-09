package com.salonplatform.booking.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.salonplatform.booking.BookingTerms;
import com.salonplatform.booking.DepositRule;
import com.salonplatform.booking.NoPreference;
import com.salonplatform.booking.Rescheduling;
import com.salonplatform.booking.Retention;
import com.salonplatform.booking.domain.MinorUnits;
import com.salonplatform.booking.service.BookingRulesService.DepositInput;
import com.salonplatform.booking.service.BookingRulesService.RescheduleInput;
import com.salonplatform.booking.service.BookingRulesService.RetentionInput;
import com.salonplatform.booking.service.BookingRulesService.RulesInput;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.FieldProblem;
import org.junit.jupiter.api.Test;

class BookingRulesValidationTest {

    private static final MinorUnits RUPEES = MinorUnits.of("LKR");

    private static RulesInput rules(DepositInput deposit, RetentionInput noShow, RescheduleInput reschedule) {
        return withHold(deposit, noShow, reschedule, null, null);
    }

    private static RulesInput withHold(
            DepositInput deposit, RetentionInput noShow, RescheduleInput reschedule, Integer hold, String strategy) {
        return new RulesInput(
                15, 60, 60, deposit, 24, new RetentionInput("deposit", null), noShow, reschedule, hold, strategy);
    }

    private static final DepositInput NONE = new DepositInput("none", null, null, null);
    private static final RetentionInput KEEP_DEPOSIT = new RetentionInput("deposit", null);
    private static final RescheduleInput ONCE = new RescheduleInput(1, 24);

    @Test
    void everyOfferedHoldLengthAndBothStrategiesAreKept() {
        for (int minutes : new int[] {5, 10, 15, 20, 30}) {
            for (NoPreference strategy : NoPreference.values()) {
                BookingTerms terms = BookingRulesValidation.valid(
                        withHold(NONE, KEEP_DEPOSIT, ONCE, minutes, strategy.value()), RUPEES);
                assertThat(terms.holdMinutes()).isEqualTo(minutes);
                assertThat(terms.noPreference()).isEqualTo(strategy);
            }
        }
    }

    @Test
    void aHoldLengthOrStrategyLeftOutIsTenMinutesAndLeastBookedOnAFirstSave() {
        BookingTerms terms = BookingRulesValidation.valid(rules(NONE, KEEP_DEPOSIT, ONCE), RUPEES);

        assertThat(terms.holdMinutes()).isEqualTo(10);
        assertThat(terms.noPreference()).isEqualTo(NoPreference.LEAST_BOOKED);
        assertThat(BookingRulesValidation.keepingUnsent(terms, rules(NONE, KEEP_DEPOSIT, ONCE), null))
                .isSameAs(terms);
    }

    @Test
    void aHoldLengthOrStrategyLeftOutKeepsTheCurrentVersionsWhileOneSentReplacesIt() {
        BookingTerms before =
                BookingRulesValidation.valid(withHold(NONE, KEEP_DEPOSIT, ONCE, 30, "most_booked"), RUPEES);
        RulesInput leftOut = rules(NONE, KEEP_DEPOSIT, ONCE);
        BookingTerms kept =
                BookingRulesValidation.keepingUnsent(BookingRulesValidation.valid(leftOut, RUPEES), leftOut, before);
        assertThat(kept).isEqualTo(before);

        RulesInput sent = withHold(NONE, KEEP_DEPOSIT, ONCE, 5, null);
        BookingTerms changed =
                BookingRulesValidation.keepingUnsent(BookingRulesValidation.valid(sent, RUPEES), sent, before);
        assertThat(changed.holdMinutes()).isEqualTo(5);
        assertThat(changed.noPreference()).isEqualTo(NoPreference.MOST_BOOKED);
    }

    @Test
    void aHoldLengthOrStrategyNotOfferedIsAFieldError() {
        for (int minutes : new int[] {0, 1, 4, 6, 25, 31, 60, -10}) {
            assertThatExceptionOfType(ApplicationProblemException.class)
                    .isThrownBy(() -> BookingRulesValidation.valid(
                            withHold(NONE, KEEP_DEPOSIT, ONCE, minutes, "least_booked"), RUPEES))
                    .satisfies(problem -> assertThat(problem.errors())
                            .extracting(FieldProblem::field)
                            .containsExactly("rules.holdMinutes"));
        }
        for (String strategy : new String[] {"", "even", "LEAST_BOOKED", "fill", "least booked"}) {
            assertThatExceptionOfType(ApplicationProblemException.class)
                    .isThrownBy(() ->
                            BookingRulesValidation.valid(withHold(NONE, KEEP_DEPOSIT, ONCE, 10, strategy), RUPEES))
                    .satisfies(problem -> assertThat(problem.errors())
                            .extracting(FieldProblem::field)
                            .containsExactly("rules.noPreference"));
        }
    }

    @Test
    void theSuggestedRulesAreValidAndKeptInMinorUnitsWithNoRounding() {
        BookingTerms terms = BookingRulesValidation.valid(
                rules(
                        new DepositInput("percentage", 20, 200_000L, null),
                        new RetentionInput("share", 100),
                        new RescheduleInput(null, 24)),
                RUPEES);

        assertThat(terms.deposit()).isEqualTo(new DepositRule.Percentage(20, 200_000));
        assertThat(terms.noShow()).isEqualTo(new Retention.Share(100));
        assertThat(terms.reschedule()).isEqualTo(new Rescheduling.Unlimited(24));
    }

    @Test
    void everyBrokenRuleIsNamedAtOnce() {
        RulesInput broken = new RulesInput(
                12,
                null,
                60,
                new DepositInput("amount", null, null, 9_999L),
                24,
                new RetentionInput("share", null),
                new RetentionInput("nothing", null),
                new RescheduleInput(0, 6),
                3,
                "even");

        assertThatExceptionOfType(ApplicationProblemException.class)
                .isThrownBy(() -> BookingRulesValidation.valid(broken, RUPEES))
                .satisfies(problem -> assertThat(problem.errors())
                        .extracting(FieldProblem::field)
                        .containsExactly(
                                "rules.slotIntervalMinutes",
                                "rules.minimumNoticeMinutes",
                                "rules.deposit.amountMinor",
                                "rules.lateCancellation.percent",
                                "rules.noShow.kind",
                                "rules.reschedule.untilHours",
                                "rules.holdMinutes",
                                "rules.noPreference"));
    }

    @Test
    void missingRulesAreRequired() {
        assertThatExceptionOfType(ApplicationProblemException.class)
                .isThrownBy(() -> BookingRulesValidation.valid(null, RUPEES))
                .satisfies(problem -> assertThat(problem.errors())
                        .extracting(FieldProblem::field)
                        .containsExactly("rules"));
    }
}
