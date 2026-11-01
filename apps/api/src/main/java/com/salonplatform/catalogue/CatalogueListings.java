package com.salonplatform.catalogue;

/**
 * The salon's categories and services, as another module refers to them by id (decision 100): {@code staff} reads
 * them to work out what a person performs when it is read, so a service moved between categories or deleted needs no
 * change in {@code staff}. The salon is the request's, never a parameter. Opens its own read-only tenant transaction.
 */
public interface CatalogueListings {

    /**
     * The current salon's categories, and its services that are not deleted.
     *
     * @throws com.salonplatform.shared.tenancy.TenantNotFoundException when the request names no salon
     */
    CatalogueListing current();
}
