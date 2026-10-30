// @vitest-environment node
import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { contextFromTraceparent, runInRequest } from '@/lib/observability/request-context';
import { hasCredentials, workspaceEntry } from './entry';

let server: Server;
let calls: { path: string; host?: string; authorization?: string }[];
let answer: (path: string) => { status: number; data: unknown };
const staff = { id: 'staff', kind: 'staff', permissions: ['session.manage'] };
beforeEach(async () => {
  calls = [];
  vi.stubEnv('SALON_PLATFORM_DOMAIN', 'localhost');
  answer = (path) =>
    path === '/api/v1/meta' ? { status: 200, data: { time: '2026-01-01T00:10:00Z' } } : { status: 200, data: staff };
  server = createServer((request, response) => {
    const path = request.url!;
    calls.push({ path, host: request.headers.host, authorization: request.headers.authorization });
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
function decide(credentials: Parameters<typeof workspaceEntry>[1], deadline = performance.now() + 3000) {
  const context = { ...contextFromTraceparent(undefined), clientAddress: '127.0.0.1', deadline };
  return runInRequest(context, () => workspaceEntry('staff', credentials, 'alpha.localhost'));
}
const paths = () => calls.map((call) => call.path);

describe('server-side workspace entry (CR-013)', () => {
  it('sends a visitor without credentials to sign-in without calling the API', async () => {
    expect(hasCredentials({})).toBe(false);
    expect(await decide({})).toEqual({ kind: 'signed-out' });
    expect(calls).toEqual([]);
  });

  it('renders a confirmed staff principal, asking only this salon with the access credential', async () => {
    expect(await decide({ access: 'private-access', refresh: 'family' })).toEqual({
      kind: 'signed-in',
      principal: staff,
    });
    expect(paths()).toEqual(['/api/v1/identity/me']);
    expect(calls[0]).toMatchObject({ host: 'alpha.localhost', authorization: 'Bearer private-access' });
  });

  it('refuses a principal of another kind', async () => {
    answer = () => ({ status: 200, data: { ...staff, kind: 'customer' } });
    expect(await decide({ access: 'customer-access' })).toEqual({ kind: 'signed-out' });
  });

  it.each([401, 403])('sends a refused credential (%i) without a refresh family to sign-in', async (status) => {
    answer = () => ({ status, data: { code: 'invalid_session' } });
    expect(await decide({ access: 'revoked' })).toEqual({ kind: 'signed-out' });
    expect(paths()).toEqual(['/api/v1/identity/me']);
  });

  it('sends revoked access whose recorded expiry has not passed to sign-in, never renewing', async () => {
    answer = (path) =>
      path === '/api/v1/meta'
        ? { status: 200, data: { time: '2026-01-01T00:00:00Z' } }
        : { status: 401, data: { code: 'invalid_session' } };
    expect(await decide({ access: 'revoked', refresh: 'family', expiry: '2026-01-01T00:05:00Z' })).toEqual({
      kind: 'signed-out',
    });
    expect(paths()).not.toContain('/api/v1/identity/sessions/refresh');
  });

  it('leaves renewal of an expired access credential to the browser lock', async () => {
    expect(await decide({ refresh: 'family' })).toEqual({ kind: 'pending' });
    expect(paths()).toEqual(['/api/v1/meta']);
    answer = (path) =>
      path === '/api/v1/meta'
        ? { status: 200, data: { time: '2026-01-01T00:10:00Z' } }
        : { status: 401, data: { code: 'invalid_session' } };
    expect(await decide({ access: 'expired', refresh: 'family', expiry: '2026-01-01T00:05:00Z' })).toEqual({
      kind: 'pending',
    });
    expect(paths()).not.toContain('/api/v1/identity/sessions/refresh');
  });

  it('keeps a member in the workspace with safe retry when the API is out or slow', async () => {
    answer = () => ({ status: 503, data: { code: 'service_unavailable' } });
    expect(await decide({ access: 'private-access' })).toEqual({ kind: 'pending' });
    expect(await decide({ refresh: 'family' })).toEqual({ kind: 'pending' });
    calls = [];
    expect(await decide({ access: 'private-access' }, performance.now() - 1)).toEqual({ kind: 'pending' });
    expect(calls).toEqual([]);
  });

  it('is pending, never signed-in, when no API is configured', async () => {
    vi.stubEnv('SALON_API_BASE_URL', '');
    expect(await workspaceEntry('staff', { access: 'private-access' }, 'alpha.localhost')).toEqual({ kind: 'pending' });
  });
});
