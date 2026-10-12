/**
 * The {@code platform} module: the tenant registry. Its tables have no {@code tenant_id} and cannot
 * be read with the application's database role; the registry is read through
 * {@code tenant_by_slug()} only, and answers {@code shared}'s
 * {@link com.salonplatform.shared.tenancy.TenantDirectory}.
 *
 * <p>What it owns, the layout inside it and the rules between modules are in
 * {@code docs/engineering/backend.md}.
 */
package com.salonplatform.platform;
