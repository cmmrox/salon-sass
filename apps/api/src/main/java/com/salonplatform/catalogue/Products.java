package com.salonplatform.catalogue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The salon's products, as F10's checkout reads them (INV-01, decision 095). This is the only way another module
 * reads them. Products are current only: a sale copies what it needs, and what {@link TaxSettings#productPrice}
 * makes of the price under the tax version it was made under.
 *
 * <p>The salon is the request's, never a parameter (docs/engineering/backend.md, "Provided interfaces"). Each call
 * opens its own read-only tenant transaction and does not join the caller's.
 */
public interface Products {

    /**
     * One of the salon's products, sold at checkout or not. Empty for a deleted product, for another salon's product
     * and for an id that names no product: the three cannot be told apart.
     *
     * @throws com.salonplatform.shared.tenancy.TenantNotFoundException when the request names no salon
     */
    Optional<Product> product(UUID id);

    /**
     * The salon's products sold at checkout, by name regardless of case: what the desk may add to a sale.
     *
     * @throws com.salonplatform.shared.tenancy.TenantNotFoundException when the request names no salon
     */
    List<Product> soldAtCheckout();
}
