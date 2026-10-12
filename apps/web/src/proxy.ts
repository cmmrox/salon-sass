import type { NextRequest } from 'next/server';

import { routeRequest } from '@/lib/tenancy/route-request';

/**
 * Tenant routing (docs/engineering/web.md): the request's `Host` header decides whether it shows
 * the platform's own pages, a salon, or a page that says there is none.
 */
export function proxy(request: NextRequest) {
  return routeRequest(request);
}

export const config = {
  // Every path except the framework's own static files and the platform's icons, which are the same
  // on every hostname and say nothing about any salon.
  matcher: ['/((?!_next/static|_next/image|favicon\\.ico|icon\\.svg).*)'],
};
