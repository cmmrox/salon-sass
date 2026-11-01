// @vitest-environment node
import { createServer, type Server, type ServerResponse } from 'node:http';
import type { AddressInfo, Socket } from 'node:net';
import { setFlagsFromString } from 'node:v8';
import { runInNewContext } from 'node:vm';
import { createApiClient } from '@salon/api-contracts/shared';
import { NextRequest } from 'next/server';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createIdentityWeb } from './identity-web';
import type { SalonAccount, SalonAccountFor } from './salon-account';
import type { SalonSettings, SalonSettingsFor } from './salon-settings';
import type { SalonHours, SalonHoursFor } from './salon-hours';
import type { SalonBookingRules, SalonBookingRulesFor } from './salon-booking-rules';
import type { SalonCatalogue, SalonCatalogueFor } from './salon-catalogue';
import { contextFromTraceparent, runInRequest } from '@/lib/observability/request-context';
import { createServerApiClient, requestSignal } from '@/lib/api-core/client';

/** These cases never reach the platform module's paths: any call to them fails. */
const unreached: SalonAccountFor = () =>
  new Proxy({} as SalonAccount, {
    get: () => () => Promise.reject(new Error('the platform module is not reached here')),
  });
/** Nor the branding module's settings paths (F04-S01). */
const unreachedSettings: SalonSettingsFor = () =>
  new Proxy({} as SalonSettings, {
    get: () => () => Promise.reject(new Error('the branding module is not reached here')),
  });
/** Nor the staff module's hours paths (F04-S03). */
const unreachedHours: SalonHoursFor = () =>
  new Proxy({} as SalonHours, {
    get: () => () => Promise.reject(new Error('the staff module is not reached here')),
  });
/** Nor the booking module's rules paths (F04-S04). */
const unreachedRules: SalonBookingRulesFor = () =>
  new Proxy({} as SalonBookingRules, {
    get: () => () => Promise.reject(new Error('the booking module is not reached here')),
  });
const unreachedCatalogue: SalonCatalogueFor = () =>
  new Proxy({} as SalonCatalogue, {
    get: () => () => Promise.reject(new Error('the catalogue module is not reached here')),
  });
const handleIdentity = createIdentityWeb(
  unreached,
  unreachedSettings,
  unreachedHours,
  unreachedRules,
  unreachedCatalogue,
);

/**
 * BUG-F02-012: every server call to the API ends within the request's shared budget, whatever
 * phase an unresponsive API stops in, even when garbage collection runs while it waits. Abort
 * signals that only follow a copied Request are held weakly; a collection could silently drop the
 * deadline and leave the call waiting until the API resumed.
 */
setFlagsFromString('--expose_gc');
const collect = runInNewContext('gc') as () => void;

type Phase = 'silent' | 'headers-only' | 'unread';
let server: Server;
let phase: Phase;
const sockets = new Set<Socket>();
let held: ServerResponse[] = [];
beforeEach(async () => {
  held = [];
  server = createServer((request, response) => {
    held.push(response);
    if (phase === 'headers-only') {
      response.writeHead(200, { 'Content-Type': 'application/json', 'Content-Length': '64' });
      response.write('{"partial":');
    }
    // 'silent': accepts and reads the request, then never answers.
  });
  server.on('connection', (socket) => {
    sockets.add(socket);
    if (phase === 'unread') socket.pause();
  });
  await new Promise<void>((r) => server.listen(0, '127.0.0.1', r));
  vi.stubEnv('SALON_API_BASE_URL', `http://127.0.0.1:${(server.address() as AddressInfo).port}`);
  vi.stubEnv('SALON_PLATFORM_DOMAIN', 'localhost');
  vi.stubEnv('SALON_WEB_LOCAL_HTTP', 'true');
  vi.stubEnv('SALON_IDENTITY_PROXY_KEY', '12'.repeat(32));
});
afterEach(async () => {
  for (const socket of sockets) socket.destroy();
  sockets.clear();
  await new Promise<void>((r) => server.close(() => r()));
  vi.unstubAllEnvs();
});

const BUDGET = 400;
function inRequest<T>(work: () => Promise<T>) {
  const context = {
    ...contextFromTraceparent(undefined),
    clientAddress: '127.0.0.2',
    deadline: performance.now() + BUDGET,
  };
  return runInRequest(context, work);
}
/** Runs the call while forcing collections, and measures when it settles. */
async function settle<T>(work: () => Promise<T>) {
  const started = performance.now();
  const collector = setInterval(() => {
    collect();
    collect();
  }, 25);
  const watchdog = new Promise<'still waiting'>((r) => setTimeout(() => r('still waiting'), BUDGET + 2500));
  try {
    const outcome = await Promise.race([
      work().then(
        (value) => ({ value }),
        (error: unknown) => ({ error }),
      ),
      watchdog,
    ]);
    return { outcome, elapsed: performance.now() - started };
  } finally {
    clearInterval(collector);
  }
}

describe.each(['silent', 'headers-only', 'unread'] as const)('an API that stops answering (%s)', (stop) => {
  beforeEach(() => {
    phase = stop;
  });

  it('a salon call through the hostname transport gives up within the budget', async () => {
    const { outcome, elapsed } = await settle(() =>
      inRequest(() =>
        createServerApiClient(createApiClient, { hostname: 'alpha.localhost' })!.GET('/api/v1/meta', {
          signal: requestSignal(),
        }),
      ),
    );
    expect(outcome).not.toBe('still waiting');
    expect(outcome).toHaveProperty('error');
    expect(elapsed).toBeLessThan(BUDGET + 1000);
  });

  it('a platform call through the platform fetch gives up within the budget', async () => {
    const { outcome, elapsed } = await settle(() =>
      inRequest(() => createServerApiClient(createApiClient)!.GET('/api/v1/meta', { signal: requestSignal() })),
    );
    expect(outcome).not.toBe('still waiting');
    expect(outcome).toHaveProperty('error');
    expect(elapsed).toBeLessThan(BUDGET + 1000);
  });

  it('staff sign-in answers 503 within the budget', async () => {
    const request = new NextRequest('http://alpha.localhost:3100/identity-web/staff/sign-in', {
      method: 'POST',
      headers: {
        Host: 'alpha.localhost:3100',
        Origin: 'http://alpha.localhost:3100',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ identifier: 'staff@alpha.test', password: 'private-password', deviceLabel: 'Browser' }),
    });
    const { outcome, elapsed } = await settle(() => inRequest(() => handleIdentity(request, 'staff', 'sign-in')));
    expect(outcome).not.toBe('still waiting');
    expect((outcome as { value: Response }).value.status).toBe(503);
    expect(elapsed).toBeLessThan(BUDGET + 1000);
  });
});

describe('the caller keeps control of its own call', () => {
  beforeEach(() => {
    phase = 'silent';
  });
  it.each([{ hostname: 'alpha.localhost' }, {}])(
    'an abort by the caller still ends the call at once (%o)',
    async (options) => {
      const caller = new AbortController();
      const context = { ...contextFromTraceparent(undefined), deadline: performance.now() + 60_000 };
      setTimeout(() => caller.abort(new Error('caller gave up')), 100);
      const { outcome, elapsed } = await settle(() =>
        runInRequest(context, () =>
          createServerApiClient(createApiClient, options)!.GET('/api/v1/meta', { signal: caller.signal }),
        ),
      );
      expect(outcome).toHaveProperty('error');
      expect(elapsed).toBeLessThan(1000);
    },
  );
});
