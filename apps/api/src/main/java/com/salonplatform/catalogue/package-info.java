/**
 * The {@code catalogue} module. From F05-S01: the salon's categories in its own order, and its services with
 * their photos; every change of a service's price, time or buffer creates a new immutable terms version, its time
 * held as ordered segments (decisions 074, 093). From F05-S02: a service's variants and its own deposit, part of its
 * terms, and the salon's Popular services (decision 095). Staff read the catalogue with {@code catalogue.read} and change
 * it with {@code catalogue.price.change}, under {@code /api/v1/catalogue}; the bookable menu and service photos
 * are public there. It provides {@link com.salonplatform.catalogue.ServiceTerms}, which F07 books against, and
 * declares {@link com.salonplatform.catalogue.UpcomingServiceBookings} and
 * {@link com.salonplatform.catalogue.ServiceBookingCounts}, which {@code booking} implements in F07.
 * Each new salon gets the default categories through platform's
 * {@link com.salonplatform.platform.inprocess.SalonBootstrapStep}. Branches are {@code branding}'s, checked
 * through {@link com.salonplatform.branding.SalonBranches}; the salon's currency is {@code platform}'s
 * {@link com.salonplatform.platform.SalonCurrency}; who acts is {@code identity}'s
 * {@link com.salonplatform.identity.Principals}. {@code booking} reads its {@link com.salonplatform.catalogue.ServiceDeposit}.
 *
 * <p>What it owns, the layout inside it and the rules between modules are in
 * {@code docs/engineering/backend.md}.
 */
@ApplicationModule(
        allowedDependencies = {
            "branding",
            "identity",
            "platform",
            "platform :: inprocess",
            "shared :: audit",
            "shared :: images",
            "shared :: lifecycle",
            "shared :: persistence",
            "shared :: tenancy",
            "shared :: web"
        })
package com.salonplatform.catalogue;

import org.springframework.modulith.ApplicationModule;
