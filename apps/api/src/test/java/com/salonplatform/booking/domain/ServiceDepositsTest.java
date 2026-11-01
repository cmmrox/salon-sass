package com.salonplatform.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.salonplatform.booking.DepositRule;
import com.salonplatform.catalogue.ServiceDeposit;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A service's own deposit turned into the override {@code BookingPolicies.depositFor} takes (F05-S02 AC-8, decision
 * 095): the salon's rule is no override, none is {@code None}, a percentage has the safe-integer bound as its cap
 * (decision 091), an amount is the same amount.
 */
class ServiceDepositsTest {

    @Test
    void theSalonsRuleIsNoOverride() {
        assertThat(ServiceDeposits.override(new ServiceDeposit.SalonRule())).isEmpty();
    }

    @Test
    void noDepositOverridesTheRulesWithNone() {
        assertThat(ServiceDeposits.override(new ServiceDeposit.None())).contains(new DepositRule.None());
    }

    @Test
    void aPercentageHasTheSafeIntegerBoundAsItsCap() {
        assertThat(ServiceDeposits.override(new ServiceDeposit.Percentage(25)))
                .contains(new DepositRule.Percentage(25, 9_007_199_254_740_991L));
    }

    @Test
    void anAmountIsTheSameAmount() {
        assertThat(ServiceDeposits.override(new ServiceDeposit.Amount(150_000)))
                .isEqualTo(Optional.of(new DepositRule.Amount(150_000)));
    }

    @Test
    void aMissingDepositIsAProgrammingError() {
        assertThatNullPointerException().isThrownBy(() -> ServiceDeposits.override(null));
    }
}
