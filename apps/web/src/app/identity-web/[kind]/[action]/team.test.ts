// @vitest-environment node
import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { NextRequest } from 'next/server';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { contextFromTraceparent, runInRequest } from '@/lib/observability/request-context';
import { createIdentityWeb } from '@/features/identity/server';
import { salonAccountFor } from '@/features/platform/server';
import { WORKSPACE_ROUTES } from '@/surfaces/workspace/routes';

/** The team actions of the workspace BFF (F06-S01), as the route wires them, against a fake API on loopback. */
const handleIdentity = createIdentityWeb(salonAccountFor, WORKSPACE_ROUTES);
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
      if (principalKind === 'suspended') {
        response.writeHead(403, { 'Content-Type': 'application/problem+json' });
        response.end(JSON.stringify({ code: 'salon_suspended', status: 403, title: 'Forbidden' }));
        return;
      }
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
    () => handleIdentity(request(kind, action, body), kind, action.split('?')[0]!),
  );

/**
 * The API's own refusal of a read by a role without its permission (each use case's `principals.require`): since the
 * lead’s ruling on web H3 the BFF no longer reads the principal before a module's read, so the refusal is the API's.
 */
const PERMISSION_DENIED = { status: 403, data: { code: 'permission_denied', status: 403, title: 'Forbidden' } };
function refusedByTheApi(permission: string, ...paths: string[]) {
  const before = answer;
  answer = (method, path) =>
    method === 'GET' && paths.includes(path.split('?')[0]!) && !permissions.includes(permission)
      ? PERMISSION_DENIED
      : before(method, path);
}

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
    refusedByTheApi('team.read', '/api/v1/team/members');

    // The read is refused 403 permission_denied as the API answers it, with nothing else (lead’s ruling on web H3);
    // the change before any call.
    const read = await send('team-members');
    expect(read.status).toBe(403);
    expect(await read.json()).toEqual({ code: 'permission_denied' });
    expect((await send('team-member-add', person)).status).toBe(403);
    expect(calls.map((call) => [call.method, call.path])).toEqual([['GET', '/api/v1/team/members']]);
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

  it('reads a person’s schedule and the salon’s week with team.read alone', async () => {
    permissions = ['team.read'];
    answer = () => ({ status: 200, data: { version: 1 } });

    expect((await send(`team-schedule?memberId=${MEMBER}`)).status).toBe(200);
    expect((await send('team-opening-hours')).status).toBe(200);

    expect(calls.map((call) => `${call.method} ${call.path}`)).toEqual([
      `GET /api/v1/team/members/${MEMBER}/schedule`,
      'GET /api/v1/salon/opening-hours',
    ]);
  });

  it('refuses the schedule without team.read, and its changes without team.manage, before any call', async () => {
    permissions = [];
    refusedByTheApi('team.read', `/api/v1/team/members/${MEMBER}/schedule`);
    // The read is refused as the API answers it (lead’s ruling on web H3); the changes before any call.
    const read = await send(`team-schedule?memberId=${MEMBER}`);
    expect(read.status).toBe(403);
    expect(await read.json()).toEqual({ code: 'permission_denied' });
    permissions = ['team.read'];
    expect((await send('team-week-save', { id: MEMBER, version: 1, weekly: [] })).status).toBe(403);
    expect((await send('team-date-change-add', { id: MEMBER })).status).toBe(403);
    expect((await send('team-date-change-remove', { id: MEMBER, changeId: MEMBER })).status).toBe(403);
    expect(calls.map((call) => [call.method, call.path])).toEqual([['GET', `/api/v1/team/members/${MEMBER}/schedule`]]);
  });

  it('rebuilds a saved week from the contract’s fields', async () => {
    const monday = {
      day: 'monday',
      working: true,
      starts: '09:00',
      finishes: '18:00',
      breaks: [{ starts: '13:00', finishes: '14:00' }],
    };

    await send('team-week-save', {
      id: MEMBER,
      version: 3,
      tenantId: 'x',
      weekly: [{ ...monday, extra: 1, breaks: [{ ...monday.breaks[0], extra: 1 }] }],
    });

    expect(`${calls[0]?.method} ${calls[0]?.path}`).toBe(`PUT /api/v1/team/members/${MEMBER}/schedule/weekly`);
    expect(JSON.parse(calls[0]!.body)).toEqual({ version: 3, weekly: [monday] });
  });

  it('rebuilds a date change from the contract’s fields, and answers 201', async () => {
    answer = () => ({ status: 201, data: { change: {}, affected: [] } });
    const change = {
      date: '2026-11-05',
      working: true,
      starts: '09:00',
      finishes: '14:00',
      breaks: [],
      note: 'Half day',
    };

    const response = await send('team-date-change-add', { id: MEMBER, ...change, tenantId: 'x' });

    expect(response.status).toBe(201);
    expect(`${calls[0]?.method} ${calls[0]?.path}`).toBe(`POST /api/v1/team/members/${MEMBER}/schedule/date-changes`);
    expect(JSON.parse(calls[0]!.body)).toEqual(change);
  });

  it('removes a date change, answers no content as ok, and lets only ids reach the path', async () => {
    answer = () => ({ status: 204, data: null });

    const response = await send('team-date-change-remove', { id: MEMBER, changeId: MEMBER });

    expect(await response.json()).toEqual({ ok: true });
    expect(`${calls[0]?.method} ${calls[0]?.path}`).toBe(
      `DELETE /api/v1/team/members/${MEMBER}/schedule/date-changes/${MEMBER}`,
    );

    await send('team-date-change-remove', { id: '../..', changeId: '../../identity' });
    expect(calls[1]?.path).toBe('/api/v1/team/members/0/schedule/date-changes/0');
  });

  it('a support session reads a schedule', async () => {
    principalKind = 'support';
    permissions = ['team.read'];
    answer = () => ({ status: 200, data: { version: 1 } });

    expect((await send(`team-schedule?memberId=${MEMBER}`, undefined, 'support')).status).toBe(200);
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

describe('leave and blocked time through the BFF (F06-S03)', () => {
  const leaveBody = { id: MEMBER, firstDay: '2026-11-12', lastDay: '2026-11-14', type: 'sick', note: 'Mine' };
  const blockBody = { id: MEMBER, date: '2026-11-12', starts: '10:00', finishes: '11:00', label: 'Training' };

  it('rebuilds leave from the contract’s fields, and answers 201', async () => {
    answer = () => ({ status: 201, data: { leave: { id: MEMBER }, affected: [] } });

    const response = await send('team-leave-add', { ...leaveBody, extra: 'dropped', tenantId: 'other' });

    expect(response.status).toBe(201);
    expect(`${calls[0]?.method} ${calls[0]?.path}`).toBe(`POST /api/v1/team/members/${MEMBER}/schedule/leave`);
    expect(JSON.parse(calls[0]!.body)).toEqual({
      firstDay: '2026-11-12',
      lastDay: '2026-11-14',
      type: 'sick',
      note: 'Mine',
    });
  });

  it('rebuilds a block from the contract’s fields, with team.block alone, and answers 201', async () => {
    permissions = ['team.read', 'team.block'];
    answer = () => ({ status: 201, data: { block: { id: MEMBER }, affected: [] } });

    const response = await send('team-block-add', { ...blockBody, extra: 'dropped' });

    expect(response.status).toBe(201);
    expect(`${calls[0]?.method} ${calls[0]?.path}`).toBe(`POST /api/v1/team/members/${MEMBER}/schedule/blocked-time`);
    expect(JSON.parse(calls[0]!.body)).toEqual({
      date: '2026-11-12',
      starts: '10:00',
      finishes: '11:00',
      label: 'Training',
    });
  });

  it('removes, answers no content as ok, and lets only ids reach the path', async () => {
    permissions = ['team.read', 'team.manage', 'team.block'];
    answer = () => ({ status: 204, data: null });

    expect(await (await send('team-leave-remove', { id: MEMBER, leaveId: MEMBER })).json()).toEqual({ ok: true });
    expect(await (await send('team-block-remove', { id: MEMBER, blockId: MEMBER })).json()).toEqual({ ok: true });
    await send('team-leave-remove', { id: '../..', leaveId: '../../identity' });
    await send('team-block-remove', { id: '../..', blockId: '../../identity' });

    expect(calls.map((call) => `${call.method} ${call.path}`)).toEqual([
      `DELETE /api/v1/team/members/${MEMBER}/schedule/leave/${MEMBER}`,
      `DELETE /api/v1/team/members/${MEMBER}/schedule/blocked-time/${MEMBER}`,
      'DELETE /api/v1/team/members/0/schedule/leave/0',
      'DELETE /api/v1/team/members/0/schedule/blocked-time/0',
    ]);
  });

  it('asks about a period with the person in the path and the period in the query', async () => {
    permissions = ['team.read', 'team.manage', 'team.block'];
    answer = () => ({ status: 200, data: { affected: [] } });

    const leave = await send(`team-leave-affected?memberId=${MEMBER}&firstDay=2026-11-12&lastDay=2026-11-14`);
    const block = await send(`team-block-affected?memberId=${MEMBER}&date=2026-11-12&starts=10:00&finishes=11:00`);

    expect(leave.status).toBe(200);
    expect(block.status).toBe(200);
    expect(calls[0]?.path).toBe(
      `/api/v1/team/members/${MEMBER}/schedule/leave/affected?firstDay=2026-11-12&lastDay=2026-11-14`,
    );
    expect(decodeURIComponent(calls[1]!.path)).toBe(
      `/api/v1/team/members/${MEMBER}/schedule/blocked-time/affected?date=2026-11-12&starts=10:00&finishes=11:00`,
    );
  });

  it('holds each to its own permission, before any call', async () => {
    refusedByTheApi('team.manage', `/api/v1/team/members/${MEMBER}/schedule/leave/affected`);
    refusedByTheApi('team.block', `/api/v1/team/members/${MEMBER}/schedule/blocked-time/affected`);
    permissions = ['team.read', 'team.block'];
    expect((await send('team-leave-add', leaveBody)).status).toBe(403);
    expect((await send('team-leave-remove', { id: MEMBER, leaveId: MEMBER })).status).toBe(403);
    expect((await send(`team-leave-affected?memberId=${MEMBER}&firstDay=2026-11-12&lastDay=2026-11-14`)).status).toBe(
      403,
    );

    permissions = ['team.read', 'team.manage'];
    expect((await send('team-block-add', blockBody)).status).toBe(403);
    expect((await send('team-block-remove', { id: MEMBER, blockId: MEMBER })).status).toBe(403);
    expect(
      (await send(`team-block-affected?memberId=${MEMBER}&date=2026-11-12&starts=10:00&finishes=11:00`)).status,
    ).toBe(403);

    // The two reads are refused as the API answers them (lead’s ruling on web H3); every change before any call.
    expect(calls.map((call) => [call.method, call.path.split('?')[0]])).toEqual([
      ['GET', `/api/v1/team/members/${MEMBER}/schedule/leave/affected`],
      ['GET', `/api/v1/team/members/${MEMBER}/schedule/blocked-time/affected`],
    ]);
  });

  it('refuses a support session every change and both impact reads, with no call', async () => {
    principalKind = 'support';
    permissions = ['team.read', 'staff.list'];

    for (const [action, body] of [
      ['team-leave-add', leaveBody],
      ['team-leave-remove', { id: MEMBER, leaveId: MEMBER }],
      ['team-block-add', blockBody],
      ['team-block-remove', { id: MEMBER, blockId: MEMBER }],
    ] as const) {
      const change = await send(action, body, 'support');
      expect(change.status).toBe(403);
      expect(await change.json()).toMatchObject({ code: 'support_read_only' });
    }
    const impact = await send(`team-block-affected?memberId=${MEMBER}&date=2026-11-12`, undefined, 'support');
    expect(impact.status).toBe(403);
    expect(await impact.json()).toMatchObject({ code: 'permission_denied' });
    expect(calls).toEqual([]);
  });
});

// Web H3 and F03-S03 AC-1: the proxy no longer looks the salon up for a module's call, so the BFF says a suspension as
// one (503 salon_suspended, nothing more than the unavailable page says), never as an ended session or an outage.
describe('a suspended salon', () => {
  it('answers a module’s read 503 salon_suspended, and nothing else', async () => {
    principalKind = 'suspended';
    const response = await send('team-members');
    expect(response.status).toBe(503);
    expect(await response.json()).toEqual({ code: 'salon_suspended' });
    expect(calls).toEqual([]);
  });
});

describe('a read by a role without its permission (lead’s ruling on web H3)', () => {
  it('is answered 403 from the API’s own answer, and never 200 with the data', async () => {
    permissions = [];
    refusedByTheApi('team.read', '/api/v1/team/members');
    const response = await send('team-members');
    expect(response.status).toBe(403);
    expect(await response.json()).toEqual({ code: 'permission_denied' });
  });
});

// Web H3: a module's read reaches the API without a principal read first, so the API's own refusal of the credential
// is answered as the principal read answered it: invalid_session, with whether the browser may renew.
describe('a read the API refuses for its session', () => {
  it('is invalid_session, not renewable without a refresh credential, and carries nothing of the data', async () => {
    answer = () => ({ status: 401, data: { code: 'invalid_session', status: 401, title: 'Unauthorized' } });
    const response = await send('team-members');
    expect(response.status).toBe(401);
    expect(await response.json()).toEqual({ code: 'invalid_session', renewable: false });
    expect(calls.map((call) => call.path)).toEqual(['/api/v1/team/members']);
  });
});

describe('a suspended salon’s change', () => {
  it('is answered 503 salon_suspended from the session check, before any call', async () => {
    principalKind = 'suspended';
    const response = await send('team-member-add', person);
    expect(response.status).toBe(503);
    expect(await response.json()).toEqual({ code: 'salon_suspended' });
    expect(calls).toEqual([]);
  });
});
