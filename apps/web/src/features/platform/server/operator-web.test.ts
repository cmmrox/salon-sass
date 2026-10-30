// @vitest-environment node
import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { NextRequest } from 'next/server';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { contextFromTraceparent, runInRequest } from '@/lib/observability/request-context';
import { handleOperator, operatorAssertion, operatorCookie, operatorCsrf } from './operator-web';

type Call = { method: string; path: string; host?: string; proof?: string; authorization?: string; idempotency?: string; body: string };
let server: Server; let calls: Call[];
let answer: (call: Call) => { status: number; data: unknown; headers?: Record<string, string> };
const tokens = { state: 'authenticated', sessionId: 'session-1', accessToken: 'private-operator-access', refreshToken: 'private-operator-refresh', accessExpiresAt: '2026-01-01T00:05:00Z', refreshExpiresAt: '2026-01-08T00:00:00Z' };
const principal = { id: 'op', kind: 'operator', role: 'OPERATOR_ADMIN', permissions: ['operator.plan.read'], mfaEnabled: true };
const UUID = '3f1c2b9a-0000-4000-8000-000000000001';

beforeEach(async () => {
  calls = [];
  vi.stubEnv('SALON_PLATFORM_DOMAIN', 'localhost'); vi.stubEnv('SALON_WEB_LOCAL_HTTP', 'true'); vi.stubEnv('SALON_OPERATOR_PROXY_KEY', '34'.repeat(32));
  answer = (call) => {
    if (call.path === '/api/v1/meta') return { status: 200, data: { time: '2026-01-01T00:00:00Z' } };
    if (call.path === '/api/v1/operator/sign-in') return { status: 200, data: { state: 'enroll_mfa', challengeId: UUID, challengeToken: 'private-challenge', expiresAt: '2026-01-01T00:05:00Z', credentials: null } };
    if (call.path === '/api/v1/operator/mfa/verify') return { status: 200, data: tokens };
    if (call.path === '/api/v1/operator/mfa/challenges') return { status: 200, data: { challengeId: UUID, secret: 'SETUPSECRET', otpauthUri: 'otpauth://totp/x', expiresAt: '2026-01-01T00:05:00Z' } };
    if (call.path === '/api/v1/operator/me') return { status: 200, data: principal };
    if (call.path.startsWith('/api/v1/operator/plans') && call.method === 'POST') return { status: 201, data: { id: UUID, key: 'k' } };
    if (call.path.startsWith('/api/v1/operator/salons') && call.method === 'POST') return { status: 201, data: { id: UUID, slug: 's', hostname: 's.localhost', planId: UUID, createdAt: '2026-01-01T00:00:00Z' } };
    return { status: 200, data: { items: [], page: 0, size: 20, total: 0 } };
  };
  server = createServer((request, response) => {
    let body = '';
    request.on('data', (chunk) => { body += chunk; });
    request.on('end', () => {
      const call: Call = { method: request.method!, path: request.url!, host: request.headers.host, proof: request.headers['x-salon-client-assertion'] as string, authorization: request.headers.authorization, idempotency: request.headers['idempotency-key'] as string, body };
      calls.push(call);
      const result = answer(call);
      response.writeHead(result.status, { 'Content-Type': 'application/json', ...(result.headers ?? {}) });
      response.end(JSON.stringify(result.data));
    });
  });
  await new Promise<void>((r) => server.listen(0, '127.0.0.1', r));
  vi.stubEnv('SALON_API_BASE_URL', `http://127.0.0.1:${(server.address() as AddressInfo).port}`);
});
afterEach(async () => { server.closeAllConnections(); await new Promise<void>((r) => server.close(() => r())); vi.unstubAllEnvs(); });

function request(action: string, { method = 'POST', host = 'console.localhost:3100', origin = 'http://console.localhost:3100', cookies = '', body = {} as unknown, query = '' } = {}) {
  const headers: Record<string, string> = { Host: host, Cookie: cookies, 'Content-Type': 'application/json' };
  if (origin) headers.Origin = origin;
  return new NextRequest(`http://${host}/operator-web/${action}${query}`, { method, headers, ...(method === 'POST' ? { body: JSON.stringify(body) } : {}) });
}
function run(req: NextRequest, action: string) {
  const context = { ...contextFromTraceparent(undefined), clientAddress: '127.0.0.9', deadline: performance.now() + 3000 };
  return runInRequest(context, () => handleOperator(req, action));
}

describe('the console BFF', () => {
  it.each(['alpha.localhost:3100', 'localhost:3100', 'admin.localhost:3100', 'console.localhost.example.test'])('answers not found on %s before any upstream call', async (host) => {
    for (const action of ['principal', 'salons', 'sign-in', 'plan-create']) {
      const r = await run(request(action, { host, origin: `http://${host}`, method: ['principal', 'salons'].includes(action) ? 'GET' : 'POST' }), action);
      expect(r.status).toBe(404);
    }
    expect(calls).toEqual([]);
  });

  it.each(['sign-in', 'mfa-enroll', 'mfa-verify', 'refresh', 'sign-out', 'plan-create', 'salon-create', 'clear'])('refuses %s without the exact origin or with cross-site metadata', async (action) => {
    expect((await run(request(action, { origin: '' }), action)).status).toBe(403);
    expect((await run(request(action, { origin: 'http://alpha.localhost:3100' }), action)).status).toBe(403);
    expect((await run(request(action, { origin: 'https://console.localhost:3100' }), action)).status).toBe(403);
    const crossSite = request(action); crossSite.headers.set('sec-fetch-site', 'cross-site');
    expect((await run(crossSite, action)).status).toBe(403);
    const sameSite = request(action); sameSite.headers.set('sec-fetch-site', 'same-site');
    expect(operatorCsrf(sameSite)).toBe(false);
    expect(calls).toEqual([]);
  });

  it('allows only its listed actions and never mutates through GET', async () => {
    expect((await run(request('anything'), 'anything')).status).toBe(404);
    expect((await run(request('refresh', { method: 'GET' }), 'refresh')).status).toBe(405);
    expect((await run(request('sign-in', { method: 'GET' }), 'sign-in')).status).toBe(405);
    expect(calls).toEqual([]);
  });

  it('keeps the challenge and tokens in host-only HttpOnly cookies and returns none of them', async () => {
    const signIn = await run(request('sign-in', { body: { email: 'op@example.test', password: 'private-password', deviceLabel: 'Console' } }), 'sign-in');
    expect(await signIn.json()).toEqual({ state: 'enroll_mfa', expiresAt: '2026-01-01T00:05:00Z' });
    expect(signIn.cookies.get(operatorCookie('challenge'))?.value).toBe('private-challenge');
    expect(signIn.headers.get('set-cookie')).toContain('HttpOnly');
    expect(signIn.headers.get('set-cookie')).not.toContain('Domain=');
    const sent = calls.find((call) => call.path === '/api/v1/operator/sign-in')!;
    expect(sent.host).toBe('console.localhost');
    expect(sent.proof).toBe(operatorAssertion('console.localhost', '127.0.0.9', '2026-01-01T00:00:00Z'));
    const cookies = `${operatorCookie('challenge')}=private-challenge; ${operatorCookie('challenge_id')}=${UUID}`;
    const setup = await run(request('mfa-enroll', { cookies }), 'mfa-enroll');
    expect(await setup.json()).toEqual({ secret: 'SETUPSECRET', otpauthUri: 'otpauth://totp/x', expiresAt: '2026-01-01T00:05:00Z' });
    const verified = await run(request('mfa-verify', { cookies, body: { code: '123456' } }), 'mfa-verify');
    const body = JSON.stringify(await verified.json());
    expect(body).toBe('{"ok":true}');
    expect(verified.cookies.get(operatorCookie('access'))?.value).toBe('private-operator-access');
    expect(verified.cookies.get(operatorCookie('refresh'))?.value).toBe('private-operator-refresh');
    expect(verified.cookies.get(operatorCookie('challenge'))?.value).toBe('');
    expect(verified.cookies.get('salon_staff_access')).toBeUndefined();
  });

  it('forwards one Idempotency-Key and refuses a malformed one', async () => {
    const cookies = `${operatorCookie('access')}=private-operator-access`;
    expect((await run(request('plan-create', { cookies, body: { idempotencyKey: 'nope' } }), 'plan-create')).status).toBe(400);
    const created = await run(request('plan-create', { cookies, body: { idempotencyKey: UUID, key: 'k', name: 'n', staffSeats: 1, branches: 1, messageAllowance: 0, customDomain: false, dedicatedApp: false } }), 'plan-create');
    expect(created.status).toBe(201);
    const sent = calls.find((call) => call.method === 'POST' && call.path === '/api/v1/operator/plans')!;
    expect(sent.idempotency).toBe(UUID);
    expect(sent.authorization).toBe('Bearer private-operator-access');
    const salon = await run(request('salon-create', { cookies, body: { idempotencyKey: UUID, name: 'n', owner: { name: 'o', email: 'o@example.test', password: 'private-owner-password' }, planId: UUID } }), 'salon-create');
    expect(await salon.json()).toEqual({ id: UUID, slug: 's', hostname: 's.localhost', planId: UUID, createdAt: '2026-01-01T00:00:00Z' });
  });

  it('a revoked access with a future expiry is never renewed, and its cookies are cleared', async () => {
    answer = (call) => call.path === '/api/v1/meta' ? { status: 200, data: { time: '2026-01-01T00:00:00Z' } } : { status: 401, data: { code: 'invalid_session' } };
    const cookies = `${operatorCookie('access')}=revoked; ${operatorCookie('refresh')}=family; ${operatorCookie('expires')}=2026-01-01T00:05:00Z`;
    const r = await run(request('refresh', { cookies }), 'refresh');
    expect(r.status).toBe(401);
    expect(calls.map((call) => call.path)).not.toContain('/api/v1/operator/sessions/refresh');
    expect(r.cookies.get(operatorCookie('refresh'))?.value).toBe('');
  });

  it('an expired access renews once, verifying the new credential before issuing it', async () => {
    answer = (call) => {
      if (call.path === '/api/v1/meta') return { status: 200, data: { time: '2026-01-01T00:10:00Z' } };
      if (call.path === '/api/v1/operator/sessions/refresh') return { status: 200, data: tokens };
      if (call.path === '/api/v1/operator/me') return call.authorization === `Bearer ${tokens.accessToken}` ? { status: 200, data: principal } : { status: 401, data: { code: 'invalid_session' } };
      return { status: 500, data: {} };
    };
    const cookies = `${operatorCookie('access')}=old; ${operatorCookie('refresh')}=family; ${operatorCookie('expires')}=2026-01-01T00:05:00Z`;
    const r = await run(request('refresh', { cookies }), 'refresh');
    expect(await r.json()).toEqual({ ok: true });
    expect(calls.filter((call) => call.path === '/api/v1/operator/sessions/refresh')).toHaveLength(1);
    expect(r.cookies.get(operatorCookie('access'))?.value).toBe(tokens.accessToken);
  });

  it('passes a refusal on with its stable code, field names and wait, never its detail', async () => {
    answer = (call) => call.path === '/api/v1/meta' ? { status: 200, data: { time: '2026-01-01T00:00:00Z' } } : { status: 429, data: { code: 'rate_limited', detail: 'internal words' }, headers: { 'Retry-After': '120' } };
    const r = await run(request('sign-in', { body: { email: 'x', password: 'y' } }), 'sign-in');
    expect(r.status).toBe(429);
    expect(await r.json()).toEqual({ code: 'rate_limited', errors: [], retryAfter: 120 });
  });

  it('answers unavailable when the shared deadline is already spent', async () => {
    const context = { ...contextFromTraceparent(undefined), clientAddress: '127.0.0.9', deadline: performance.now() - 1 };
    const r = await runInRequest(context, () => handleOperator(request('sign-in', { body: {} }), 'sign-in'));
    expect(r.status).toBe(503);
  });
});

describe('the console BFF lifecycle actions (F03-S03)', () => {
  const signedIn = 'salon_operator_access=private-operator-access';
  it('forwards suspend, reactivate and close with only their fields', async () => {
    for (const [action, method, path] of [['salon-suspend', 'POST', 'suspension'], ['salon-reactivate', 'DELETE', 'suspension'], ['salon-close', 'POST', 'closure']] as const) {
      calls = [];
      const r = await run(request(action, { cookies: signedIn, body: { id: UUID, version: 3, reason: 'A reason long enough', confirmSlug: 'alpha', extra: 'dropped' } }), action);
      expect(r.status).toBe(200);
      const upstream = calls.find((call) => call.path === `/api/v1/operator/salons/${UUID}/${path}`)!;
      expect(upstream.method).toBe(method);
      expect(JSON.parse(upstream.body)).toEqual(action === 'salon-close' ? { version: 3, confirmSlug: 'alpha', reason: 'A reason long enough' } : { version: 3, reason: 'A reason long enough' });
    }
  });

  it('reads the history and export, and refuses a malformed salon id before any upstream call', async () => {
    for (const action of ['lifecycle', 'export', 'export-archive']) {
      calls = [];
      expect((await run(request(action, { method: 'GET', cookies: signedIn, query: '?id=nope' }), action)).status).toBe(400);
      expect(calls.filter((call) => call.path.includes('/salons/'))).toEqual([]);
    }
    const r = await run(request('lifecycle', { method: 'GET', cookies: signedIn, query: `?id=${UUID}&page=1&size=10` }), 'lifecycle');
    expect(r.status).toBe(200);
    expect(calls.at(-1)!.path).toBe(`/api/v1/operator/salons/${UUID}/lifecycle?page=1&size=10`);
  });

  it('passes a refused archive through as its code', async () => {
    answer = (call) => call.path === '/api/v1/operator/me' ? { status: 200, data: principal } : { status: 409, data: { code: 'invalid_state' } };
    const r = await run(request('export-archive', { method: 'GET', cookies: signedIn, query: `?id=${UUID}` }), 'export-archive');
    expect(r.status).toBe(409);
    expect((await r.json()).code).toBe('invalid_state');
  });
});
