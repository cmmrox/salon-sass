// @vitest-environment node
import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { NextRequest } from 'next/server';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { contextFromTraceparent, runInRequest } from '@/lib/observability/request-context';
import { createIdentityWeb, supportCsrf } from '@/features/identity/server';
import { salonSettingsFor } from '@/features/branding/server';
import { salonAccountFor } from '@/features/platform/server';
import { salonHoursFor } from '@/features/staff/server';
import { salonBookingRulesFor } from '@/features/booking/server';

/** The support session's BFF (F03-S04), as the route wires it, against a fake API on loopback. */
const handleIdentity = createIdentityWeb(salonAccountFor, salonSettingsFor, salonHoursFor, salonBookingRulesFor);
let server: Server;
let calls: { method: string; path: string; host?: string; authorization?: string; body: string }[];
let answer: (method: string, path: string) => { status: number; data: unknown };
const CODE = 'A'.repeat(43);
const credential = {
  accessToken: 'private-support-access',
  sessionId: '11111111-0000-4000-8000-000000000001',
  issuedAt: '2026-01-01T00:00:00Z',
  expiresAt: '2026-01-01T00:30:00Z',
};
const principal = {
  id: credential.sessionId,
  kind: 'support',
  role: null,
  permissions: ['staff.list', 'audit.list', 'entitlement.read', 'invoice.read'],
  scope: 'salon',
  staffId: null,
  mfaEnabled: false,
  expiresAt: credential.expiresAt,
};
beforeEach(async () => {
  calls = [];
  vi.stubEnv('SALON_PLATFORM_DOMAIN', 'localhost');
  vi.stubEnv('SALON_WEB_LOCAL_HTTP', 'true');
  answer = (method, path) =>
    path === '/api/v1/support/entry'
      ? { status: 200, data: credential }
      : path === '/api/v1/identity/me'
        ? { status: 200, data: principal }
        : method === 'DELETE'
          ? { status: 204, data: null }
          : { status: 200, data: { items: [], page: 0, size: 10, total: 0 } };
  server = createServer((request, response) => {
    let body = '';
    request.on('data', (chunk) => {
      body += chunk;
    });
    request.on('end', () => {
      calls.push({
        method: request.method!,
        path: request.url!.split('?')[0]!,
        host: request.headers.host,
        authorization: request.headers.authorization,
        body,
      });
      const result = answer(request.method!, request.url!.split('?')[0]!);
      response.writeHead(result.status, { 'Content-Type': 'application/json' });
      response.end(result.data === null ? '' : JSON.stringify(result.data));
    });
  });
  await new Promise<void>((r) => server.listen(0, '127.0.0.1', r));
  vi.stubEnv('SALON_API_BASE_URL', `http://127.0.0.1:${(server.address() as AddressInfo).port}`);
});
afterEach(async () => {
  server.closeAllConnections();
  await new Promise<void>((r) => server.close(() => r()));
  vi.unstubAllEnvs();
});
function request(
  method: string,
  action: string,
  { cookies = '', body, headers = {} }: { cookies?: string; body?: unknown; headers?: Record<string, string> } = {},
) {
  return new NextRequest(`http://alpha.localhost:3100/identity-web/support/${action}`, {
    method,
    headers: {
      Host: 'alpha.localhost:3100',
      Origin: 'http://alpha.localhost:3100',
      'Sec-Fetch-Site': 'same-origin',
      Cookie: cookies,
      'Content-Type': 'application/json',
      ...headers,
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
}
function run(req: NextRequest, action: string) {
  const context = {
    ...contextFromTraceparent(undefined),
    clientAddress: '127.0.0.2',
    deadline: performance.now() + 3000,
  };
  return runInRequest(context, () => handleIdentity(req, 'support', action));
}
const signedIn = 'salon_support_access=private-support-access; salon_support_session=s';

describe('support entry', () => {
  it('exchanges the code once and keeps the credential only in host-only HttpOnly cookies until the session ends', async () => {
    const response = await run(request('POST', 'entry', { body: { code: CODE } }), 'entry');
    expect(response.status).toBe(200);
    const body = await response.json();
    expect(body).toEqual({ ok: true, expiresAt: credential.expiresAt });
    expect(JSON.stringify(body)).not.toContain('private-support-access');
    const cookies = response.headers.getSetCookie();
    const access = cookies.find((cookie) => cookie.startsWith('salon_support_access=private-support-access'));
    expect(access).toMatch(/HttpOnly/i);
    expect(access).toMatch(/SameSite=lax/i);
    expect(access).toMatch(/Max-Age=1800/);
    expect(access).not.toMatch(/Domain=/i);
    expect(cookies.some((cookie) => cookie.startsWith(`salon_support_session=${credential.sessionId}`))).toBe(true);
    expect(calls).toEqual([
      expect.objectContaining({
        method: 'POST',
        path: '/api/v1/support/entry',
        host: 'alpha.localhost',
        body: JSON.stringify({ code: CODE }),
      }),
    ]);
  });

  it('issues nothing for a refused code', async () => {
    answer = () => ({ status: 401, data: { code: 'invalid_code' } });
    const response = await run(request('POST', 'entry', { body: { code: CODE } }), 'entry');
    expect(response.status).toBe(401);
    expect(await response.json()).toMatchObject({ code: 'invalid_code' });
    expect(response.headers.getSetCookie()).toEqual([]);
  });

  it.each([
    ['no Origin', { Origin: '' }],
    ['a foreign Origin', { Origin: 'http://beta.localhost:3100' }],
    ['cross-site Fetch Metadata', { 'Sec-Fetch-Site': 'cross-site' }],
    ['same-site Fetch Metadata', { 'Sec-Fetch-Site': 'same-site' }],
  ] as [string, Record<string, string>][])('refuses %s before any API call', async (_name, headers) => {
    const req = request('POST', 'entry', { body: { code: CODE }, headers });
    if (headers.Origin === '') req.headers.delete('origin');
    expect(supportCsrf(req)).toBe(false);
    const response = await run(req, 'entry');
    expect(response.status).toBe(403);
    expect(calls).toEqual([]);
  });

  it('refuses a body over its limit and a request on no salon host', async () => {
    expect((await run(request('POST', 'entry', { body: { code: 'x'.repeat(2000) } }), 'entry')).status).toBe(400);
    const elsewhere = new NextRequest('http://localhost:3100/identity-web/support/entry', {
      method: 'POST',
      headers: { Host: 'localhost:3100', Origin: 'http://localhost:3100' },
      body: JSON.stringify({ code: CODE }),
    });
    expect((await run(elsewhere, 'entry')).status).toBe(404);
    expect(calls).toEqual([]);
  });
});

describe('the support view', () => {
  it.each([
    'staff-create',
    'staff-change',
    'staff-remove',
    'password',
    'logout',
    'revoke-all',
    'mfa-start',
    'support-revoke',
    'sign-in',
  ])('refuses %s as read only, with no API call', async (action) => {
    const response = await run(request('POST', action, { cookies: signedIn, body: {} }), action);
    expect(response.status).toBe(403);
    expect(await response.json()).toMatchObject({ code: 'support_read_only' });
    expect(calls).toEqual([]);
  });

  it('reads with the support credential and returns the principal without any credential', async () => {
    const response = await run(request('GET', 'principal', { cookies: signedIn }), 'principal');
    expect(await response.json()).toEqual(principal);
    expect(calls[0]).toMatchObject({ path: '/api/v1/identity/me', authorization: 'Bearer private-support-access' });
    const staff = await run(request('GET', 'staff', { cookies: signedIn }), 'staff');
    expect(staff.status).toBe(200);
    expect(calls.at(-1)).toMatchObject({ path: '/api/v1/identity/staff' });
  });

  it('does not read devices or support access', async () => {
    expect((await run(request('GET', 'sessions', { cookies: signedIn }), 'sessions')).status).toBe(403);
    expect((await run(request('GET', 'support-sessions', { cookies: signedIn }), 'support-sessions')).status).toBe(403);
    expect(calls).toEqual([]);
  });

  it('leaves the support view when the API ends the session', async () => {
    answer = () => ({ status: 401, data: { code: 'invalid_session' } });
    const response = await run(request('GET', 'audit', { cookies: signedIn }), 'audit');
    expect(response.status).toBe(401);
    expect(await response.json()).toMatchObject({ code: 'invalid_session', renewable: false });
    expect(
      response.headers
        .getSetCookie()
        .filter((cookie) => /^salon_support_(access|session)=;/.test(cookie) && /Max-Age=0/i.test(cookie)),
    ).toHaveLength(2);
    expect(
      response.headers
        .getSetCookie()
        .filter((cookie) => /^salon_session_ended=support;/.test(cookie) && /HttpOnly/i.test(cookie)),
    ).toHaveLength(1);
  });

  it('forgets the ended marker when the entry page has shown it (clear)', async () => {
    const response = await run(request('POST', 'clear', { cookies: 'salon_session_ended=support', body: {} }), 'clear');
    expect(response.status).toBe(200);
    expect(
      response.headers
        .getSetCookie()
        .filter((cookie) => /^salon_session_ended=;/.test(cookie) && /Max-Age=0/i.test(cookie)),
    ).toHaveLength(1);
  });

  it('ends the session and clears its cookies, also when it had already ended', async () => {
    const ended = await run(request('POST', 'end', { cookies: signedIn, body: {} }), 'end');
    expect(ended.status).toBe(200);
    expect(calls).toEqual([
      expect.objectContaining({
        method: 'DELETE',
        path: '/api/v1/support/session',
        authorization: 'Bearer private-support-access',
      }),
    ]);
    expect(
      ended.headers.getSetCookie().filter((cookie) => /^salon_support_/.test(cookie) && /Max-Age=0/i.test(cookie)),
    ).toHaveLength(2);
    answer = () => ({ status: 401, data: { code: 'invalid_session' } });
    const again = await run(request('POST', 'end', { cookies: signedIn, body: {} }), 'end');
    expect(again.status).toBe(200);
    expect(again.headers.getSetCookie().some((cookie) => cookie.startsWith('salon_support_access=;'))).toBe(true);
  });
});
