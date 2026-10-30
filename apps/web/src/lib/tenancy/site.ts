import { findSalon, type Salon, type SalonLookup } from '@/features/branding/server/salon';
import { markTenant } from '@/lib/observability/request-context';

import { classifyHostname, hasSlugForm, platformDomainOf, salonHostname } from './hostname';

/** What a request's hostname shows. */
export type Site =
  /** The platform domain itself: the platform's own pages (the status page). */
  | { kind: 'platform' }
  /** A salon, active, with its published brand. */
  | { kind: 'salon'; salon: Salon }
  /** No salon: "salon not found". */
  | { kind: 'not-found' }
  /** A suspended salon: "temporarily unavailable". */
  | { kind: 'suspended' }
  /** The salon could not be resolved: the API cannot be reached, or the web app is not configured. */
  | { kind: 'unavailable' };

export type FindSalon = (slug: string, hostname: string) => Promise<SalonLookup>;

/**
 * Works out what a request shows from its `Host` header, and nothing else. A hostname that is not
 * exactly one label in front of the platform domain, or whose label cannot be a slug, is "not
 * found" without asking the API. Nothing is cached: every call reads the settings and asks again.
 *
 * @param hostHeader the request's `Host` header
 * @param find how a salon is looked up; the API by default
 * @param platformDomainSetting the platform domain; `SALON_PLATFORM_DOMAIN` by default
 */
export async function resolveSite(
  hostHeader: string | null,
  find: FindSalon = findSalon,
  platformDomainSetting: string | undefined = process.env.SALON_PLATFORM_DOMAIN,
): Promise<Site> {
  let platformDomain: string;
  try {
    platformDomain = platformDomainOf(platformDomainSetting);
  } catch (error) {
    console.warn(`[tenancy] SALON_PLATFORM_DOMAIN is not usable: ${(error as Error).message}`);
    return { kind: 'unavailable' };
  }

  const hostname = classifyHostname(hostHeader, platformDomain);
  if (hostname.kind === 'platform') {
    return { kind: 'platform' };
  }
  if (hostname.kind === 'none' || !hasSlugForm(hostname.slug)) {
    return { kind: 'not-found' };
  }

  const lookup = await find(hostname.slug, salonHostname(hostname.slug, platformDomain));
  switch (lookup.kind) {
    case 'found':
      // The request's log line names the salon (NFR-16) once the API has said it is one.
      markTenant(hostname.slug);
      return { kind: 'salon', salon: lookup.salon };
    case 'not-found':
      return { kind: 'not-found' };
    case 'suspended':
      markTenant(hostname.slug);
      return { kind: 'suspended' };
    case 'unavailable':
      return { kind: 'unavailable' };
  }
}
