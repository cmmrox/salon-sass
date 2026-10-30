// @vitest-environment node
import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createServerApiClient } from '@/lib/api-core/client';
import { contextFromTraceparent, runInRequest } from '@/lib/observability/request-context';
import { operatorEntry } from './operator-entry';

let server: Server; let calls: string[];
let answer: (path: string) => { status: number; data: unknown } | null;
const operator = { id: 'op', kind: 'operator', role: 'OPERATOR_ADMIN', permissions: ['operator.salon.read'], mfaEnabled: true };
beforeEach(async () => {
  calls = [];
  answer = (path) => path === '/api/v1/meta' ? { status: 200, data: { time: '2026-01-01T00:10:00Z' } } : { status: 200, data: operator };
  server = createServer((request, response) => {
    calls.push(request.url!);
    const result = answer(request.url!);
    if (!result) return; // silent: accepts the request and never answers
    response.writeHead(result.status, { 'Content-Type': 'application/json' }); response.end(JSON.stringify(result.data));
  });
  await new Promise<void>((r) => server.listen(0, '127.0.0.1', r));
  vi.stubEnv('SALON_API_BASE_URL', `http://127.0.0.1:${(server.address() as AddressInfo).port}`);
});
afterEach(async () => { server.closeAllConnections(); await new Promise<void>((r) => server.close(() => r())); vi.unstubAllEnvs(); });
function decide(credentials: Parameters<typeof operatorEntry>[0], deadline = performance.now() + 3000) {
  const context = { ...contextFromTraceparent(undefined), clientAddress: '127.0.0.1', deadline };
  return runInRequest(context, () => operatorEntry(credentials, createServerApiClient({ hostname: 'console.localhost' })));
}

describe('server-side console entry', () => {
  it('confirms an operator, and leaves renewal of an expired access credential to the browser', async () => {
    expect(await decide({ access: 'private-access' })).toEqual({ kind: 'signed-in', principal: operator });
    expect(await decide({ refresh: 'family' })).toEqual({ kind: 'pending' });
  });

  // BUG-F03-002: an API that cannot say within the request's budget is reported as unavailable at
  // once, so the page shows its unavailable state instead of handing the browser a second wait.
  it('reports an API that answers 5xx as unavailable', async () => {
    answer = () => ({ status: 503, data: { code: 'service_unavailable' } });
    expect(await decide({ access: 'private-access' })).toEqual({ kind: 'unavailable' });
    expect(await decide({ refresh: 'family' })).toEqual({ kind: 'unavailable' });
  });

  it('reports a silent API as unavailable when the budget runs out, and a spent budget without calling', async () => {
    answer = () => null;
    const started = performance.now();
    expect(await decide({ access: 'private-access' }, performance.now() + 300)).toEqual({ kind: 'unavailable' });
    expect(performance.now() - started).toBeLessThan(1500);
    calls = [];
    expect(await decide({ access: 'private-access' }, performance.now() - 1)).toEqual({ kind: 'unavailable' });
    expect(calls).toEqual([]);
  });

  it('is pending, never signed-in, when no API is configured', async () => {
    expect(await operatorEntry({ access: 'private-access' }, null)).toEqual({ kind: 'pending' });
  });
});
