// @vitest-environment node
import { createServer, type IncomingHttpHeaders, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { runInRequest, type RequestContext } from '@/lib/observability/request-context';

import { createServerApiClient } from './client';
import { findSalon } from '@/features/branding/server/salon';
import { getMeta } from './meta';

/** A stand-in for the API that records what it was sent. */
let server: Server;
let received: IncomingHttpHeaders[];

beforeEach(async () => {
  received = [];
  server = createServer((request, response) => {
    received.push(request.headers);
    response.writeHead(200, { 'Content-Type': 'application/json' });
    response.end(
      request.url === '/api/v1/meta'
        ? '{"service":"salon-api","version":"1","commit":"c","time":"2026-01-01T00:00:00Z"}'
        : '{"slug":"alpha","name":"Alpha Studio","brandColour":"#D6086A","timeZone":"Asia/Colombo"}',
    );
  });
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  vi.stubEnv('SALON_API_BASE_URL', `http://127.0.0.1:${(server.address() as AddressInfo).port}`);
  vi.spyOn(console, 'warn').mockImplementation(() => {});
});

afterEach(async () => {
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
  server.closeAllConnections();
  await new Promise<void>((resolve) => server.close(() => resolve()));
});

const request: RequestContext = { traceId: '4bf92f3577b34da6a3ce929d0e0e4736', spanId: 'a1b2c3d4e5f60718', sampled: true };

describe('calls to the API made while a request is served', () => {
  it("send the request's W3C traceparent with a salon's call, beside the salon's hostname", async () => {
    const lookup = await runInRequest(request, () => findSalon('alpha', 'alpha.localhost'));

    expect(lookup.kind).toBe('found');
    expect(received).toHaveLength(1);
    expect(received[0]?.traceparent).toBe('00-4bf92f3577b34da6a3ce929d0e0e4736-a1b2c3d4e5f60718-01');
    expect(received[0]?.host).toBe('alpha.localhost');
  });

  it("send it with the platform's own calls too", async () => {
    const meta = await runInRequest(request, () => getMeta());

    expect(meta.ok).toBe(true);
    expect(received[0]?.traceparent).toBe('00-4bf92f3577b34da6a3ce929d0e0e4736-a1b2c3d4e5f60718-01');
  });

  it('send each request its own trace when two are served at once', async () => {
    const other: RequestContext = { traceId: 'ffffffffffffffffffffffffffffffff', spanId: '0102030405060708', sampled: false };

    await Promise.all([
      runInRequest(request, () => findSalon('alpha', 'alpha.localhost')),
      runInRequest(other, () => findSalon('alpha', 'alpha.localhost')),
    ]);

    expect(received.map((headers) => headers.traceparent).sort()).toEqual([
      '00-4bf92f3577b34da6a3ce929d0e0e4736-a1b2c3d4e5f60718-01',
      '00-ffffffffffffffffffffffffffffffff-0102030405060708-00',
    ]);
  });
});

describe('calls made outside a request', () => {
  it('send no traceparent, and the API starts a trace of its own', async () => {
    await findSalon('alpha', 'alpha.localhost', createServerApiClient({ hostname: 'alpha.localhost' }));

    expect(received[0]?.traceparent).toBeUndefined();
  });
});
