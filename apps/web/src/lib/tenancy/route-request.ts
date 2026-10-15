import { NextResponse, type NextRequest } from 'next/server';

import { plainPageHtml, plainPageStatus, type PlainPage } from '@/lib/plain-page/plain-page';

import { SALON_HEADER, encodeSalon } from './salon-header';
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
  // This retired namespace has no tenant surface. Reject it before salon lookup so even an
  // unavailable API cannot turn removed customer operations into upstream calls or 503s.
  if (path === '/identity-web/customer' || path.startsWith('/identity-web/customer/')) {
    return NextResponse.json({ code: 'not_found' }, { status: 404, headers: { 'Cache-Control': NOT_STORED } });
  }
  if (path === INTERNAL_PREFIX || path.startsWith(`${INTERNAL_PREFIX}/`)) {
    return rewrite(request, NOWHERE, headers);
  }

  const site = await resolve(request.headers.get('host'));
  switch (site.kind) {
    case 'platform':
      return NextResponse.next({ request: { headers } });
    case 'not-found':
      return answer('salon-not-found');
    case 'suspended':
      return answer('salon-unavailable');
    case 'unavailable':
      return answer('service-unavailable');
    case 'salon': {
      headers.set(SALON_HEADER, encodeSalon(site.salon));
      if (path === '/' || path === '/sign-in' || path === '/account') {
        const destination = request.nextUrl.clone();
        destination.pathname = path === '/' ? '/workspace' : '/workspace/sign-in';
        destination.search = '';
        const response = NextResponse.redirect(destination, 307);
        response.headers.set('Cache-Control', NOT_STORED);
        return response;
      }
      // Any other path on a salon's hostname is the application's, with the salon handed on.
      const response = NextResponse.next({ request: { headers } });
      response.headers.set('Cache-Control', NOT_STORED);
      return response;
    }
  }
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
