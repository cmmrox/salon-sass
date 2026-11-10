// @vitest-environment node
import { IncomingMessage, ServerResponse } from 'node:http';
import { Socket } from 'node:net';
import { NextRequest, type NextResponse } from 'next/server';
import { NodeNextRequest, NodeNextResponse } from 'next/dist/server/base-http/node';
import { sendResponse } from 'next/dist/server/send-response';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { BRAND_IMAGE_CACHE, NOT_STORED, SERVICE_PHOTO_CACHE, TEAM_PHOTO_CACHE } from '@/lib/http/photo-cache';
import { routeRequest } from './route-request';
import type { Site } from './site';

/**
 * BUG-F05-003 (CR-030: F04-S02 AC-13, F05-S01 AC-21, F06-S01 AC-18): the header that reaches the browser for a salon's
 * public photo is the route handler's, while every other answer on a salon's hostname keeps the proxy's `no-store`.
 *
 * How Next.js joins the two, in the installed version: `server/lib/router-server.js` sets every header of the proxy's
 * `NextResponse.next()` on the outgoing response with `res.setHeader` before the route runs (after
 * `lib/router-utils/resolve-routes.js` drops the `x-middleware-*` ones), and `server/send-response.js`, through which
 * every route handler's answer is written (`build/templates/app-route.js`), appends a handler's header only when the
 * response has none of that name yet. So a `Cache-Control` the proxy sets is the one sent, whatever the handler says.
 * {@link served} does what router-server does and then calls Next.js's own `sendResponse`.
 */
const ID = '0b6f9f43-5a1d-4c5e-9a2b-3f3f6e1d2c10';
const alpha = { slug: 'alpha', name: 'Alpha Salon', brandColour: '#D6086A', timeZone: 'Asia/Colombo' };
const resolving = (site: Site) => vi.fn<(host: string | null) => Promise<Site>>(async () => site);
const request = (path: string) =>
  new NextRequest(`http://alpha.localhost:3100${path}`, { headers: { host: 'alpha.localhost:3100' } });

beforeEach(() => {
  vi.stubEnv('SALON_PLATFORM_DOMAIN', 'localhost');
});
afterEach(() => {
  vi.unstubAllEnvs();
});

/** The proxy's answer, then the handler's, composed as Next.js composes them; the Cache-Control the browser gets. */
async function served(proxied: NextResponse, handler: Response): Promise<string | undefined> {
  const raw = new ServerResponse(new IncomingMessage(new Socket()));
  const skipped = ['content-length', 'x-middleware-rewrite', 'x-middleware-redirect', 'x-middleware-refresh'];
  proxied.headers.forEach((value, name) => {
    if (!name.startsWith('x-middleware-') && !skipped.includes(name)) raw.setHeader(name, value);
  });
  const incoming = new IncomingMessage(new Socket());
  // HEAD: Next.js writes the same headers and ends the answer without piping a body into this socket-less response.
  incoming.method = 'HEAD';
  const req = new NodeNextRequest(incoming as never);
  await sendResponse(req, new NodeNextResponse(raw), handler);
  // Next.js's appendHeader keeps a list, which Node sends as one header line.
  const sent = raw.getHeader('cache-control');
  return Array.isArray(sent) ? sent.join(', ') : typeof sent === 'string' ? sent : undefined;
}

const photo = (cache: string, status = 200) =>
  new Response(status === 200 ? new Uint8Array([0xff, 0xd8]) : null, {
    status,
    headers: { 'Cache-Control': cache, 'Content-Type': 'image/jpeg', 'X-Content-Type-Options': 'nosniff' },
  });

describe('a salon’s public photos through the proxy (BUG-F05-003)', () => {
  it.each([
    ['/service-photos', SERVICE_PHOTO_CACHE],
    ['/team-photos', TEAM_PHOTO_CACHE],
    ['/brand-images', BRAND_IMAGE_CACHE],
  ])('%s/<id> reaches the browser with the handler’s own Cache-Control', async (route, cache) => {
    const proxied = await routeRequest(request(`${route}/${ID}`), resolving({ kind: 'salon', salon: alpha }));
    expect(await served(proxied, photo(cache))).toBe(cache);
  });

  it.each(['/service-photos', '/team-photos', '/brand-images'])(
    '%s/<id> that the handler answers not found, or with a draft, stays no-store',
    async (route) => {
      const proxied = await routeRequest(request(`${route}/${ID}`), resolving({ kind: 'salon', salon: alpha }));
      expect(await served(proxied, photo(NOT_STORED, 404))).toBe(NOT_STORED);
      expect(await served(proxied, photo(NOT_STORED))).toBe(NOT_STORED);
    },
  );

  it.each([
    ['unknown', { kind: 'not-found' } as Site, 404],
    ['suspended', { kind: 'suspended' } as Site, 503],
  ])('on an %s salon’s hostname, the proxy answers the photo path itself, no-store', async (_case, site, status) => {
    const response = await routeRequest(request(`/service-photos/${ID}`), resolving(site));
    expect(response.status).toBe(status);
    expect(response.headers.get('cache-control')).toBe(NOT_STORED);
  });

  it('keeps no-store on every other answer of a salon’s hostname, whatever its handler says', async () => {
    for (const path of ['/workspace/sign-in', '/identity-web/staff/bookings', '/service-photos', '/brand-images-x/1']) {
      const proxied = await routeRequest(request(path), resolving({ kind: 'salon', salon: alpha }));
      expect(await served(proxied, photo(SERVICE_PHOTO_CACHE)), path).toBe(NOT_STORED);
    }
  });
});
