package com.salonplatform.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.booking.BookingTerms;
import com.salonplatform.booking.DepositRule;
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
            new Rescheduling.Limited(2, 12));

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
                new Rescheduling.Unlimited(12));

        assertThat(RuleChanges.between(SUGGESTED, after))
                .containsExactly("slotIntervalMinutes", "deposit", "noShow", "reschedule");
    }
}
