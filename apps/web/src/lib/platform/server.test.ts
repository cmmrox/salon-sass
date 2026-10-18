// @vitest-environment node
import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { NextRequest } from 'next/server';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { contextFromTraceparent, runInRequest } from '@/lib/observability/request-context';
import { handlePlatform } from './server';

type Call = { method: string; path: string; host?: string };
let server: Server; let calls: Call[];
const TOKEN = 'AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-abcde';
const ZIP = Buffer.from([0x50, 0x4b, 0x03, 0x04, 0, 1, 2, 3, 255]);

beforeEach(async () => {
  calls = [];
  vi.stubEnv('SALON_PLATFORM_DOMAIN', 'localhost'); vi.stubEnv('SALON_WEB_LOCAL_HTTP', 'true');
  server = createServer((request, response) => {
    calls.push({ method: request.method!, path: request.url!, host: request.headers.host });
    if (!request.url!.startsWith(`/api/v1/exports/${TOKEN}`)) {
      response.writeHead(404, { 'Content-Type': 'application/problem+json' });
      response.end(JSON.stringify({ code: 'not_found', status: 404 }));
    } else if (request.url!.endsWith('/metadata')) {
      response.writeHead(200, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify({ readyAt: '2026-01-01T00:00:00Z', deleteAt: '2026-01-31T00:00:00Z', sizeBytes: 9, sha256: 'ab'.repeat(32), extra: 'never forwarded' }));
    } else {
      response.writeHead(200, { 'Content-Type': 'application/zip', 'Content-Disposition': 'attachment; filename="salon-export-alpha.zip"', 'X-Content-SHA256': 'ab'.repeat(32) });
      response.end(ZIP);
    }
  });
  await new Promise<void>((r) => server.listen(0, '127.0.0.1', r));
  vi.stubEnv('SALON_API_BASE_URL', `http://127.0.0.1:${(server.address() as AddressInfo).port}`);
});
afterEach(async () => { server.closeAllConnections(); await new Promise<void>((r) => server.close(() => r())); vi.unstubAllEnvs(); });

function request(action: string, { method = 'POST', host = 'localhost:3100', origin = 'http://localhost:3100', token = TOKEN as unknown, site = '' } = {}) {
  const headers: Record<string, string> = { Host: host, 'Content-Type': 'application/json' };
  if (origin) headers.Origin = origin;
  if (site) headers['Sec-Fetch-Site'] = site;
  return new NextRequest(`http://${host}/platform-web/${action}`, { method, headers, ...(method === 'POST' ? { body: JSON.stringify({ token }) } : {}) });
}
function run(req: NextRequest, action: string) {
  const context = { ...contextFromTraceparent(undefined), clientAddress: '127.0.0.9', deadline: performance.now() + 3000 };
  return runInRequest(context, () => handlePlatform(req, action));
}

describe('the platform host BFF (F03-S03)', () => {
  it('answers the archive\'s nonsecret facts for a token, sent in the body and never in its own URL', async () => {
    const r = await run(request('export-info'), 'export-info');
    expect(r.status).toBe(200);
    expect(await r.json()).toEqual({ readyAt: '2026-01-01T00:00:00Z', deleteAt: '2026-01-31T00:00:00Z', sizeBytes: 9, sha256: 'ab'.repeat(32) });
    expect(r.headers.get('cache-control')).toContain('no-store');
    expect(calls).toEqual([{ method: 'GET', path: `/api/v1/exports/${TOKEN}/metadata`, host: 'localhost' }]);
  });

  it('passes the archive through byte for byte as a no-store attachment', async () => {
    const r = await run(request('export-download'), 'export-download');
    expect(r.status).toBe(200);
    expect(Buffer.from(await r.arrayBuffer())).toEqual(ZIP);
    expect(r.headers.get('content-type')).toBe('application/zip');
    expect(r.headers.get('content-disposition')).toBe('attachment; filename="salon-export-alpha.zip"');
    expect(r.headers.get('cache-control')).toContain('no-store');
  });

  it.each(['not-a-token', '', 12, `${TOKEN}x`, '../../api/v1/operator/me'])('answers a malformed token %s as unknown without asking the API', async (token) => {
    expect((await run(request('export-download', { token }), 'export-download')).status).toBe(404);
    expect(calls).toEqual([]);
  });

  it('answers an unknown token as not found, saying nothing of a salon', async () => {
    const r = await run(request('export-info', { token: 'x'.repeat(43) }), 'export-info');
    expect(r.status).toBe(404);
    expect(await r.json()).toEqual({ code: 'not_found' });
  });

  it.each(['alpha.localhost:3100', 'console.localhost:3100', 'admin.localhost:3100', 'localhost.evil.test'])('is not found on %s', async (host) => {
    expect((await run(request('export-info', { host, origin: `http://${host}` }), 'export-info')).status).toBe(404);
    expect(calls).toEqual([]);
  });

  it('refuses a foreign or missing origin, cross-site metadata, GET and unknown actions', async () => {
    expect((await run(request('export-info', { origin: '' }), 'export-info')).status).toBe(403);
    expect((await run(request('export-info', { origin: 'http://alpha.localhost:3100' }), 'export-info')).status).toBe(403);
    expect((await run(request('export-info', { site: 'cross-site' }), 'export-info')).status).toBe(403);
    expect((await run(request('export-info', { method: 'GET' }), 'export-info')).status).toBe(405);
    expect((await run(request('salons'), 'salons')).status).toBe(404);
    expect(calls).toEqual([]);
  });
});
