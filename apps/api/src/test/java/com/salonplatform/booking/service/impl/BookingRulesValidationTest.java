package com.salonplatform.booking.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.salonplatform.booking.BookingTerms;
import com.salonplatform.booking.DepositRule;
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
        return new RulesInput(15, 60, 60, deposit, 24, new RetentionInput("deposit", null), noShow, reschedule);
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
                new RescheduleInput(0, 6));

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
                                "rules.reschedule.untilHours"));
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
