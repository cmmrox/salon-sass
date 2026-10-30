import { platformDomainOf } from './hostname';

/**
 * The operator console's one hostname (docs/architecture/operator-console.md):
 * `console.<platform domain>`, compared without regard to case, with no port or one valid port.
 * Any other host (a salon, the bare platform domain, another reserved name, a lookalike or a nested
 * label) is not the console. Read from `Host` alone; forwarded headers never choose it.
 */
export function consoleHostname(platformDomain: string): string {
  return `console.${platformDomain}`;
}

/** Whether a raw `Host` header names the console exactly. */
export function isConsoleHost(raw: string | null | undefined, platformDomain: string): boolean {
  if (!raw || !/^[a-zA-Z0-9.-]+(?::[0-9]{1,5})?$/.test(raw)) return false;
  const [name, port] = raw.toLowerCase().split(':');
  if (port !== undefined && (Number(port) < 1 || Number(port) > 65535)) return false;
  return name === consoleHostname(platformDomain);
}

/** The console host of a request and its exact origin, or null when the request is not on it. */
export function consoleRequestHost(raw: string | null): { hostname: string; origin: string } | null {
  let platformDomain: string;
  try {
    platformDomain = platformDomainOf(process.env.SALON_PLATFORM_DOMAIN);
  } catch {
    return null;
  }
  if (!raw || !isConsoleHost(raw, platformDomain)) return null;
  const scheme = process.env.SALON_WEB_LOCAL_HTTP === 'true' ? 'http' : 'https';
  return { hostname: consoleHostname(platformDomain), origin: `${scheme}://${raw.toLowerCase()}` };
}

/**
 * Whether a path is the console's own (pages or BFF). Only a canonical path counts: no percent
 * escape, path parameter, backslash, or empty or dot segment, so no spelling of a console path can
 * be routed while a check reads it as something else.
 */
export function isCanonicalPath(path: string): boolean {
  if (!path.startsWith('/') || /[;%\\]/.test(path)) return false;
  const segments = path.slice(1).split('/');
  return segments.every(
    (segment, index) =>
      !(segment === '.' || segment === '..' || (segment === '' && index < segments.length - 1 && segments.length > 1)),
  );
}

/** Whether a canonical path is the prefix itself or below it. */
export function isUnder(path: string, prefix: string): boolean {
  return isCanonicalPath(path) && (path === prefix || path.startsWith(`${prefix}/`));
}

/** The console's page and BFF namespaces. */
export function isConsolePath(path: string): boolean {
  return isUnder(path, '/console') || isUnder(path, '/operator-web');
}
