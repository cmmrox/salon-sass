// @vitest-environment node
import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { NextRequest } from 'next/server';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { contextFromTraceparent, runInRequest } from '@/lib/observability/request-context';
import { GET } from './route';

/** `/service-photos/<id>` on a salon's hostname (F05-S01 AC-10), against a fake API on loopback. */
const ID = '0b6f9f43-5a1d-4c5e-9a2b-3f3f6e1d2c10';
const JPEG = Buffer.from([0xff, 0xd8, 0xff, 0xd9]);
let server: Server;
let calls: { path: string; host?: string; authorization?: string }[];
let answer: (authorization: string | undefined) => { status: number; type: string; body: Buffer };

beforeEach(async () => {
  calls = [];
  vi.stubEnv('SALON_PLATFORM_DOMAIN', 'localhost');
  vi.stubEnv('SALON_WEB_LOCAL_HTTP', 'true');
  answer = () => ({ status: 200, type: 'image/jpeg', body: JPEG });
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
  const request = new NextRequest(`http://alpha.localhost:3100/service-photos/${id}`, {
    headers: { Host: 'alpha.localhost:3100', Cookie: cookie },
  });
  const context = {
    ...contextFromTraceparent(undefined),
    clientAddress: '127.0.0.2',
    deadline: performance.now() + 3000,
  };
  return runInRequest(context, () => GET(request, { params: Promise.resolve({ id }) }));
}

describe('a service photo on the salon hostname', () => {
  it('passes the photo through with no-store caching and nosniff, never with a credential', async () => {
    const response = await get(ID, 'salon_staff_access=private-access');

    expect(response.status).toBe(200);
    expect(response.headers.get('content-type')).toBe('image/jpeg');
    expect(response.headers.get('x-content-type-options')).toBe('nosniff');
    expect(response.headers.get('cache-control')).toBe('private, no-cache, no-store, max-age=0, must-revalidate');
    expect(Buffer.from(await response.arrayBuffer())).toEqual(JPEG);
    expect(calls).toEqual([
      { path: `/api/v1/catalogue/photos/${ID}`, host: 'alpha.localhost', authorization: undefined },
    ]);
  });

  it('answers 404 with the same headers for a removed photo, and calls nothing for an id that is not one', async () => {
    answer = () => ({ status: 404, type: 'application/problem+json', body: Buffer.from('{"code":"not_found"}') });
    const notFound = await get(ID);
    const notAnId = await get('..%2F..%2Fsecret');

    expect(notFound.status).toBe(404);
    expect(notFound.headers.get('x-content-type-options')).toBe('nosniff');
    expect(notFound.headers.get('cache-control')).toBe('private, no-cache, no-store, max-age=0, must-revalidate');
    expect(notAnId.status).toBe(404);
    expect(calls).toHaveLength(1);
  });

  it('refuses anything but a JPEG the API made', async () => {
    answer = () => ({ status: 200, type: 'text/html', body: Buffer.from('<script>') });

    expect((await get(ID)).status).toBe(502);
  });
});
