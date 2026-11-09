// @vitest-environment node
import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { NextRequest } from 'next/server';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { contextFromTraceparent, runInRequest } from '@/lib/observability/request-context';
import { salonSettingsFor } from '@/features/branding/server';
import { createIdentityWeb } from '@/features/identity/server';
import { salonAccountFor } from '@/features/platform/server';
import { salonHoursFor, salonTeamFor } from '@/features/staff/server';
import { salonBookingRulesFor, salonBookingsFor } from '@/features/booking/server';
import { salonCatalogueFor } from '@/features/catalogue/server';

/**
 * The booking actions of the workspace BFF (F07-S03), as the route wires them, against a fake API on loopback: each
 * behind its permission, a support session read-only, every request rebuilt from the contract's own fields and sent
 * for the salon of the request's own hostname only.
 */
const handleIdentity = createIdentityWeb(
  salonAccountFor,
  salonSettingsFor,
  salonHoursFor,
  salonBookingRulesFor,
  salonCatalogueFor,
  salonTeamFor,
  salonBookingsFor,
);
type Call = { method: string; path: string; host?: string; authorization?: string; key?: string; body: string };
let server: Server;
let calls: Call[];
let answer: (method: string, path: string) => { status: number; data: unknown };
let permissions: string[];
let principalKind: string;
const BOOKING = '8d2f6c1e-4b7a-4c3e-9f10-2a3b4c5d6e7f';
const MEMBER = '33333333-0000-4000-8000-000000000001';
const SERVICE = '44444444-0000-4000-8000-000000000001';
const KEY = '0b6f9f43-5a1d-4c5e-9a2b-3f3f6e1d2c10';
const STAFF = ['calendar.read', 'booking.manage', 'booking.attend'];

beforeEach(async () => {
  calls = [];
  permissions = STAFF;
  principalKind = 'staff';
  vi.stubEnv('SALON_PLATFORM_DOMAIN', 'localhost');
  vi.stubEnv('SALON_WEB_LOCAL_HTTP', 'true');
  answer = () => ({ status: 200, data: { id: BOOKING } });
  server = createServer((request, response) => {
    const chunks: Buffer[] = [];
    request.on('data', (chunk) => chunks.push(chunk));
    request.on('end', () => {
      const path = request.url ?? '';
      if (path === '/api/v1/identity/me') {
        response.writeHead(200, { 'Content-Type': 'application/json' });
        response.end(
          JSON.stringify({
            id: 's',
            kind: principalKind,
            role: 'RECEPTIONIST',
            permissions,
            scope: 'salon',
            staffId: null,
            mfaEnabled: false,
            expiresAt: null,
          }),
        );
        return;
      }
      calls.push({
        method: request.method ?? '',
        path,
        host: request.headers.host,
        authorization: request.headers.authorization,
        key: request.headers['idempotency-key'] as string | undefined,
        body: Buffer.concat(chunks).toString('utf8'),
      });
      const reply = answer(request.method ?? '', path);
      if (reply.status === 204) {
        response.writeHead(204);
        response.end();
        return;
      }
      response.writeHead(reply.status, {
        'Content-Type': reply.status < 300 ? 'application/json' : 'application/problem+json',
      });
      response.end(JSON.stringify(reply.data));
    });
  });
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  vi.stubEnv('SALON_API_BASE_URL', `http://127.0.0.1:${(server.address() as AddressInfo).port}`);
});
afterEach(async () => {
  server.closeAllConnections();
  await new Promise<void>((resolve) => server.close(() => resolve()));
  vi.unstubAllEnvs();
});

function request(
  kind: 'staff' | 'support',
  action: string,
  body?: unknown,
  host = 'alpha.localhost:3100',
  origin?: string,
) {
  return new NextRequest(`http://${host}/identity-web/${kind}/${action}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: {
      Host: host,
      Origin: origin ?? `http://${host}`,
      'Sec-Fetch-Site': 'same-origin',
      Cookie:
        kind === 'staff'
          ? 'salon_staff_access=private-access; salon_staff_session=s'
          : 'salon_support_access=private-support; salon_support_session=s',
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
}

const send = (action: string, body?: unknown, kind: 'staff' | 'support' = 'staff', host?: string, origin?: string) =>
  runInRequest(
    { ...contextFromTraceparent(undefined), clientAddress: '127.0.0.2', deadline: performance.now() + 3000 },
    () => handleIdentity(request(kind, action, body, host, origin), kind, action.split('?')[0]!),
  );

const hold = {
  memberId: null,
  serviceId: SERVICE,
  variantId: null,
  date: '2086-06-16',
  time: '10:30',
  customer: { name: 'Amaya Rajapaksa', mobile: '077 123 4567' },
  channel: 'phone',
};

describe('the booking actions of the BFF (F07-S03)', () => {
  it('reads one day for the salon of the request’s own hostname, with only the contract’s parameters', async () => {
    answer = () => ({ status: 200, data: { items: [], page: 0, size: 50, total: 0 } });

    const response = await send(
      `bookings?from=2086-06-15&to=2086-06-15&state=held&memberId=${MEMBER}&q=K7M&page=0&size=50&tenantId=beta`,
    );

    expect(response.status).toBe(200);
    expect(response.headers.get('cache-control')).toBe('private, no-store');
    expect(calls).toHaveLength(1);
    const url = new URL(`http://x${calls[0]!.path}`);
    expect(url.pathname).toBe('/api/v1/bookings');
    expect(Object.fromEntries(url.searchParams)).toEqual({
      from: '2086-06-15',
      to: '2086-06-15',
      state: 'held',
      memberId: MEMBER,
      q: 'K7M',
      page: '0',
      size: '50',
    });
    expect(calls[0]?.host).toBe('alpha.localhost');
    expect(calls[0]?.authorization).toBe('Bearer private-access');
  });

  it('names a salon only by the hostname: another salon’s address reaches that salon, never one from the request', async () => {
    answer = () => ({ status: 200, data: { items: [], page: 0, size: 50, total: 0 } });

    await send(
      'bookings?from=2086-06-15&to=2086-06-15&host=alpha.localhost',
      undefined,
      'staff',
      'beta.localhost:3100',
    );

    expect(calls.map((call) => call.host)).toEqual(['beta.localhost']);
  });

  it('reads one booking by an id of the right form; anything else reaches no other path', async () => {
    await send(`booking?id=${BOOKING}`);
    await send('booking?id=../../identity/staff');

    expect(calls.map((call) => call.path)).toEqual([`/api/v1/bookings/${BOOKING}`, '/api/v1/bookings/0']);
  });

  it('reads free times and open days with booking.manage', async () => {
    answer = () => ({ status: 200, data: { date: '2086-06-16', times: [], reason: 'closed' } });

    await send(`booking-availability?serviceId=${SERVICE}&date=2086-06-16&memberId=${MEMBER}&other=1`);
    await send('booking-open-days?from=2086-06-15&to=2087-06-15');

    expect(calls.map((call) => call.path)).toEqual([
      `/api/v1/bookings/availability?serviceId=${SERVICE}&date=2086-06-16&memberId=${MEMBER}`,
      '/api/v1/bookings/open-days?from=2086-06-15&to=2087-06-15',
    ]);
  });

  it('answers the API’s clock', async () => {
    answer = (_, path) =>
      path === '/api/v1/meta'
        ? { status: 200, data: { service: 'salon-api', version: '1', time: '2086-06-15T02:30:00Z' } }
        : { status: 404, data: { code: 'not_found' } };

    const response = await send('booking-clock');

    expect(await response.json()).toEqual({ now: '2086-06-15T02:30:00Z' });
  });

  it('places a hold with the browser’s key, rebuilt from the contract’s fields', async () => {
    answer = () => ({ status: 201, data: { id: BOOKING, state: 'held' } });

    const response = await send('booking-place', { ...hold, idempotencyKey: KEY, tenantId: 'beta', price: 1 });

    expect(response.status).toBe(201);
    expect(`${calls[0]?.method} ${calls[0]?.path}`).toBe('POST /api/v1/bookings');
    expect(calls[0]?.key).toBe(KEY);
    // A service without variants sends no variantId at all.
    expect(JSON.parse(calls[0]!.body)).toEqual({ ...hold, variantId: undefined });
  });

  it('sends each change to the booking named, a cancellation with its reason and note only', async () => {
    answer = (_, path) =>
      path.endsWith('/release') ? { status: 204, data: null } : { status: 200, data: { id: BOOKING } };

    for (const action of ['confirm', 'arrive', 'start', 'no-show', 'undo-no-show'])
      expect((await send(`booking-${action}`, { id: BOOKING })).status).toBe(200);
    expect(await (await send('booking-release', { id: BOOKING })).json()).toEqual({ ok: true });
    await send('booking-cancel', { id: BOOKING, reason: 'salon_unable', note: '  ', extra: 1 });
    await send('booking-cancel', { id: BOOKING, reason: 'other', note: 'Offered Thursday' });

    expect(calls.map((call) => `${call.method} ${call.path.replace(BOOKING, 'B')}`)).toEqual([
      'POST /api/v1/bookings/B/confirm',
      'POST /api/v1/bookings/B/arrive',
      'POST /api/v1/bookings/B/start',
      'POST /api/v1/bookings/B/no-show',
      'POST /api/v1/bookings/B/undo-no-show',
      'POST /api/v1/bookings/B/release',
      'POST /api/v1/bookings/B/cancel',
      'POST /api/v1/bookings/B/cancel',
    ]);
    expect(JSON.parse(calls[6]!.body)).toEqual({ reason: 'salon_unable', note: null });
    expect(JSON.parse(calls[7]!.body)).toEqual({ reason: 'other', note: 'Offered Thursday' });
  });

  it('passes a refusal on with its code', async () => {
    answer = () => ({ status: 409, data: { code: 'slot_taken', detail: 'Taken.' } });

    const response = await send('booking-place', { ...hold, idempotencyKey: KEY });

    expect(response.status).toBe(409);
    expect((await response.json()).code).toBe('slot_taken');
  });

  it('lets a Professional read and arrive or start, and refuses them every other change before any call', async () => {
    permissions = ['calendar.read', 'booking.attend'];

    expect((await send(`booking?id=${BOOKING}`)).status).toBe(200);
    expect((await send('booking-arrive', { id: BOOKING })).status).toBe(200);
    expect((await send('booking-start', { id: BOOKING })).status).toBe(200);
    for (const action of [
      'booking-place',
      'booking-confirm',
      'booking-release',
      'booking-cancel',
      'booking-no-show',
      'booking-undo-no-show',
    ])
      expect((await send(action, { id: BOOKING, ...hold })).status, action).toBe(403);
    expect((await send('booking-availability?serviceId=x&date=2086-06-16')).status).toBe(403);
    expect(calls.map((call) => call.path)).toEqual([
      `/api/v1/bookings/${BOOKING}`,
      `/api/v1/bookings/${BOOKING}/arrive`,
      `/api/v1/bookings/${BOOKING}/start`,
    ]);
  });

  it('refuses an Accountant, who has no calendar, before any call', async () => {
    permissions = ['financial.read'];

    expect((await send('bookings?from=2086-06-15&to=2086-06-15')).status).toBe(403);
    expect((await send(`booking?id=${BOOKING}`)).status).toBe(403);
    expect((await send('booking-clock')).status).toBe(403);
    expect(calls).toEqual([]);
  });

  it('refuses a change from another origin before any call', async () => {
    const response = await send(
      'booking-cancel',
      { id: BOOKING, reason: 'other' },
      'staff',
      undefined,
      'http://beta.localhost:3100',
    );

    expect(response.status).toBe(403);
    expect((await response.json()).code).toBe('request_rejected');
    expect(calls).toEqual([]);
  });

  it('lets a support session read bookings and refuses every change as read-only', async () => {
    principalKind = 'support';
    permissions = ['calendar.read', 'team.read'];
    answer = () => ({ status: 200, data: { items: [], page: 0, size: 50, total: 0 } });

    expect((await send('bookings?from=2086-06-15&to=2086-06-15', undefined, 'support')).status).toBe(200);
    const refused = await send('booking-arrive', { id: BOOKING }, 'support');

    expect(refused.status).toBe(403);
    expect((await refused.json()).code).toBe('support_read_only');
    expect(calls.map((call) => call.authorization)).toEqual(['Bearer private-support']);
  });
});
