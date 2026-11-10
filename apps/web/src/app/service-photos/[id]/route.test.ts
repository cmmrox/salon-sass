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
    // The API reads a photo under the hostname's salon only (row-level security): another salon has none of it.
    const result =
      request.headers.host === 'alpha.localhost'
        ? answer(request.headers.authorization)
        : { status: 404, type: 'application/problem+json', body: Buffer.from('{"code":"not_found"}') };
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

function get(id: string, cookie = '', host = 'alpha.localhost:3100') {
  const request = new NextRequest(`http://${host}/service-photos/${id}`, {
    headers: { Host: host, Cookie: cookie },
  });
  const context = {
    ...contextFromTraceparent(undefined),
    clientAddress: '127.0.0.2',
    deadline: performance.now() + 3000,
  };
  return runInRequest(context, () => GET(request, { params: Promise.resolve({ id }) }));
}

describe('a service photo on the salon hostname', () => {
  it('passes the photo through for the browser alone to keep, with nosniff, never with a credential', async () => {
    const response = await get(ID, 'salon_staff_access=private-access');

    expect(response.status).toBe(200);
    expect(response.headers.get('content-type')).toBe('image/jpeg');
    expect(response.headers.get('x-content-type-options')).toBe('nosniff');
    // The lead's ruling on web M6: a service photo, public and addressed by an id a new upload replaces.
    expect(response.headers.get('cache-control')).toBe('private, max-age=86400, immutable');
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

// The isolation argument of the lead's ruling on web M6, as a check: the browser keeps a photo under its full address,
// whose hostname is the salon's, and the same path on another salon's hostname is that salon's question, answered not
// found and stored by nothing.
describe('a photo kept by one salon’s visitors', () => {
  it('is not found, and not stored, on another salon’s hostname', async () => {
    const own = await get(ID);
    expect(own.headers.get('cache-control')).toBe('private, max-age=86400, immutable');
    const other = await get(ID, '', 'beta.localhost:3100');
    expect(other.status).toBe(404);
    expect(other.headers.get('cache-control')).toBe('private, no-cache, no-store, max-age=0, must-revalidate');
    expect(calls.map((call) => call.host)).toEqual(['alpha.localhost', 'beta.localhost']);
  });
});
