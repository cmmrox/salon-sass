import type { NextRequest } from 'next/server';

import { routeRequest } from '@/app/_proxy/route-request';
import { contentSecurityPolicy, newNonce, SECURITY_HEADERS } from '@/lib/http/security-headers';

/**
 * Tenant routing (docs/engineering/web.md): the request's `Host` header decides whether it shows
 * the platform's own pages, a salon, or a page that says there is none. Every answer carries the
 * security headers and a content security policy with a nonce made for this request alone, which
 * Next.js reads from the request's own policy header and puts on the page's scripts (web M7).
 */
export async function proxy(request: NextRequest) {
  const nonce = newNonce();
  const policy = contentSecurityPolicy(nonce, process.env.NODE_ENV === 'development');
  const response = await routeRequest(request, undefined, undefined, undefined, { policy, nonce });
  response.headers.set('Content-Security-Policy', policy);
  for (const [name, value] of Object.entries(SECURITY_HEADERS)) response.headers.set(name, value);
  return response;
}

export const config = {
  // Every path except the framework's own static files, the platform's icons, images, brand fallbacks and fonts, and
  // the readiness check (app/healthz), which are the same on every hostname and say nothing about any salon: they cost
  // no salon lookup (web M6), and next.config.ts lets browsers keep them.
  matcher: ['/((?!_next/static|_next/image|favicon\\.ico|icon\\.svg|fonts/poppins/|images/|icons/|brand/|healthz).*)'],
};
