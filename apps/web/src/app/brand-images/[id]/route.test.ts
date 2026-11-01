// @vitest-environment node
import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { NextRequest } from 'next/server';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { contextFromTraceparent, runInRequest } from '@/lib/observability/request-context';
import { GET } from './route';

/** `/brand-images/<id>` on a salon's hostname (F04-S02), against a fake API on loopback. */
const ID = '0b6f9f43-5a1d-4c5e-9a2b-3f3f6e1d2c10';
const PNG = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
let server: Server;
let calls: { path: string; host?: string; authorization?: string }[];
let answer: (authorization: string | undefined) => { status: number; type: string; body: Buffer };

beforeEach(async () => {
  calls = [];
  vi.stubEnv('SALON_PLATFORM_DOMAIN', 'localhost');
  vi.stubEnv('SALON_WEB_LOCAL_HTTP', 'true');
  answer = () => ({ status: 200, type: 'image/png', body: PNG });
  server = createServer((request, response) => {
    calls.push({ path: request.url!, host: request.headers.host, authorization: request.headers.authorization });
    const result = answer(request.headers.authorization);
    response.writeHead(result.status, { 'Content-Type': result.type });
    response.end(result.body);
  });
  await new Promise<void>((r) => server.listen(0, '127.0.0.1', r));
  vi.stubEnv('SALON_API_BASE_URL', `http://127.0.0.1:${(server.address() as AddressInfo).port}`);
});
afterEach(async () => {
  server.closeAllConnections();
  await new Promise<void>((r) => server.close(() => r()));
  vi.unstubAllEnvs();
});

function get(id: string, cookie = '') {
  const request = new NextRequest(`http://alpha.localhost:3100/brand-images/${id}`, {
    headers: { Host: 'alpha.localhost:3100', Cookie: cookie },
  });
  const context = {
    ...contextFromTraceparent(undefined),
    clientAddress: '127.0.0.2',
    deadline: performance.now() + 3000,
  };
  return runInRequest(context, () => GET(request, { params: Promise.resolve({ id }) }));
}

describe('a brand image on the salon hostname', () => {
  it('passes a published image through with no-store caching and nosniff, asked with no credential', async () => {
    const response = await get(ID, 'salon_staff_access=private-access');

    expect(response.status).toBe(200);
    expect(response.headers.get('content-type')).toBe('image/png');
    expect(response.headers.get('x-content-type-options')).toBe('nosniff');
    expect(response.headers.get('cache-control')).toBe('private, no-cache, no-store, max-age=0, must-revalidate');
    expect(Buffer.from(await response.arrayBuffer())).toEqual(PNG);
    expect(calls).toEqual([
      { path: `/api/v1/salon/brand/images/${ID}`, host: 'alpha.localhost', authorization: undefined },
    ]);
  });

  it('asks again with the session only for an image that is not public', async () => {
    answer = (authorization) =>
      authorization
        ? { status: 200, type: 'image/jpeg', body: PNG }
        : { status: 404, type: 'application/problem+json', body: Buffer.from('{"code":"not_found"}') };

    const response = await get(ID, 'salon_support_access=private-support');

    expect(response.status).toBe(200);
    expect(calls.map((call) => call.authorization)).toEqual([undefined, 'Bearer private-support']);
  });

  it('answers 404 with the same headers, and calls nothing for an id that is not one', async () => {
    answer = () => ({ status: 404, type: 'application/problem+json', body: Buffer.from('{"code":"not_found"}') });
    const notFound = await get(ID);
    const notAnId = await get('..%2F..%2Fsecret');

    expect(notFound.status).toBe(404);
    expect(notFound.headers.get('x-content-type-options')).toBe('nosniff');
    expect(notAnId.status).toBe(404);
    expect(calls).toHaveLength(1);
  });
});
