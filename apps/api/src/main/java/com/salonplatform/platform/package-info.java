/**
 * The {@code platform} module: the tenant registry, operator accounts and sessions, immutable plan
 * definitions, salon provisioning and the platform audit. Its tables have no {@code tenant_id}. The
 * tenant role reads the registry only through {@code tenant_by_slug()} and its siblings (answering
 * {@code shared}'s {@link com.salonplatform.shared.tenancy.TenantDirectory}); the operator console's
 * services use the separately configured platform runtime role
 * ({@link com.salonplatform.shared.persistence.PlatformDatabase}), and create a salon's first Owner
 * and brand only through identity's and branding's in-process-only provisioning contracts.
 *
 * <p>What it owns, the layout inside it and the rules between modules are in
 * {@code docs/engineering/backend.md}; the console's policies in
 * {@code docs/architecture/operator-console.md}.
 */
@ApplicationModule(
        allowedDependencies = {
            "identity",
            "identity :: inprocess",
            "branding :: inprocess",
            "shared :: audit",
            "shared :: entitlements",
            "shared :: jobs",
            "shared :: lifecycle",
            "shared :: observability",
            "shared :: outbox",
            "shared :: persistence",
            "shared :: secrets",
            "shared :: tenancy",
            "shared :: time",
            "shared :: web"
        })
package com.salonplatform.platform;

import org.springframework.modulith.ApplicationModule;
