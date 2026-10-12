// @vitest-environment node
import { createServer, type IncomingHttpHeaders, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';

import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { fetchForHostname } from './forward-hostname';

/** A stand-in for the API that records what it was sent. */
let server: Server;
let received: { url: string; headers: IncomingHttpHeaders }[];
let answer: (respond: (status: number, body: string, contentType?: string) => void) => void;

beforeEach(async () => {
  received = [];
  answer = (respond) => respond(200, '{"ok":true}');
  server = createServer((request, response) => {
    received.push({ url: request.url ?? '', headers: request.headers });
    answer((status, body, contentType = 'application/json') => {
      response.writeHead(status, { 'Content-Type': contentType });
      response.end(body);
    });
  });
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
});

afterEach(async () => {
  server.closeAllConnections();
  await new Promise<void>((resolve) => server.close(() => resolve()));
});

const base = () => `http://127.0.0.1:${(server.address() as AddressInfo).port}`;

describe('fetchForHostname', () => {
  it('connects to the address it was given and names the salon only by the Host it sends', async () => {
    const response = await fetchForHostname('alpha.localhost')(`${base()}/api/v1/salon`);

    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ ok: true });
    expect(received).toHaveLength(1);
    expect(received[0]?.url).toBe('/api/v1/salon');
    expect(received[0]?.headers.host).toBe('alpha.localhost');
    // No parameter and no custom header names the salon.
    expect(Object.keys(received[0]?.headers ?? {}).filter((name) => /forwarded|tenant|salon/i.test(name))).toEqual([]);
  });

  it('sends the Host it was made with, whatever Host the request carries', async () => {
    await fetchForHostname('alpha.localhost')(`${base()}/x`, { headers: { Host: 'beta.localhost' } });

    expect(received[0]?.headers.host).toBe('alpha.localhost');
  });

  it('sends the headers the client set, and nothing of any other request', async () => {
    await fetchForHostname('alpha.localhost')(`${base()}/x`, { headers: { Accept: 'application/json' } });

    expect(received[0]?.headers.accept).toBe('application/json');
    expect(received[0]?.headers.cookie).toBeUndefined();
    expect(received[0]?.headers['x-forwarded-host']).toBeUndefined();
  });

  it('returns an error answer as it came, with its status, headers and body', async () => {
    answer = (respond) => respond(403, '{"code":"salon_suspended"}', 'application/problem+json');

    const response = await fetchForHostname('gamma.localhost')(`${base()}/api/v1/salon`);

    expect(response.status).toBe(403);
    expect(response.headers.get('content-type')).toBe('application/problem+json');
    expect(await response.json()).toEqual({ code: 'salon_suspended' });
  });

  it('fails when nothing listens at the address', async () => {
    const address = base();
    server.closeAllConnections();
    await new Promise<void>((resolve) => server.close(() => resolve()));
    server = createServer();
    server.listen(0, '127.0.0.1');

    await expect(fetchForHostname('alpha.localhost')(`${address}/x`)).rejects.toThrow();
  });

  it('gives up when the signal says so, with the signal\'s reason', async () => {
    answer = () => {
      // Accepts the request and answers nothing.
    };

    const pending = fetchForHostname('alpha.localhost')(`${base()}/x`, { signal: AbortSignal.timeout(100) });

    await expect(pending).rejects.toMatchObject({ name: 'TimeoutError' });
  });

  it('refuses at once when the signal has already given up', async () => {
    const signal = AbortSignal.abort();

    await expect(fetchForHostname('alpha.localhost')(`${base()}/x`, { signal })).rejects.toMatchObject({ name: 'AbortError' });
    expect(received).toHaveLength(0);
  });
});
