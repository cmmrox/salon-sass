package com.salonplatform.catalogue;

/**
 * What a tax-inclusive price includes (CAT-05, decision 095), in minor units of the price's currency. Prices are
 * entered and shown inclusive; the parts are worked out from the price and always add up to it exactly:
 * {@code netMinor + serviceChargeMinor + vatMinor == priceMinor}. A part that does not apply is 0.
 *
 * @param priceMinor the price as entered and shown, which the customer pays
 * @param netMinor the price without the service charge and VAT
 * @param serviceChargeMinor the service charge it includes
 * @param vatMinor the VAT it includes, charged on the price including the service charge
 */
public record PriceParts(long priceMinor, long netMinor, long serviceChargeMinor, long vatMinor) {}
