package com.salonplatform.platform;

/**
 * The current salon's currency (NFR-13), for a module that keeps amounts in it ({@code booking}'s rules,
 * decision 089). The salon is the request's, never a parameter.
 *
 * <p>The tenant role has no grant on the registry; the implementation reads the one column through the narrow
 * {@code salon_currency()} database function, scoped to the salon set in the tenant transaction. Each call is
 * one short read in a read-only tenant transaction it opens itself when the caller has none; call it before
 * opening one.
 */
public interface SalonCurrency {

    /** The currency a salon without one of its own uses: Sri Lankan rupees. */
    String DEFAULT = "LKR";

    /**
     * The salon's ISO 4217 currency code, {@link #DEFAULT} when the operator set none.
     *
     * @throws com.salonplatform.shared.tenancy.TenantNotFoundException when the request names no salon
     */
    String current();
}
