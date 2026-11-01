package com.salonplatform.catalogue;

/**
 * What a service asks customers to pay when they book it (BKG-09, the per-service override of decision 089,
 * decision 095): the salon's own booking rule, nothing, a share of the service's price, or a fixed amount. It is
 * part of the service's terms, so a booking keeps the deposit of the version it was made on.
 *
 * <p>The catalogue's own value: {@code catalogue} never depends on {@code booking}. {@code booking} turns it into
 * its {@code DepositRule} with {@code com.salonplatform.booking.domain.ServiceDeposits#override}, which F07 and F08
 * pass to {@code BookingPolicies#depositFor}.
 */
public sealed interface ServiceDeposit
        permits ServiceDeposit.SalonRule, ServiceDeposit.None, ServiceDeposit.Percentage, ServiceDeposit.Amount {

    /** The salon's booking rules decide, whatever they are when the booking is made. */
    record SalonRule() implements ServiceDeposit {}

    /** No deposit for this service, even when the salon's rules ask for one. */
    record None() implements ServiceDeposit {}

    /**
     * A share of the price booked (the variant's, for a service with variants).
     *
     * @param percent 1 to 100
     */
    record Percentage(int percent) implements ServiceDeposit {}

    /**
     * The same amount for every booking of the service, never more than its lowest price.
     *
     * @param amountMinor minor units of the terms version's currency
     */
    record Amount(long amountMinor) implements ServiceDeposit {}
}
