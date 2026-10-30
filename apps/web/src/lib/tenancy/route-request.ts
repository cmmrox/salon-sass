import { NextResponse, type NextRequest } from 'next/server';

import { createServerApiClient } from '@/lib/api-core/client';
import { hasCredentials, workspaceEntry, type Entry } from '@/features/identity/server/entry';
import { clearCookies, cookieName, endSupportView, requestHost, supportEnded, type Credentials, type Kind } from '@/features/identity/server/identity-web';
import { plainPageHtml, plainPageStatus, type PlainPage } from '@/lib/plain-page/plain-page';
import { hasOperatorCredentials, operatorEntry, type OperatorEntry } from '@/lib/operator/entry';
import { consoleHostname, isCanonicalPath, isConsoleHost, isConsolePath, isUnder } from './console-host';
import { clearOperatorCookies, credentialsOf as operatorCredentialsOf } from '@/lib/operator/server';

import { platformDomainOf } from './hostname';

import { SALON_HEADER, encodeSalon } from '@/features/branding/server/salon-header';
import { resolveSite, type Site } from './site';

/**
 * Retired customer shell path. It remains inaccessible, including all child paths.
 */
export const SALON_SHELL_ROUTE = '/salon-site';

/** A path no page answers, so the application's own not-found page answers it. */
const NOWHERE = '/salon-site/nowhere';

const INTERNAL_PREFIX = SALON_SHELL_ROUTE;

/**
 * Every response about a salon's hostname is for this request only: a status change, a brand
 * change or a salon that appears is seen on the next request, by every cache on the way.
 */
const NOT_STORED = 'private, no-cache, no-store, max-age=0, must-revalidate';

/** Staff sign-in, where a visitor without a usable session belongs. */
const SIGN_IN = '/workspace/sign-in';

/** The protected workspace pages, whose entry is decided here before anything renders (CR-013). */
const PROTECTED = new Set(['/workspace/account', '/workspace/staff', '/workspace/audit', '/workspace/billing', '/workspace/support-access']);

/** The support entry page (F03-S04), where a support view starts and where it lands when it ends. */
const SUPPORT_ENTRY = '/workspace/support';

/** The console's sign-in, and where its root and bare `/console` lead (docs/architecture/operator-console.md). */
const CONSOLE_SIGN_IN = '/console/sign-in';
const CONSOLE_HOME = '/console/salons';
/** The protected console pages: the directories and their detail pages. */
const CONSOLE_PROTECTED = ['/console/salons', '/console/plans', '/console/support'];

/** How entry to a protected console page is decided: the request's operator session, checked with the API. */
export type DecideConsoleEntry = (request: NextRequest) => Promise<OperatorEntry>;

const decideConsoleEntry: DecideConsoleEntry = (request) => {
  let domain: string;
  try { domain = platformDomainOf(process.env.SALON_PLATFORM_DOMAIN); } catch { return Promise.resolve({ kind: 'pending' }); }
  return operatorEntry(operatorCredentialsOf(request), createServerApiClient({ hostname: consoleHostname(domain) }));
};

/**
 * How entry to a protected page is decided: the request's staff session, or its support session
 * (F03-S04), checked with the API.
 */
export type DecideEntry = (request: NextRequest, credentials: Credentials, kind?: Kind) => Promise<Entry>;

function credentialsOf(request: NextRequest): Credentials {
  return {
    access: request.cookies.get(cookieName('staff', 'access'))?.value,
    refresh: request.cookies.get(cookieName('staff', 'refresh'))?.value,
    expiry: request.cookies.get(cookieName('staff', 'expires'))?.value,
  };
}

/** A support session's credential: one access cookie, no refresh. It takes precedence over a staff session. */
function supportCredentialsOf(request: NextRequest): Credentials {
  return { access: request.cookies.get(cookieName('support', 'access'))?.value };
}

const decideEntry: DecideEntry = (request, credentials, kind = 'staff') => {
  const host = requestHost(request.headers.get('host'));
  return workspaceEntry(kind, credentials, host ? createServerApiClient({ hostname: host.hostname }) : null);
};

/**
 * What the proxy does with a request: works out the site from the `Host` header alone. An active
 * salon enters its staff workspace; "salon not found", "temporarily unavailable" and "service
 * unavailable" are answered here, as complete HTML with their status (decision 028), because the
 * framework answers a page reached by a rewrite with 200. Routing only: it is not a
 * security boundary (docs/engineering/web.md); the API and the database enforce access.
 *
 * The salon is never taken from the client: the query, cookies and every header but `Host` are
 * ignored, and the header that hands a resolved salon to the page is removed from every request
 * before the proxy sets it itself.
 *
 * Workspace entry is decided here too (CR-013), because a page's own redirect streams after the
 * framework's shell and would paint a frame first: `/workspace` and every protected page answer a
 * request without a usable staff session with a redirect to sign-in. The pages check the session
 * again; this decision only chooses where the request goes.
 *
 * @param request the incoming request
 * @param resolve how the site is worked out from the `Host` header
 * @param decide how entry to a protected page is decided from the request's staff session
 */
export async function routeRequest(
  request: NextRequest,
  resolve: (hostHeader: string | null) => Promise<Site> = resolveSite,
  decide: DecideEntry = decideEntry,
  decideConsole: DecideConsoleEntry = decideConsoleEntry,
): Promise<NextResponse> {
  const headers = new Headers(request.headers);
  headers.delete(SALON_HEADER);

  const path = request.nextUrl.pathname;
  // This retired namespace has no tenant surface. Reject it before salon lookup so even an
  // unavailable API cannot turn removed customer operations into upstream calls or 503s.
  if (path === '/identity-web/customer' || path.startsWith('/identity-web/customer/')) {
    return NextResponse.json({ code: 'not_found' }, { status: 404, headers: { 'Cache-Control': NOT_STORED } });
  }
  if (path === INTERNAL_PREFIX || path.startsWith(`${INTERNAL_PREFIX}/`)) {
    return rewrite(request, NOWHERE, headers);
  }

  // A path not in its one canonical form (a path parameter, a percent-escape, an empty segment)
  // could be routed while a check here reads it as something else: it is nobody's page.
  if (!isCanonicalPath(path)) {
    return rewrite(request, NOWHERE, headers);
  }
  let platformDomain: string | null = null;
  try { platformDomain = platformDomainOf(process.env.SALON_PLATFORM_DOMAIN); } catch { platformDomain = null; }
  if (platformDomain && isConsoleHost(request.headers.get('host'), platformDomain)) {
    return routeConsole(request, path, headers, decideConsole);
  }
  // The console's pages and BFF exist on the exact console hostname only, before any lookup.
  if (isConsolePath(path)) {
    return rewrite(request, NOWHERE, headers);
  }

  const site = await resolve(request.headers.get('host'));
  // The Owner's export download page and its BFF (F03-S03) exist on the bare platform host only.
  const exportPath = isUnder(path, '/exports') || isUnder(path, '/platform-web');
  switch (site.kind) {
    case 'platform': {
      const response = NextResponse.next({ request: { headers } });
      if (exportPath) response.headers.set('Cache-Control', NOT_STORED);
      return response;
    }
    case 'not-found':
      return answer('salon-not-found');
    case 'suspended':
      return answer('salon-unavailable');
    case 'unavailable':
      return answer('service-unavailable');
    case 'salon': {
      if (exportPath) return rewrite(request, NOWHERE, headers);
      headers.set(SALON_HEADER, encodeSalon(site.salon));
      if (path === '/' || path === '/sign-in' || path === '/account') {
        return redirect(request, path === '/' ? '/workspace' : SIGN_IN);
      }
      const support = supportCredentialsOf(request);
      // A support view that ended moments ago (BUG-F03-005): its cookies may already be gone, cleared
      // by a refused prefetch or read, but a page without any session still belongs on the notice.
      const ended = supportEnded(request);
      if (path === '/workspace') {
        if (hasCredentials(support) || hasCredentials(credentialsOf(request))) return redirect(request, '/workspace/account');
        return ended ? redirect(request, SUPPORT_ENTRY, 'ended') : redirect(request, SIGN_IN);
      }
      if (PROTECTED.has(path) && hasCredentials(support)) {
        // The support view (F03-S04): an ended, expired or revoked session leaves it for the entry
        // page, its cookies cleared host-only; a staff session in the same browser is left alone.
        if ((await decide(request, support, 'support')).kind === 'signed-out') {
          const response = redirect(request, SUPPORT_ENTRY, 'ended');
          endSupportView(response);
          return response;
        }
      } else if (PROTECTED.has(path)) {
        const credentials = credentialsOf(request);
        if (!hasCredentials(credentials)) return ended ? redirect(request, SUPPORT_ENTRY, 'ended') : redirect(request, SIGN_IN);
        if ((await decide(request, credentials)).kind === 'signed-out') {
          // The same clearing the BFF does for a session that cannot renew (BUG-F02-011): the
          // revoked family's credentials leave the browser with the redirect, host-only.
          const response = redirect(request, SIGN_IN);
          clearCookies(response, 'staff');
          return response;
        }
      }
      // Any other path on a salon's hostname is the application's, with the salon handed on.
      const response = NextResponse.next({ request: { headers } });
      response.headers.set('Cache-Control', NOT_STORED);
      return response;
    }
  }
}

/**
 * The console hostname: never a salon, never resolved as one. Its root and `/console` lead to the
 * salon directory; a protected page without a usable operator session is redirected to sign-in
 * before anything renders; every path that is not the console's is not found.
 */
async function routeConsole(request: NextRequest, path: string, headers: Headers, decide: DecideConsoleEntry): Promise<NextResponse> {
  if (path === '/' || path === '/console' || path === '/console/') return redirect(request, CONSOLE_HOME);
  if (!isConsolePath(path)) return rewrite(request, NOWHERE, headers);
  if (CONSOLE_PROTECTED.some((prefix) => isUnder(path, prefix))) {
    const credentials = operatorCredentialsOf(request);
    if (!hasOperatorCredentials(credentials)) return redirect(request, CONSOLE_SIGN_IN);
    if ((await decide(request)).kind === 'signed-out') {
      const response = redirect(request, CONSOLE_SIGN_IN);
      clearOperatorCookies(response);
      return response;
    }
  }
  const response = NextResponse.next({ request: { headers } });
  response.headers.set('Cache-Control', NOT_STORED);
  return response;
}

function redirect(request: NextRequest, pathname: string, fragment = ''): NextResponse {
  // The same origin as the request; nothing of the query is carried to the destination. A fixed
  // fragment may name the state the destination shows (the support entry page's `#ended`).
  const destination = request.nextUrl.clone();
  destination.pathname = pathname;
  destination.search = '';
  destination.hash = fragment;
  const response = NextResponse.redirect(destination, 307);
  response.headers.set('Cache-Control', NOT_STORED);
  return response;
}

function rewrite(request: NextRequest, pathname: string, headers: Headers): NextResponse {
  // The same origin as the request, so the rewrite stays inside this application.
  const destination = request.nextUrl.clone();
  destination.pathname = pathname;
  destination.search = '';
  const response = NextResponse.rewrite(destination, { request: { headers } });
  response.headers.set('Cache-Control', NOT_STORED);
  return response;
}

function answer(page: PlainPage): NextResponse {
  return new NextResponse(plainPageHtml(page), {
    status: plainPageStatus(page),
    headers: { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': NOT_STORED },
  });
}
