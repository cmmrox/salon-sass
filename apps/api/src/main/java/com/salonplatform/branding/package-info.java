/**
 * The {@code branding} module: a salon's published brand and business profile, served at
 * {@code GET /api/v1/salon} for the salon the request's hostname names; the profile's draft, which the
 * salon's settings staff save, publish and discard; the salon's branches, which other modules check
 * through {@link com.salonplatform.branding.SalonBranches}; and the first brand and default branch of a
 * new salon, created through its in-process-only provisioning contract
 * ({@link com.salonplatform.branding.inprocess}).
 *
 * <p>What it owns, the layout inside it and the rules between modules are in
 * {@code docs/engineering/backend.md}.
 */
@ApplicationModule(
        allowedDependencies = {
            "identity",
            "shared :: audit",
            "shared :: lifecycle",
            "shared :: persistence",
            "shared :: tenancy",
            "shared :: web"
        })
package com.salonplatform.branding;

import org.springframework.modulith.ApplicationModule;
