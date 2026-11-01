// @vitest-environment node
import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { NextRequest } from 'next/server';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { contextFromTraceparent, runInRequest } from '@/lib/observability/request-context';
import { assertion, cookieName, createIdentityWeb, csrf, requestHost } from './identity-web';
import type { SalonAccount, SalonAccountFor } from './salon-account';
import type { SalonSettings, SalonSettingsFor } from './salon-settings';
import type { SalonHours, SalonHoursFor } from './salon-hours';
import { normalizeIp, strictInstant } from '@/lib/http/network';
import { formatInstant } from '@salon/format';

/** These cases never reach the platform module's paths: any call to them fails. */
const unreached: SalonAccountFor = () =>
  new Proxy({} as SalonAccount, {
    get: () => () => Promise.reject(new Error('the platform module is not reached here')),
  });
/** Nor the branding module's settings paths (F04-S01). */
const unreachedSettings: SalonSettingsFor = () =>
  new Proxy({} as SalonSettings, {
    get: () => () => Promise.reject(new Error('the branding module is not reached here')),
  });
/** Nor the staff module's hours paths (F04-S03). */
const unreachedHours: SalonHoursFor = () =>
  new Proxy({} as SalonHours, {
    get: () => () => Promise.reject(new Error('the staff module is not reached here')),
  });
const handleIdentity = createIdentityWeb(unreached, unreachedSettings, unreachedHours);
let server: Server;
let calls: { path: string; host?: string; proof?: string }[];
let answer: (path: string) => { status: number; data: unknown };
const token = {
  state: 'authenticated',
  sessionId: 'session',
  accessToken: 'private-access',
  refreshToken: 'private-refresh',
  accessExpiresAt: '2026-01-01T00:05:00Z',
  refreshExpiresAt: '2026-01-08T00:00:00Z',
};
beforeEach(async () => {
  calls = [];
  vi.stubEnv('SALON_PLATFORM_DOMAIN', 'localhost');
  vi.stubEnv('SALON_WEB_LOCAL_HTTP', 'true');
  vi.stubEnv('SALON_IDENTITY_PROXY_KEY', '12'.repeat(32));
  answer = (path) => ({
    status: 200,
    data:
      path === '/api/v1/meta'
        ? { time: '2026-01-01T00:00:00Z' }
        : path.endsWith('/sign-in')
          ? { state: 'authenticated', credentials: token }
          : { id: 'staff', kind: 'staff', permissions: ['session.manage'] },
  });
  server = createServer((request, response) => {
    const path = request.url!;
    calls.push({ path, host: request.headers.host, proof: request.headers['x-salon-client-assertion'] as string });
    const result = answer(path);
    response.writeHead(result.status, { 'Content-Type': 'application/json' });
    response.end(JSON.stringify(result.data));
  });
  await new Promise<void>((r) => server.listen(0, '127.0.0.1', r));
  vi.stubEnv('SALON_API_BASE_URL', `http://127.0.0.1:${(server.address() as AddressInfo).port}`);
});
afterEach(async () => {
  server.closeAllConnections();
  await new Promise<void>((r) => server.close(() => r()));
  vi.unstubAllEnvs();
});
function request(method = 'POST', cookies = '') {
  return new NextRequest('http://alpha.localhost:3100/identity-web/staff/sign-in', {
    method,
    headers: {
      Host: 'alpha.localhost:3100',
      Origin: 'http://alpha.localhost:3100',
      Cookie: cookies,
      'Content-Type': 'application/json',
    },
    ...(method === 'POST'
      ? {
          body: JSON.stringify({
            identifier: 'staff@alpha.test',
            password: 'private-password',
            deviceLabel: 'Browser',
          }),
        }
      : {}),
  });
}
function run(req: NextRequest, action: string) {
  const context = {
    ...contextFromTraceparent(undefined),
    clientAddress: '127.0.0.2',
    deadline: performance.now() + 3000,
  };
  return runInRequest(context, () => handleIdentity(req, 'staff', action));
}
describe('browser boundary', () => {
  it('rejects login CSRF before any API side effect', async () => {
    const req = request();
    req.headers.delete('origin');
    expect((await run(req, 'sign-in')).status).toBe(403);
    expect(calls).toEqual([]);
  });
  it('does not trust forwarded origin, host, cross-site metadata or a client proof', async () => {
    const req = request();
    req.headers.set('origin', 'https://beta.localhost:3100');
    req.headers.set('x-forwarded-host', 'beta.localhost');
    req.headers.set('x-forwarded-proto', 'https');
    expect(csrf(req)).toBe(false);
    req.headers.set('origin', 'http://alpha.localhost:3100');
    req.headers.set('sec-fetch-site', 'cross-site');
    expect(csrf(req)).toBe(false);
  });
  it('never rotates through GET', async () => {
    expect((await run(request('GET'), 'refresh')).status).toBe(405);
    expect(calls).toEqual([]);
  });
  it('returns no access, refresh or password material and issues separate private cookies', async () => {
    const r = await run(request(), 'sign-in');
    expect(await r.json()).toEqual({ state: 'authenticated' });
    expect(r.cookies.get(cookieName('staff', 'access'))?.value).toBe('private-access');
    expect(r.headers.get('set-cookie')).toContain('HttpOnly');
    expect(r.headers.get('set-cookie')).not.toContain('Domain=');
    expect(r.cookies.get('salon_customer_access')).toBeUndefined();
    expect(calls.every((call) => call.host === 'alpha.localhost')).toBe(true);
    expect(calls.at(-1)?.proof).toBe(
      assertion('alpha.localhost', '/api/v1/identity/staff/sign-in', '127.0.0.2', '2026-01-01T00:00:00Z'),
    );
  });
  it('future-expiry revoked access cannot trigger renewal', async () => {
    answer = (path) =>
      path === '/api/v1/meta'
        ? { status: 200, data: { time: '2026-01-01T00:00:00Z' } }
        : { status: 401, data: { code: 'invalid_session' } };
    const r = await run(
      request(
        'POST',
        'salon_staff_access=revoked; salon_staff_refresh=family; salon_staff_expires=2026-01-01T00:05:00Z',
      ),
      'refresh',
    );
    expect(r.status).toBe(401);
    expect(calls.map((call) => call.path)).not.toContain('/api/v1/identity/sessions/refresh');
    expect(r.cookies.get('salon_staff_refresh')?.value).toBe('');
  });
  it('uses the original deadline even if proxy work already exhausted it', async () => {
    const context = {
      ...contextFromTraceparent(undefined),
      clientAddress: '127.0.0.1',
      deadline: performance.now() - 1,
    };
    const r = await runInRequest(context, () => handleIdentity(request(), 'staff', 'sign-in'));
    expect(r.status).toBe(503);
    expect(calls).toEqual([]);
  });
});
describe('canonical inputs', () => {
  it.each(['127.0.0.1:30', '[::1]', '::1%en0', '1.2.3.4,5.6.7.8', 'garbage'])('rejects %s as address', (ip) =>
    expect(normalizeIp(ip)).toBeNull(),
  );
  it('normalizes mapped IPv6 and full IPv6 identically to Java', () => {
    expect(normalizeIp('::ffff:127.0.0.1')).toBe('127.0.0.1');
    expect(normalizeIp('2001:db8::1')).toBe('2001:0db8:0000:0000:0000:0000:0000:0001');
  });
  it('uses explicit transport rather than incoming protocol', () => {
    expect(requestHost('alpha.localhost:3100')?.origin).toBe('http://alpha.localhost:3100');
    vi.stubEnv('SALON_WEB_LOCAL_HTTP', 'false');
    expect(requestHost('alpha.localhost:3100')?.origin).toBe('https://alpha.localhost:3100');
  });
  it.each(['2026-01-01', '2026-01-01T00:00:00+00:00', 'not-time'])('rejects noninstant %s', (time) =>
    expect(strictInstant(time)).toBe(false),
  );
  it('formats timestamps in the salon timezone', () => {
    expect(formatInstant('2026-01-01T00:00:00Z', 'Asia/Colombo')).not.toEqual(
      formatInstant('2026-01-01T00:00:00Z', 'Europe/London'),
    );
  });
});
