import { NextResponse, type NextRequest } from 'next/server';

import { SALON_HEADER, encodeSalon } from './salon-header';
import { resolveSite, type Site } from './site';

/**
 * The pages a salon's hostname is rewritten to. They are reached only through `proxy.ts`; a request
 * for one of these paths from outside is answered as a page that does not exist.
 */
export const SITE_ROUTES = {
  /** An active salon's shell, at `/` on its hostname. */
  shell: '/salon-site',
  /** "Salon not found". */
  notFound: '/salon-site/missing',
  /** "Temporarily unavailable": a suspended salon. */
  suspended: '/salon-site/suspended',
  /** "Service unavailable": the salon could not be resolved. */
  unavailable: '/salon-site/unavailable',
} as const;

/** A path no page answers, so the application's own not-found page answers it. */
const NOWHERE = '/salon-site/nowhere';

const INTERNAL_PREFIX = SITE_ROUTES.shell;

/**
 * Every response about a salon's hostname is for this request only: a status change, a brand
 * change or a salon that appears is seen on the next request, by every cache on the way.
 */
const NOT_STORED = 'private, no-cache, no-store, max-age=0, must-revalidate';

/**
 * What the proxy does with a request: works out the site from the `Host` header alone, and
 * rewrites to the page for it with the status that page answers with. Routing only: it is not a
 * security boundary (docs/engineering/web.md); the API and the database enforce access.
 *
 * The salon is never taken from the client: the query, cookies and every header but `Host` are
 * ignored, and the header that hands a resolved salon to the page is removed from every request
 * before the proxy sets it itself.
 *
 * @param request the incoming request
 * @param resolve how the site is worked out from the `Host` header
 */
export async function routeRequest(
  request: NextRequest,
  resolve: (hostHeader: string | null) => Promise<Site> = resolveSite,
): Promise<NextResponse> {
  const headers = new Headers(request.headers);
  headers.delete(SALON_HEADER);

  const path = request.nextUrl.pathname;
  if (path === INTERNAL_PREFIX || path.startsWith(`${INTERNAL_PREFIX}/`)) {
    return rewrite(request, NOWHERE, 404, headers);
  }

  const site = await resolve(request.headers.get('host'));
  switch (site.kind) {
    case 'platform':
      return NextResponse.next({ request: { headers } });
    case 'not-found':
      return rewrite(request, SITE_ROUTES.notFound, 404, headers);
    case 'suspended':
      return rewrite(request, SITE_ROUTES.suspended, 503, headers);
    case 'unavailable':
      return rewrite(request, SITE_ROUTES.unavailable, 503, headers);
    case 'salon': {
      headers.set(SALON_HEADER, encodeSalon(site.salon));
      if (path === '/') {
        return rewrite(request, SITE_ROUTES.shell, 200, headers);
      }
      // Any other path on a salon's hostname is the application's, with the salon handed on.
      const response = NextResponse.next({ request: { headers } });
      response.headers.set('Cache-Control', NOT_STORED);
      return response;
    }
  }
}

function rewrite(request: NextRequest, pathname: string, status: number, headers: Headers): NextResponse {
  // The same origin as the request, so the rewrite stays inside this application.
  const destination = request.nextUrl.clone();
  destination.pathname = pathname;
  destination.search = '';
  const response = NextResponse.rewrite(destination, { status, request: { headers } });
  response.headers.set('Cache-Control', NOT_STORED);
  return response;
}
