// @vitest-environment node
import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { NextRequest } from 'next/server';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { contextFromTraceparent, runInRequest } from '@/lib/observability/request-context';
import { createIdentityWeb } from './identity-web';
import type { SalonAccount, SalonAccountFor } from './salon-account';
import type { SalonSettings, SalonSettingsFor } from './salon-settings';
import type { SalonHours, SalonHoursFor } from './salon-hours';
import type { SalonBookingRules, SalonBookingRulesFor } from './salon-booking-rules';
import type { SalonCatalogue, SalonCatalogueFor } from './salon-catalogue';
import type { SalonTeam, SalonTeamFor } from './salon-team';
import type { SalonBookings, SalonBookingsFor } from './salon-bookings';

/**
 * BUG-F04-001: a photo sent over a slow connection. The BFF's API budget starts once it has read the request's
 * body, and the upload's own call has its own stated limit (docs/engineering/web.md, "Structure"). Before the
 * fix the budget started with the request's headers, so a body that took longer than the budget to arrive left
 * nothing for the API call and the screen said the service was unavailable.
 */
function unreachedModule<T extends object>(name: string): () => T {
  return () =>
    new Proxy({} as T, {
      get: () => () => Promise.reject(new Error(`the ${name} module is not reached here`)),
    });
}

/** The time the request's headers arrived and its budget started; the body arrives in {@link CHUNK_DELAY} steps. */
const BUDGET = 300;
const CHUNK_DELAY = 250;

let server: Server;
let uploads: { aborted: boolean; memberId: string; size: number }[];

/** The team module as far as a photo goes: it answers as the API would, refusing a call whose signal has ended. */
const team: SalonTeamFor = () =>
  new Proxy({} as SalonTeam, {
    get: (_target, name) =>
      name === 'uploadPhoto'
        ? async (call: { signal: AbortSignal }, memberId: string, file: Blob) => {
            uploads.push({ aborted: call.signal.aborted, memberId, size: file.size });
            if (call.signal.aborted) throw call.signal.reason;
            return { data: { photo: 'stored' }, response: new Response(null, { status: 200 }) };
          }
        : () => Promise.reject(new Error('only the photo is reached here')),
  });

const handleIdentity = createIdentityWeb(
  unreachedModule<SalonAccount>('platform') as SalonAccountFor,
  unreachedModule<SalonSettings>('branding') as SalonSettingsFor,
  unreachedModule<SalonHours>('hours') as SalonHoursFor,
  unreachedModule<SalonBookingRules>('booking rules') as SalonBookingRulesFor,
  unreachedModule<SalonCatalogue>('catalogue') as SalonCatalogueFor,
  team,
  unreachedModule<SalonBookings>('booking') as SalonBookingsFor,
);

beforeEach(async () => {
  uploads = [];
  vi.stubEnv('SALON_PLATFORM_DOMAIN', 'localhost');
  vi.stubEnv('SALON_WEB_LOCAL_HTTP', 'true');
  vi.stubEnv('SALON_IDENTITY_PROXY_KEY', '12'.repeat(32));
  server = createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'application/json' });
    response.end(JSON.stringify({ id: 'staff', kind: 'staff', permissions: ['team.manage'] }));
  });
  await new Promise<void>((r) => server.listen(0, '127.0.0.1', r));
  vi.stubEnv('SALON_API_BASE_URL', `http://127.0.0.1:${(server.address() as AddressInfo).port}`);
});
afterEach(async () => {
  server.closeAllConnections();
  await new Promise<void>((r) => server.close(() => r()));
  vi.unstubAllEnvs();
});

/** A team photo whose multipart body arrives in three parts, {@link CHUNK_DELAY} apart. */
async function slowPhotoUpload(): Promise<NextRequest> {
  const form = new FormData();
  form.append('memberId', '0b5c7a4e-58a1-4f4e-9b0e-5d1f7f2a9c01');
  form.append('file', new Blob([new Uint8Array(30_000).fill(7)], { type: 'image/jpeg' }), 'photo.jpg');
  const encoded = new Response(form);
  const contentType = encoded.headers.get('content-type')!;
  const bytes = new Uint8Array(await encoded.arrayBuffer());
  const third = Math.ceil(bytes.byteLength / 3);
  let sent = 0;
  const body = new ReadableStream<Uint8Array>({
    async pull(controller) {
      await new Promise((r) => setTimeout(r, CHUNK_DELAY));
      if (sent >= bytes.byteLength) {
        controller.close();
        return;
      }
      controller.enqueue(bytes.slice(sent, sent + third));
      sent += third;
    },
  });
  return new NextRequest('http://alpha.localhost:3100/identity-web/staff/team-photo', {
    method: 'POST',
    headers: {
      Host: 'alpha.localhost:3100',
      Origin: 'http://alpha.localhost:3100',
      Cookie: 'salon_staff_access=private-access',
      'Content-Type': contentType,
    },
    body,
    duplex: 'half',
  } as ConstructorParameters<typeof NextRequest>[1]);
}

describe('an upload over a slow connection', () => {
  it('is passed on to the API with a budget that starts once its body has arrived', async () => {
    const request = await slowPhotoUpload();
    const context = {
      ...contextFromTraceparent(undefined),
      clientAddress: '127.0.0.2',
      deadline: performance.now() + BUDGET,
    };

    const response = await runInRequest(context, () => handleIdentity(request, 'staff', 'team-photo'));

    expect(uploads).toEqual([{ aborted: false, memberId: '0b5c7a4e-58a1-4f4e-9b0e-5d1f7f2a9c01', size: 30_000 }]);
    expect(response.status).toBe(200);
  });
});
