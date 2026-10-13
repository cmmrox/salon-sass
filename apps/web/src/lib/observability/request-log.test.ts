// @vitest-environment node
import { createServer, request as httpRequest, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';

import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';

import { currentRequest, markTenant } from './request-context';
import { installRequestLog, pathOf } from './request-log';
import { serverState } from './server-state';

/** Every line the request log wrote, parsed. */
const lines: Record<string, unknown>[] = [];

let server: Server;
let base: string;

beforeAll(async () => {
  installRequestLog((line) => lines.push(JSON.parse(line)));
  // A stand-in for the framework's handler: answers with what the request context held.
  server = createServer((request, response) => {
    setTimeout(() => {
      if (request.url?.startsWith('/salon')) {
        markTenant('alpha');
      }
      const context = currentRequest();
      response.writeHead(request.url?.startsWith('/missing') ? 404 : 200, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify(context ?? null));
    }, 5);
  });
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
});

afterAll(async () => {
  server.closeAllConnections();
  await new Promise<void>((resolve) => server.close(() => resolve()));
});

beforeEach(() => {
  lines.length = 0;
});

function get(path: string, headers: Record<string, string> = {}): Promise<{ status: number; body: string }> {
  return new Promise((resolve, reject) => {
    const outgoing = httpRequest(`${base}${path}`, { headers }, (incoming) => {
      let body = '';
      incoming.on('data', (chunk) => (body += chunk));
      incoming.on('end', () => resolve({ status: incoming.statusCode ?? 0, body }));
    });
    outgoing.on('error', reject);
    outgoing.end();
  });
}

async function linesFor(count: number): Promise<Record<string, unknown>[]> {
  for (let waited = 0; lines.length < count && waited < 2000; waited += 10) {
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
  return lines;
}

describe('the request log', () => {
  it('writes one line per request: method, path, status, duration, salon and trace', async () => {
    const answer = await get('/salon/page');
    const [line, ...others] = await linesFor(1);

    expect(others).toEqual([]);
    const context = JSON.parse(answer.body);
    expect(line).toMatchObject({
      log: { level: 'INFO' },
      service: { name: 'salon-web' },
      message: expect.stringMatching(/^GET \/salon\/page 200 \d+ ms$/),
      http: { request: { method: 'GET' }, response: { status_code: 200 } },
      url: { path: '/salon/page' },
      tenant: { slug: 'alpha' },
      trace: { id: context.traceId },
      span: { id: context.spanId },
    });
    expect((line?.event as { duration: number }).duration).toBeGreaterThanOrEqual(5_000_000);
  });

  it('names no salon when the hostname names none', async () => {
    await get('/missing');
    const [line] = await linesFor(1);

    expect(line).toMatchObject({ http: { response: { status_code: 404 } } });
    expect(line).not.toHaveProperty('tenant');
  });

  it("continues the caller's trace, and gives the request a span of its own", async () => {
    const answer = await get('/salon', { traceparent: '00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01' });
    const [line] = await linesFor(1);

    expect(JSON.parse(answer.body).traceId).toBe('4bf92f3577b34da6a3ce929d0e0e4736');
    expect(line).toMatchObject({ trace: { id: '4bf92f3577b34da6a3ce929d0e0e4736' } });
    expect((line?.span as { id: string }).id).not.toBe('00f067aa0ba902b7');
  });

  it('never writes the query string, a cookie or an authorisation header', async () => {
    await get('/salon/page?token=s3cr3t-query&code=123456', {
      Cookie: 'session=s3cr3t-cookie',
      Authorization: 'Bearer s3cr3t-bearer',
    });
    const [line] = await linesFor(1);

    expect(line).toMatchObject({ url: { path: '/salon/page' } });
    const written = JSON.stringify(lines);
    for (const secret of ['s3cr3t-query', '123456', 'token=', 's3cr3t-cookie', 'session=', 's3cr3t-bearer', 'Bearer']) {
      expect(written).not.toContain(secret);
    }
  });

  it('keeps the requests of one connection and of simultaneous connections apart', async () => {
    const answers = await Promise.all(['/salon/a', '/missing/b', '/salon/c'].map((path) => get(path)));
    const logged = await linesFor(3);

    for (const answer of answers) {
      const context = JSON.parse(answer.body);
      const line = logged.find((candidate) => (candidate.trace as { id: string }).id === context.traceId);
      expect(line).toBeDefined();
    }
    expect(new Set(logged.map((line) => (line.trace as { id: string }).id)).size).toBe(3);
    expect(logged.filter((line) => 'tenant' in line)).toHaveLength(2);
  });

  it('counts the requests in flight, for a stop that waits for them', async () => {
    const before = serverState().inFlight;
    const pending = get('/salon/slow');
    for (let waited = 0; serverState().inFlight === before && waited < 1000; waited += 1) {
      await new Promise((resolve) => setTimeout(resolve, 1));
    }
    expect(serverState().inFlight).toBe(before + 1);
    await pending;
    await linesFor(1);
    expect(serverState().inFlight).toBe(before);
    expect(serverState().servers.has(server)).toBe(true);
  });
});

describe('pathOf', () => {
  it.each([
    ['/a/b?c=d', '/a/b'],
    ['/a#frag', '/a'],
    ['/', '/'],
    ['http://alpha.localhost/x/y?z=1', '/x/y'],
    [undefined, '/'],
    ['not a url', '/'],
  ])('%j is %j', (url, path) => {
    expect(pathOf(url)).toBe(path);
  });
});
