// @vitest-environment node
import { createServer, request as httpRequest, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';

import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

import { drain, drainSettingsFromEnvironment } from './drain';
import { installRequestLog } from './request-log';
import { isReady, serverState } from './server-state';

let server: Server;
let base: string;
/** How long the stand-in handler takes to answer. */
let answerAfterMs = 0;

beforeAll(async () => {
  installRequestLog(() => {});
  server = createServer((_, response) => {
    setTimeout(() => response.end('answered'), answerAfterMs);
  });
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
});

afterEach(() => {
  serverState().draining = false;
});

function get(): Promise<string> {
  return new Promise((resolve, reject) => {
    const outgoing = httpRequest(base, { agent: false }, (incoming) => {
      let body = '';
      incoming.on('data', (chunk) => (body += chunk));
      incoming.on('end', () => resolve(body));
    });
    outgoing.on('error', reject);
    outgoing.end();
  });
}

describe('drain', () => {
  it('stops being ready at once, keeps answering for the delay, then finishes what is in flight and exits', async () => {
    await get(); // the server is known once a request has arrived on it
    const exit = vi.fn();
    const lines: string[] = [];
    answerAfterMs = 300;

    const started = Date.now();
    const stopping = drain({ delayMs: 200, graceMs: 5000, exit, sink: (line) => lines.push(line) });

    expect(isReady()).toBe(false);
    // Taken during the delay and still being answered when the server closes.
    await new Promise((resolve) => setTimeout(resolve, 100));
    const inFlight = get();
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(exit).not.toHaveBeenCalled();

    await expect(inFlight).resolves.toBe('answered');
    await stopping;
    expect(exit).toHaveBeenCalledExactlyOnceWith(0);
    expect(Date.now() - started).toBeGreaterThanOrEqual(350);
    // After the delay the server takes no new connection.
    await expect(get()).rejects.toThrow();
    expect(lines.map((line) => JSON.parse(line).message)).toEqual([
      'Stopping: not ready; requests are still answered for 200 ms',
      'Stopped: every request was answered',
    ]);
  });
});

describe('drainSettingsFromEnvironment', () => {
  it('waits 5 s and gives requests 30 s unless told otherwise', () => {
    expect(drainSettingsFromEnvironment({})).toMatchObject({ delayMs: 5000, graceMs: 30000 });
    expect(drainSettingsFromEnvironment({ SALON_DRAIN_DELAY_SECONDS: '2', SALON_SHUTDOWN_GRACE_SECONDS: '10' })).toMatchObject({
      delayMs: 2000,
      graceMs: 10000,
    });
    expect(drainSettingsFromEnvironment({ SALON_DRAIN_DELAY_SECONDS: 'soon' })).toMatchObject({ delayMs: 5000 });
  });
});
