package com.salonplatform.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.booking.BookingTerms;
import com.salonplatform.booking.DepositRule;
import com.salonplatform.booking.NoPreference;
import com.salonplatform.booking.Rescheduling;
import com.salonplatform.booking.Retention;
import org.junit.jupiter.api.Test;

class RuleChangesTest {

    private static final BookingTerms SUGGESTED = new BookingTerms(
            15,
            60,
            60,
            new DepositRule.Percentage(20, 200_000),
            24,
            new Retention.Deposit(),
            new Retention.Deposit(),
            new Rescheduling.Limited(2, 12),
            10,
            NoPreference.LEAST_BOOKED);

    @Test
    void aFirstVersionChangesEveryRuleInTheFormsOrder() {
        assertThat(RuleChanges.between(null, SUGGESTED)).isEqualTo(RuleChanges.FIELDS);
    }

    @Test
    void identicalRulesChangeNothing() {
        assertThat(RuleChanges.between(SUGGESTED, SUGGESTED)).isEmpty();
    }

    @Test
    void aChangeInsideARuleNamesTheRule() {
        BookingTerms after = new BookingTerms(
                10,
                60,
                60,
                new DepositRule.Percentage(20, 250_000),
                24,
                new Retention.Deposit(),
                new Retention.Share(100),
                new Rescheduling.Unlimited(12),
                10,
                NoPreference.LEAST_BOOKED);

        assertThat(RuleChanges.between(SUGGESTED, after))
                .containsExactly("slotIntervalMinutes", "deposit", "noShow", "reschedule");
    }

    @Test
    void aChangeOfTheHoldLengthOrTheStrategyIsNamedWhereTheFormShowsIt() {
        BookingTerms after = new BookingTerms(
                15,
                60,
                60,
                new DepositRule.Percentage(20, 200_000),
                24,
                new Retention.Deposit(),
                new Retention.Deposit(),
                new Rescheduling.Limited(2, 12),
                5,
                NoPreference.MOST_BOOKED);

        assertThat(RuleChanges.between(SUGGESTED, after)).containsExactly("holdMinutes", "noPreference");
        assertThat(RuleChanges.FIELDS.subList(2, 6))
                .containsExactly("bookingHorizonDays", "holdMinutes", "noPreference", "deposit");
    }
}
