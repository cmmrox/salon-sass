package com.salonplatform.booking.domain;

import com.salonplatform.booking.DepositRule;
import com.salonplatform.catalogue.ServiceDeposit;
import java.util.Objects;
import java.util.Optional;

/**
 * A service's own deposit, as the catalogue keeps it on the service's terms (BKG-09, decision 095), turned into the
 * override {@link com.salonplatform.booking.BookingPolicies#depositFor} takes. F07 and F08 call it with the
 * {@code deposit} of the {@link com.salonplatform.catalogue.ServiceTermsVersion} a booking is made on:
 *
 * <ul>
 *   <li>the salon's rule: no override, so the rules' own deposit applies;
 *   <li>none: {@link DepositRule.None}, so the service asks for no deposit even when the rules do;
 *   <li>a percentage: {@link DepositRule.Percentage} with no cap of its own, the cap set to the safe-integer bound
 *       (2^53-1 minor units, decision 091);
 *   <li>an amount: {@link DepositRule.Amount}, in the currency of the service's terms.
 * </ul>
 */
public final class ServiceDeposits {

    private ServiceDeposits() {}

    public static Optional<DepositRule> override(ServiceDeposit deposit) {
        Objects.requireNonNull(deposit, "deposit");
        return switch (deposit) {
            case ServiceDeposit.SalonRule _ -> Optional.empty();
            case ServiceDeposit.None _ -> Optional.of(new DepositRule.None());
            case ServiceDeposit.Percentage percentage ->
                Optional.of(new DepositRule.Percentage(percentage.percent(), RuleOptions.MAX_SAFE_MINOR));
            case ServiceDeposit.Amount amount -> Optional.of(new DepositRule.Amount(amount.amountMinor()));
        };
    }
}
