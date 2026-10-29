/**
 * The {@code identity} module: staff accounts, roles and sessions, and customers' one-time-code sign-in.
 * Other modules ask who the current request acts as through {@link com.salonplatform.identity.Principals};
 * platform provisions a salon's first Owner and closes its sessions through the in-process-only
 * contracts of {@link com.salonplatform.identity.inprocess}.
 *
 * <p>What it owns, the layout inside it and the rules between modules are in
 * {@code docs/engineering/backend.md}.
 */
@ApplicationModule(
        allowedDependencies = {
            "shared :: audit",
            "shared :: entitlements",
            "shared :: infrastructure",
            "shared :: jobs",
            "shared :: lifecycle",
            "shared :: mapping",
            "shared :: outbox",
            "shared :: tenancy",
            "shared :: time",
            "shared :: web"
        })
package com.salonplatform.identity;

import org.springframework.modulith.ApplicationModule;
