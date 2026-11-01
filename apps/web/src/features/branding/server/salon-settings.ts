import 'server-only';

import { createApiClient, type components } from '@salon/api-contracts/branding';

import { createServerApiClient } from '@/lib/api-core/client';

type Call = { headers: Record<string, string | undefined>; signal: AbortSignal };
type ProfileInput = components['schemas']['SalonProfileInput'];
type BranchInput = components['schemas']['SalonProfileBranchInput'];
type BrandInput = components['schemas']['SalonBrandInput'];

const text = (value: unknown): string | null => (typeof value === 'string' ? value : null);
const record = (value: unknown): Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value) ? (value as Record<string, unknown>) : {};

/**
 * A profile from the browser, rebuilt from the contract's own fields only: nothing else the browser sent
 * reaches the API, and anything of the wrong type goes as null or empty for the API to refuse or clear.
 * A number stays a number; the API checks its range.
 */
function profileInput(value: unknown): ProfileInput {
  const profile = record(value);
  const links = record(profile.links);
  const branches = Array.isArray(profile.branches) ? profile.branches : [];
  return {
    description: text(profile.description),
    links: { instagram: text(links.instagram), facebook: text(links.facebook), website: text(links.website) },
    branches: branches.map((entry): BranchInput => {
      const branch = record(entry);
      const location = branch.location === null ? null : record(branch.location);
      return {
        id: text(branch.id) ?? '',
        address: text(branch.address),
        location: location && {
          latitude: location.latitude as number,
          longitude: location.longitude as number,
        },
        phone: text(branch.phone),
        whatsapp: text(branch.whatsapp),
      };
    }),
  };
}

/** A brand from the browser, rebuilt from the contract's own fields only, as the profile is. */
function brandInput(value: unknown): BrandInput {
  const brand = record(value);
  return {
    name: text(brand.name) ?? '',
    tagline: text(brand.tagline),
    colour: text(brand.colour) ?? '',
    logo: text(brand.logo),
    cover: text(brand.cover),
  };
}

/**
 * The branding paths the workspace BFF reaches for a settings user, under the salon's hostname: the profile
 * draft (F04-S01) and the brand draft with its images (F04-S02). The BFF checks the session and
 * the permission before any of them. Null when the API is not configured.
 */
export function salonSettingsFor(hostname: string) {
  const client = createServerApiClient(createApiClient, { hostname });
  if (!client) return null;
  return {
    profileDraft: (call: Call) => client.GET('/api/v1/salon/profile/draft', call),
    saveProfileDraft: (call: Call, version: unknown, profile: unknown) =>
      client.PUT('/api/v1/salon/profile/draft', {
        ...call,
        body: { version: version as number, profile: profileInput(profile) },
      }),
    publishProfile: (call: Call, version: unknown) =>
      client.POST('/api/v1/salon/profile/publication', { ...call, body: { version: version as number } }),
    discardProfileDraft: (call: Call, version: unknown) =>
      client.DELETE('/api/v1/salon/profile/draft', { ...call, body: { version: version as number } }),
    brandDraft: (call: Call) => client.GET('/api/v1/salon/brand/draft', call),
    saveBrandDraft: (call: Call, version: unknown, brand: unknown) =>
      client.PUT('/api/v1/salon/brand/draft', {
        ...call,
        body: { version: version as number, brand: brandInput(brand) },
      }),
    resetBrandDraft: (call: Call, version: unknown) =>
      client.POST('/api/v1/salon/brand/draft/reset', { ...call, body: { version: version as number } }),
    publishBrand: (call: Call, version: unknown) =>
      client.POST('/api/v1/salon/brand/publication', { ...call, body: { version: version as number } }),
    discardBrandDraft: (call: Call, version: unknown) =>
      client.DELETE('/api/v1/salon/brand/draft', { ...call, body: { version: version as number } }),
    uploadBrandImage: (call: Call, kind: string, file: Blob) => {
      // The form is built here from the two fields the contract names; nothing else of the browser's goes on.
      const form = new FormData();
      form.append('kind', kind);
      form.append('file', file, 'upload');
      return client.POST('/api/v1/salon/brand/images', {
        ...call,
        body: form as unknown as { kind: 'logo' | 'cover'; file: string },
        bodySerializer: (body) => body as unknown as FormData,
      });
    },
  };
}
