/**
 * Which salon, if any, a request's `Host` header names: `<slug>.<platform domain>`, the port
 * ignored, compared without regard to case. Only exactly one label in front of the platform domain
 * names a salon; the platform domain itself, two labels in front of it, and a hostname that merely
 * contains it name none (docs/architecture/multi-tenancy.md, "Salons and their hostnames").
 *
 * The same rule as the API's `PlatformHostname`, with the same table of cases in the tests. Whether
 * the label is a salon at all is the API's to say.
 */

/** What a hostname is to the web app. */
export type HostnameKind =
  /** The platform domain itself: the platform's own pages. */
  | { kind: 'platform' }
  /** Exactly one label in front of the platform domain: the API is asked whether it is a salon. */
  | { kind: 'candidate'; slug: string }
  /** Anything else. It names no salon, and the API is not asked. */
  | { kind: 'none' };

/**
 * The platform domain, normalised: trimmed and in lower case.
 *
 * @throws Error when it is not a hostname such as `localhost`: empty, with a leading or trailing
 *   dot, or with a port
 */
export function platformDomainOf(value: string | undefined): string {
  const domain = (value ?? '').trim().toLowerCase();
  if (domain === '' || domain.startsWith('.') || domain.endsWith('.') || domain.includes(':')) {
    throw new Error(`the platform domain must be a hostname such as 'localhost', not '${value ?? ''}'`);
  }
  return domain;
}

/**
 * Reads a `Host` header.
 *
 * @param hostHeader the header exactly as received, or null when there was none
 * @param platformDomain the platform domain, as {@link platformDomainOf} returns it
 */
export function classifyHostname(hostHeader: string | null | undefined, platformDomain: string): HostnameKind {
  if (hostHeader == null) {
    return { kind: 'none' };
  }
  const host = withoutPort(hostHeader.trim()).toLowerCase();
  if (host === platformDomain) {
    return { kind: 'platform' };
  }
  const suffix = `.${platformDomain}`;
  if (!host.endsWith(suffix)) {
    return { kind: 'none' };
  }
  const label = host.slice(0, host.length - suffix.length);
  if (label === '' || label.includes('.')) {
    return { kind: 'none' };
  }
  return { kind: 'candidate', slug: label };
}

/**
 * The candidate slug a hostname names, or null: the API's `PlatformHostname.slugOf`.
 */
export function slugOf(hostHeader: string | null | undefined, platformDomain: string): string | null {
  const hostname = classifyHostname(hostHeader, platformDomain);
  return hostname.kind === 'candidate' ? hostname.slug : null;
}

/**
 * Whether a label has the form of a slug: 3 to 30 lowercase letters, digits and hyphens, beginning
 * and ending with a letter or digit. The database refuses any other, so a label of another form is
 * never a salon and is answered without asking the API.
 */
export function hasSlugForm(label: string): boolean {
  return /^[a-z0-9][a-z0-9-]{1,28}[a-z0-9]$/.test(label);
}

/** The hostname the web app forwards to the API for a salon: canonical, without a port. */
export function salonHostname(slug: string, platformDomain: string): string {
  return `${slug}.${platformDomain}`;
}

function withoutPort(host: string): string {
  if (host.startsWith('[')) {
    // An IPv6 literal is never a salon's hostname; keep it whole so that it cannot match.
    return host;
  }
  const colon = host.indexOf(':');
  return colon < 0 ? host : host.slice(0, colon);
}
