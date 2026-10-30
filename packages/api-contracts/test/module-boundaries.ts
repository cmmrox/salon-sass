// Type-checked by `pnpm typecheck`, never run: an entry @salon/api-contracts/<module> resolves through the
// package's exports and exposes that module's paths and schemas only, so a path or a schema of another
// module does not compile from it. Each @ts-expect-error fails the type check if the line compiles.
import type { ApiClient as BrandingClient, components as branding } from '@salon/api-contracts/branding';
import type { ApiClient as IdentityClient, paths as identity } from '@salon/api-contracts/identity';
import type { paths as platform } from '@salon/api-contracts/platform';
import type { paths as shared } from '@salon/api-contracts/shared';

export type OwnPaths = [
  identity['/api/v1/identity/me'],
  platform['/api/v1/salon/invoices'],
  shared['/api/v1/meta'],
  branding['schemas']['Salon'],
];

// @ts-expect-error the salon's brand is branding's path, not identity's
export type BrandingPathFromIdentity = identity['/api/v1/salon'];

// @ts-expect-error the salon's invoices are platform's, though their path starts with branding's
export type PlatformPathFromShared = shared['/api/v1/salon/invoices'];

// @ts-expect-error a staff account is identity's schema, not branding's
export type IdentitySchemaFromBranding = branding['schemas']['StaffView'];

export function callsOnlyItsOwnPaths(identityClient: IdentityClient, brandingClient: BrandingClient) {
  void brandingClient.GET('/api/v1/salon');
  // @ts-expect-error identity's client cannot call branding's path
  void identityClient.GET('/api/v1/salon');
}
