import type { ApiClient, Salon as ContractSalon } from '@salon/api-contracts';

import { DEFAULT_BRAND_COLOUR, isHexColour } from '@/styles/brand';

import { requestSignal, createServerApiClient } from './client';
import { describeFailure } from './failure';

/**
 * What the web app carries about a salon: its public brand. The contract's salon holds more (its
 * time zone, from F01-S04), which no screen uses yet; a screen that shows a time takes it then.
 */
export type Salon = Pick<ContractSalon, 'slug' | 'name' | 'brandColour' | 'timeZone'>;

/** What the API said about the salon a hostname names. */
export type SalonLookup =
  /** An active salon, and its published brand. */
  | { kind: 'found'; salon: Salon }
  /** No such salon: `404 not_found`. */
  | { kind: 'not-found' }
  /** The salon is suspended: `403 salon_suspended`. */
  | { kind: 'suspended' }
  /** The API could not say: not configured, unreachable, too slow, or an answer outside the contract. */
  | { kind: 'unavailable' };

/**
 * Asks the API for the published brand of the salon with this slug, by sending its hostname
 * (`GET /api/v1/salon`). Never throws, and never caches: every call asks the API.
 *
 * Only an answer that is exactly the contract's is believed. In particular a brand is returned
 * only when it is the brand of the salon that was asked for; anything else is "unavailable".
 * The one exception is the colour (CR-013 AC-10): a salon whose colour is missing or not
 * `#RRGGBB` is still that salon, with the prototype's default accent, so its staff can sign in.
 *
 * @param slug the salon's slug, as the hostname names it
 * @param hostname the salon's hostname, `<slug>.<platform domain>`, sent to the API as `Host`
 * @param client the client to use; by default one for the configured API address and this hostname
 */
export async function findSalon(
  slug: string,
  hostname: string,
  client: ApiClient | null = createServerApiClient({ hostname }),
): Promise<SalonLookup> {
  if (!client) {
    return unavailable(slug, 'SALON_API_BASE_URL is not set');
  }
  try {
    const { data, error, response } = await client.GET('/api/v1/salon', {
      signal: requestSignal(),
    });
    if (response.status === 200) {
      return isSalon(data, slug)
        ? { kind: 'found', salon: { slug: data.slug, name: data.name, brandColour: isHexColour(data.brandColour) ? data.brandColour : DEFAULT_BRAND_COLOUR, timeZone: data.timeZone } }
        : unavailable(slug, 'the API answered with something that is not this salon');
    }
    const code = typeof error === 'object' && error !== null ? (error as { code?: unknown }).code : undefined;
    if (response.status === 404 && code === 'not_found') {
      return { kind: 'not-found' };
    }
    if (response.status === 403 && code === 'salon_suspended') {
      return { kind: 'suspended' };
    }
    return unavailable(slug, `the API answered ${response.status}${typeof code === 'string' ? ` ${code}` : ''}`);
  } catch (failure) {
    return unavailable(slug, describeFailure(failure));
  }
}

function isSalon(value: unknown, slug: string): value is Omit<Salon, 'brandColour'> & { brandColour?: unknown } {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const candidate = value as Record<string, unknown>;
  return (
    candidate.slug === slug &&
    typeof candidate.name === 'string' &&
    candidate.name.trim() !== '' &&
    typeof candidate.timeZone === 'string'
  );
}

function unavailable(slug: string, reason: string): SalonLookup {
  console.warn(`[api] salon '${slug}' is unavailable: ${reason}`);
  return { kind: 'unavailable' };
}
