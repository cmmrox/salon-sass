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
import { salonBookingRulesFor } from '@/features/booking/server';
import { salonCatalogueFor } from '@/features/catalogue/server';

/** The team actions of the workspace BFF (F06-S01), as the route wires them, against a fake API on loopback. */
const handleIdentity = createIdentityWeb(
  salonAccountFor,
  salonSettingsFor,
  salonHoursFor,
  salonBookingRulesFor,
  salonCatalogueFor,
  salonTeamFor,
);
let server: Server;
let calls: { method: string; path: string; authorization?: string; body: string }[];
let answer: (method: string, path: string) => { status: number; data: unknown };
let permissions: string[];
let principalKind: string;
const MEMBER = '33333333-0000-4000-8000-000000000001';

const person = {
  name: 'Nimali',
  title: null,
  highlights: null,
  bio: null,
  shownOnline: true,
  services: { categoryIds: [], serviceIds: [] },
  signInAccountId: null,
};

beforeEach(async () => {
  calls = [];
  permissions = ['team.read', 'team.manage', 'staff.list'];
  principalKind = 'staff';
  vi.stubEnv('SALON_PLATFORM_DOMAIN', 'localhost');
  vi.stubEnv('SALON_WEB_LOCAL_HTTP', 'true');
  answer = () => ({ status: 200, data: { id: MEMBER, name: 'Nimali', version: 1 } });
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
            role: 'MANAGER',
            permissions,
            scope: 'salon',
            staffId: 's',
            mfaEnabled: false,
            expiresAt: null,
          }),
        );
        return;
      }
      calls.push({
        method: request.method ?? '',
        path,
        authorization: request.headers.authorization,
        body: Buffer.concat(chunks).toString('latin1'),
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

function request(kind: 'staff' | 'support', action: string, body?: unknown) {
  const form = body instanceof FormData;
  return new NextRequest(`http://alpha.localhost:3100/identity-web/${kind}/${action}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: {
      Host: 'alpha.localhost:3100',
      Origin: 'http://alpha.localhost:3100',
      'Sec-Fetch-Site': 'same-origin',
      Cookie:
        kind === 'staff'
          ? 'salon_staff_access=private-access; salon_staff_session=s'
          : 'salon_support_access=private-support; salon_support_session=s',
      ...(body === undefined || form ? {} : { 'Content-Type': 'application/json' }),
    },
    ...(body === undefined ? {} : { body: form ? body : JSON.stringify(body) }),
  });
}

const send = (action: string, body?: unknown, kind: 'staff' | 'support' = 'staff') =>
  runInRequest(
    { ...contextFromTraceparent(undefined), clientAddress: '127.0.0.2', deadline: performance.now() + 3000 },
    () => handleIdentity(request(kind, action, body), kind, action),
  );

describe('the team actions of the BFF (F06-S01)', () => {
  it('reads the team with team.read, and the accounts with team.manage', async () => {
    answer = () => ({ status: 200, data: { items: [] } });

    expect((await send('team-members')).status).toBe(200);
    expect((await send('team-sign-in-accounts')).status).toBe(200);

    expect(calls.map((call) => `${call.method} ${call.path}`)).toEqual([
      'GET /api/v1/team/members',
      'GET /api/v1/team/sign-in-accounts',
    ]);
    expect(calls[0]?.authorization).toBe('Bearer private-access');
  });

  it('refuses before any call without the permission', async () => {
    permissions = [];

    expect((await send('team-members')).status).toBe(403);
    expect((await send('team-member-add', person)).status).toBe(403);
    expect(calls).toEqual([]);
  });

  it('rebuilds a new person from the contract’s fields: nothing else of the browser’s goes on', async () => {
    answer = () => ({ status: 201, data: { id: MEMBER, name: 'Nimali', version: 0 } });

    const response = await send('team-member-add', { ...person, price: 5, tenantId: 'x' });

    expect(response.status).toBe(201);
    expect(calls[0]?.path).toBe('/api/v1/team/members');
    expect(JSON.parse(calls[0]!.body)).toEqual(person);
  });

  it('saves with the id in the path and the version in the body', async () => {
    await send('team-member-save', { ...person, id: MEMBER, version: 4 });

    expect(`${calls[0]?.method} ${calls[0]?.path}`).toBe(`PUT /api/v1/team/members/${MEMBER}`);
    expect(JSON.parse(calls[0]!.body)).toEqual({ ...person, version: 4 });
  });

  it('removes with the version in the query, and answers no content as ok', async () => {
    answer = () => ({ status: 204, data: null });

    const response = await send('team-member-remove', { id: MEMBER, version: 4 });

    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ ok: true });
    expect(`${calls[0]?.method} ${calls[0]?.path}`).toBe(`DELETE /api/v1/team/members/${MEMBER}?version=4`);
  });

  it('never lets anything but an id reach the path', async () => {
    await send('team-member-save', { ...person, id: '../../identity/staff', version: 0 });

    expect(calls[0]?.path).toBe('/api/v1/team/members/0');
  });

  it('passes the API’s refusal on with its code and items, so the screen can say the detail', async () => {
    answer = () => ({
      status: 409,
      data: { code: 'in_use', errors: [{ field: 'upcomingBookings', detail: '3' }] },
    });

    const response = await send('team-member-remove', { id: MEMBER, version: 0 });

    expect(response.status).toBe(409);
    expect(await response.json()).toMatchObject({
      code: 'in_use',
      errors: [{ field: 'upcomingBookings', detail: '3' }],
    });
  });

  it('removes a photo, and uploads one naming the member in the form', async () => {
    await send('team-photo-remove', { id: MEMBER });
    expect(`${calls[0]?.method} ${calls[0]?.path}`).toBe(`DELETE /api/v1/team/members/${MEMBER}/photo`);

    const form = new FormData();
    form.append('memberId', MEMBER);
    form.append('file', new File(['bytes'], 'p.png', { type: 'image/png' }));
    const response = await send('team-photo', form);

    expect(response.status).toBe(200);
    expect(`${calls[1]?.method} ${calls[1]?.path}`).toBe(`PUT /api/v1/team/members/${MEMBER}/photo`);
    expect(calls[1]?.body).toContain('bytes');
    expect(calls[1]?.body).not.toContain('memberId');
  });

  it('lets a support session read the team and refuses it every change with no call', async () => {
    principalKind = 'support';
    permissions = ['team.read', 'staff.list'];
    answer = () => ({ status: 200, data: { items: [] } });

    expect((await send('team-members', undefined, 'support')).status).toBe(200);
    const change = await send('team-member-add', person, 'support');

    expect(change.status).toBe(403);
    expect(await change.json()).toMatchObject({ code: 'support_read_only' });
    expect(calls.map((call) => call.path)).toEqual(['/api/v1/team/members']);
  });
});
