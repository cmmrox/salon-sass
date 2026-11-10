// @vitest-environment node
import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { NextRequest } from 'next/server';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { RouteTable } from '@/lib/api-core/routes';
import { contextFromTraceparent, runInRequest } from '@/lib/observability/request-context';
import { createIdentityWeb } from './identity-web';
import type { SalonAccountFor } from './salon-account';

/**
 * BUG-F03-013: the workspace BFF ends a session only on the API's own evidence (the lead's rule): the API refusing the
 * refresh credential, or saying the session is gone. "Could not find out" is answered as what it is: 503
 * `salon_suspended` for a suspended salon, 503 `service_unavailable` otherwise, with the session's cookies left as they
 * are, so that the same session goes on after an outage or a reactivation (F03-S03 AC-1, decision 027).
 *
 * The access credential is often absent here: its cookie lives five minutes (`issue`), and the browser renews only
 * when a call is refused, so the first poll after that has none.
 */
type Api = 'active' | 'suspended' | 'unreachable' | 'revoked';
let api: Api;
let server: Server;
let calls: string[];
const SUSPENDED = { code: 'salon_suspended', status: 403, title: 'Forbidden' };
const tokens = {
  sessionId: 'session',
  accessToken: 'fresh-access',
  refreshToken: 'fresh-refresh',
  accessExpiresAt: '2026-01-01T00:05:00Z',
  refreshExpiresAt: '2026-01-08T00:00:00Z',
};

beforeEach(async () => {
  api = 'active';
  calls = [];
  vi.stubEnv('SALON_PLATFORM_DOMAIN', 'localhost');
  vi.stubEnv('SALON_WEB_LOCAL_HTTP', 'true');
  server = createServer((request, response) => {
    const path = request.url!.split('?')[0]!;
    calls.push(`${request.method} ${path}`);
    const reply = (status: number, data: unknown) => {
      response.writeHead(status, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify(data));
    };
    // The API's own endpoint answers on a suspended salon's hostname too (MetaController reads no salon).
    if (path === '/api/v1/meta') return reply(200, { time: '2026-01-01T00:10:00Z' });
    if (api === 'suspended') return reply(403, SUSPENDED);
    const bearer = request.headers.authorization;
    if (path === '/api/v1/identity/sessions/refresh')
      return api === 'revoked'
        ? reply(401, { code: 'invalid_session', status: 401, title: 'Unauthorized' })
        : reply(200, tokens);
    if (!bearer) return reply(401, { code: 'invalid_session', status: 401, title: 'Unauthorized' });
    if (path === '/api/v1/identity/me')
      return reply(200, { id: 'staff', kind: 'staff', permissions: ['calendar.read'] });
    return reply(200, { items: [] });
  });
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  vi.stubEnv('SALON_API_BASE_URL', `http://127.0.0.1:${(server.address() as AddressInfo).port}`);
});
afterEach(async () => {
  server.closeAllConnections();
  await new Promise<void>((resolve) => server.close(() => resolve()));
  vi.unstubAllEnvs();
});

/** A module's read as a module declares it: the day's bookings, through the API on the salon's hostname. */
const routes: RouteTable = {
  pages: [],
  reads: {
    bookings: {
      permission: 'calendar.read',
      read: async (hostname, call) => {
        const base = process.env.SALON_API_BASE_URL!;
        const { fetchForHostname } = await import('@/lib/api-core/forward-hostname');
        const response = await fetchForHostname(hostname)(`${base}/api/v1/bookings`, {
          headers: Object.fromEntries(
            Object.entries(call.headers).filter((entry): entry is [string, string] => !!entry[1]),
          ),
          signal: call.signal,
        });
        const body = await response.json();
        return response.ok ? { data: body, response } : { error: body, response };
      },
    },
  },
  changes: {},
  uploads: {},
};
const unreached: SalonAccountFor = () => null;
const handle = createIdentityWeb(unreached, routes);

/** The browser's cookies once the access credential has lapsed: the refresh credential and the session remain. */
const LAPSED = 'salon_staff_refresh=private-refresh; salon_staff_expires=2026-01-01T00:05:00Z; salon_staff_session=s';

function ask(action: string, method = 'GET', cookie = LAPSED) {
  const request = new NextRequest(`http://alpha.localhost:3100/identity-web/staff/${action}`, {
    method,
    headers: {
      Host: 'alpha.localhost:3100',
      Origin: 'http://alpha.localhost:3100',
      'Sec-Fetch-Site': 'same-origin',
      Cookie: cookie,
      ...(method === 'POST' ? { 'Content-Type': 'application/json' } : {}),
    },
    ...(method === 'POST' ? { body: '{}' } : {}),
  });
  const context = {
    ...contextFromTraceparent(undefined),
    clientAddress: '127.0.0.2',
    deadline: performance.now() + 3000,
  };
  return runInRequest(context, () => handle(request, 'staff', action));
}
/** The cookies a response clears (Max-Age 0). */
const cleared = (response: Response) =>
  response.headers
    .getSetCookie()
    .filter((cookie) => /Max-Age=0/i.test(cookie))
    .map((cookie) => cookie.split('=')[0]);

describe('a read with the access credential lapsed', () => {
  it('on a suspended salon, is answered 503 salon_suspended, never 401, and the session is left as it is', async () => {
    api = 'suspended';
    const response = await ask('bookings');
    expect(response.status).toBe(503);
    expect(await response.json()).toEqual({ code: 'salon_suspended' });
    expect(cleared(response)).toEqual([]);
  });

  it('with the API unreachable, is answered 503 service_unavailable, never as an ended session', async () => {
    vi.stubEnv('SALON_API_BASE_URL', 'http://127.0.0.1:9');
    const response = await ask('bookings');
    expect(response.status).toBe(503);
    expect(await response.json()).toEqual({ code: 'service_unavailable' });
    expect(cleared(response)).toEqual([]);
  });

  it('on an active salon, is answered 401 renewable, for the browser to renew', async () => {
    const response = await ask('bookings');
    expect(response.status).toBe(401);
    expect(await response.json()).toEqual({ code: 'invalid_session', renewable: true });
  });
});

describe('the renewal itself', () => {
  it('on a suspended salon, is answered 503 salon_suspended and clears nothing', async () => {
    api = 'suspended';
    const response = await ask('refresh', 'POST');
    expect(response.status).toBe(503);
    expect(await response.json()).toEqual({ code: 'salon_suspended' });
    expect(cleared(response)).toEqual([]);
  });

  it('with the API unreachable, is answered 503 service_unavailable and clears nothing: nothing was rotated', async () => {
    vi.stubEnv('SALON_API_BASE_URL', 'http://127.0.0.1:9');
    const response = await ask('refresh', 'POST');
    expect(response.status).toBe(503);
    expect(await response.json()).toEqual({ code: 'service_unavailable' });
    expect(cleared(response)).toEqual([]);
  });

  it('with the refresh credential revoked, ends the session: 401 invalid_session and the cookies cleared', async () => {
    api = 'revoked';
    const response = await ask('refresh', 'POST');
    expect(response.status).toBe(401);
    expect(await response.json()).toMatchObject({ code: 'invalid_session' });
    expect(cleared(response)).toEqual(expect.arrayContaining(['salon_staff_access', 'salon_staff_refresh']));
  });

  it('on an active salon, renews and issues the new credentials', async () => {
    const response = await ask('refresh', 'POST');
    expect(response.status).toBe(200);
    expect(response.headers.getSetCookie().some((cookie) => cookie.startsWith('salon_staff_access=fresh-access'))).toBe(
      true,
    );
  });
});
