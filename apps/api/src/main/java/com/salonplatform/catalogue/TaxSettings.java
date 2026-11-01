package com.salonplatform.catalogue;

import java.util.Optional;
import java.util.UUID;

/**
 * The salon's tax settings, as F07 and F08 (bookings and their payments) and F10 (checkout and receipts) read them
 * (CAT-05, decision 095). This is the only way another module reads them or works out what a price includes.
 *
 * <p>Every save of the settings creates a new immutable version, and the newest is current. A booking or a sale
 * stores the {@link TaxSettingsVersion#versionId()} it was made under and later reads that version with
 * {@link #version}, so a change the salon or the platform's configuration makes afterwards never rewrites the tax
 * an existing booking or sale was made under.
 *
 * <p>Prices are tax-inclusive: what the menu shows is what the customer pays. {@link #servicePrice} and
 * {@link #productPrice} work out the parts a price includes under a version, with the rule of decision 095: for a
 * price P in minor units, VAT rate v and service charge s where they apply, {@code beforeVat = round(P / (1 + v))},
 * {@code vat = P - beforeVat}, {@code net = round(beforeVat / (1 + s))}, {@code serviceCharge = beforeVat - net},
 * rounding half to even, so the parts always add up to P. Before a salon first saves its settings nothing is charged:
 * a caller with no version takes the whole price as net.
 *
 * <p>The salon is the request's, never a parameter (docs/engineering/backend.md, "Provided interfaces"). The reads
 * open their own read-only tenant transaction and do not join the caller's; the breakdowns read nothing.
 */
public interface TaxSettings {

    /**
     * The current settings of the salon's default branch (R1 has one branch per salon): its newest version. Empty
     * until the salon first saves its tax settings.
     *
     * @throws com.salonplatform.shared.tenancy.TenantNotFoundException when the request names no salon
     */
    Optional<TaxSettingsVersion> current();

    /**
     * A version of the current salon's tax settings by its id, current or not, with exactly the values, names and
     * rates it was saved with. Another salon's version id is empty, exactly as an id that names no version: the two
     * cannot be told apart.
     *
     * @throws com.salonplatform.shared.tenancy.TenantNotFoundException when the request names no salon
     */
    Optional<TaxSettingsVersion> version(UUID versionId);

    /**
     * What a service's price includes under a version: its VAT when the salon is registered, and its service charge
     * when the salon charges one.
     *
     * @param priceMinor the service's price, tax-inclusive, in minor units; not negative
     */
    PriceParts servicePrice(TaxSettingsVersion version, long priceMinor);

    /**
     * What a product's price includes under a version: its VAT when the salon is registered and the product charges
     * VAT, and the service charge only when the salon charges one on products too.
     *
     * @param priceMinor the product's price, tax-inclusive, in minor units; not negative
     * @param chargesVat the product's own VAT choice ({@link Product#chargesVat()})
     */
    PriceParts productPrice(TaxSettingsVersion version, long priceMinor, boolean chargesVat);
}
