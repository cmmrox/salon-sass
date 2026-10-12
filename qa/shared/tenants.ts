/**
 * The three synthetic salons of docs/qa/test-plan.md, "The synthetic salons". The product's own
 * local seed creates them; these constants must match it, and a test that finds they do not has
 * found a defect in the seed or a document that moved without this file.
 *
 * The one place a salon's id, slug, name, colour or hostname is written. Playwright imports this
 * file, k6 imports it too, and the shell checks read it through shared/tenants-shell.mjs. So it
 * imports nothing and uses only syntax that disappears when the types are removed.
 */
export type Salon = {
  /** The subdomain: `alpha` in `alpha.localhost`. */
  slug: string;
  /** The tenant id. Never shown to a caller of the API. */
  id: string;
  /** The published display name. */
  name: string;
  /** The brand colour, `#RRGGBB`. */
  brandColour: string;
  /** The hostname on the local platform domain, without a port. */
  hostname: string;
};

/** The local platform domain (SALON_PLATFORM_DOMAIN in the product's .env.example). */
export const PLATFORM_DOMAIN = 'localhost';

function salon(slug: string, id: string, name: string, brandColour: string): Salon {
  return { slug, id, name, brandColour, hostname: `${slug}.${PLATFORM_DOMAIN}` };
}

/** The salon under test. */
export const alpha: Salon = salon('alpha', 'a1a1a1a1-0000-4000-8000-000000000001', 'Alpha Studio', '#D6086A');

/** The other salon, for every isolation test: a dark colour and a long name. */
export const beta: Salon = salon(
  'beta',
  'b2b2b2b2-0000-4000-8000-000000000002',
  'Beta Hair, Beauty & Wellness Lounge of Colombo Seven',
  '#1F2937',
);

/** The edge salon: a short name and a light colour. */
export const gamma: Salon = salon('gamma', 'c3c3c3c3-0000-4000-8000-000000000003', 'Gamma', '#FDE68A');

export const salons: Salon[] = [alpha, beta, gamma];

/** The tenant id of "no salon": what a session with no tenant carries, and no row may. */
export const NIL_TENANT_ID = '00000000-0000-0000-0000-000000000000';

/**
 * Slugs that can never be a salon, from docs/architecture/multi-tenancy.md, "Salons and their
 * hostnames".
 */
export const RESERVED_SLUGS: string[] = [
  'admin', 'api', 'app', 'apps', 'assets', 'auth', 'cdn', 'console', 'docs', 'help', 'login',
  'mail', 'staging', 'static', 'status', 'support', 'www',
];

/**
 * A service's address with a hostname in place of its own: `http://localhost:8090` and
 * `alpha.localhost` give `http://alpha.localhost:8090`. The scheme, the port and any path stay.
 */
export function onHostname(serviceUrl: string, hostname: string): string {
  const match = /^([a-z][a-z0-9+.-]*:\/\/)(\[[^\]]*\]|[^/:?#]*)(.*)$/i.exec(serviceUrl);
  if (!match) {
    throw new Error(`not an absolute address: ${serviceUrl}`);
  }
  return `${match[1]}${hostname}${match[3]}`;
}

/** A salon's address on a service: the salon's hostname and the service's port. */
export function salonUrl(of: Salon, serviceUrl: string): string {
  return onHostname(serviceUrl, of.hostname);
}

/** Every value that identifies a salon, for asserting that none of them appears somewhere. */
export function identifiersOf(of: Salon): string[] {
  return [of.id, of.slug, of.name, of.brandColour];
}
