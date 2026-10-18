/**
 * The {@code platform} module: the tenant registry, operator accounts and sessions, immutable plan
 * definitions, salon provisioning and the platform audit. Its tables have no {@code tenant_id}. The
 * tenant role reads the registry only through {@code tenant_by_slug()} and its siblings (answering
 * {@code shared}'s {@link com.salonplatform.shared.tenancy.TenantDirectory}); the operator console's
 * services use the separately configured platform runtime role
 * ({@link com.salonplatform.shared.infrastructure.PlatformDatabase}), and create a salon's first Owner
 * and brand only through identity's and branding's public provisioning facades.
 *
 * <p>What it owns, the layout inside it and the rules between modules are in
 * {@code docs/engineering/backend.md}; the console's policies in
 * {@code docs/architecture/operator-console.md}.
 */
package com.salonplatform.platform;
