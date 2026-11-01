import 'server-only';

import { createApiClient, type components } from '@salon/api-contracts/branding';

import { createServerApiClient } from '@/lib/api-core/client';

type Call = { headers: Record<string, string | undefined>; signal: AbortSignal };
type ProfileInput = components['schemas']['SalonProfileInput'];
type BranchInput = components['schemas']['SalonProfileBranchInput'];

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

/**
 * The branding paths the workspace BFF reaches for a settings user (F04-S01), under the salon's
 * hostname: the profile draft, and saving, publishing and discarding it. The BFF checks the session and
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
  };
}
