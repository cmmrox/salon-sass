/**
 * The {@code branding} module: a salon's published brand, served at {@code GET /api/v1/salon} for
 * the salon the request's hostname names, and the first brand of a new salon, created through its
 * in-process-only provisioning contract ({@link com.salonplatform.branding.inprocess}).
 *
 * <p>What it owns, the layout inside it and the rules between modules are in
 * {@code docs/engineering/backend.md}.
 */
@ApplicationModule(
        allowedDependencies = {"shared :: lifecycle", "shared :: persistence", "shared :: tenancy", "shared :: web"})
package com.salonplatform.branding;

import org.springframework.modulith.ApplicationModule;
