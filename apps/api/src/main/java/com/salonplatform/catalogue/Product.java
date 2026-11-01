package com.salonplatform.catalogue;

import java.util.UUID;

/**
 * One product the salon sells at the front desk (INV-01), as it is now. A sale (F10) copies its name, price and VAT
 * choice, so a later change or delete never rewrites a past sale. Stock is not tracked (INV-02).
 *
 * @param id the product's id
 * @param name its name, unique in the salon regardless of case
 * @param priceMinor its tax-inclusive price in minor units of {@code currency}
 * @param currency the ISO 4217 currency of its price: the salon's when it was last saved
 * @param chargesVat whether VAT applies to it while the salon is registered for VAT; kept while VAT is off
 * @param soldAtCheckout whether the desk offers it at checkout
 */
public record Product(
        UUID id, String name, long priceMinor, String currency, boolean chargesVat, boolean soldAtCheckout) {}
