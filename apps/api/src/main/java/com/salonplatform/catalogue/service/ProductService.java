package com.salonplatform.catalogue.service;

import java.util.List;
import java.util.UUID;

/**
 * The salon's products (INV-01, F05-S03): read with {@code catalogue.read}, changed with
 * {@code catalogue.price.change}. Every refusal is a 400 field error or a 409, and nothing changes. Every change is
 * written to the salon's audit trail with the values before and after.
 */
public interface ProductService {

    /** A product as a request sends it, validated here. Wrappers, so that a missing value is told apart. */
    record ProductInput(String name, Long priceMinor, Boolean chargesVat, Boolean soldAtCheckout) {}

    /**
     * One product.
     *
     * @param version its version, which a save or delete is made from
     */
    record ProductView(
            UUID id,
            String name,
            long priceMinor,
            String currency,
            boolean chargesVat,
            boolean soldAtCheckout,
            long version) {}

    /**
     * Every product of the salon, by name regardless of case (at most 300), with the salon's currency.
     *
     * @param currency the salon's ISO 4217 currency now: what an added or saved product's price is kept in, also
     *     before its first product
     */
    record ProductList(String currency, List<ProductView> items) {}

    /** Every product of the salon, by name regardless of case: at most 300, with the salon's currency. */
    ProductList list();

    /**
     * Adds a product. 400 on {@code name} when it is not 2 to 40 characters after trimming or another product has it
     * regardless of case, on {@code priceMinor} when it is not a whole Rs. 1 to Rs. 500,000 (scaled for the salon's
     * currency), on {@code chargesVat} or {@code soldAtCheckout} when missing; 409 {@code limit_reached} at 300.
     */
    ProductView add(ProductInput input);

    /**
     * Replaces a product's name, price and settings under the same rules. 404 for an id that is not the salon's; 409
     * {@code conflict} for a stale version, {@code invalid_state} when nothing changes.
     */
    ProductView save(UUID id, ProductInput input, Long version);

    /** Deletes a product, which frees its name. 404 for an id that is not the salon's; 409 {@code conflict} for a stale version. */
    void delete(UUID id, Long version);
}
