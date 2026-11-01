// @vitest-environment node
import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { NextRequest } from 'next/server';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { contextFromTraceparent, runInRequest } from '@/lib/observability/request-context';
import { salonSettingsFor } from '@/features/branding/server';
import { createIdentityWeb } from '@/features/identity/server';
import { salonAccountFor } from '@/features/platform/server';
import { salonHoursFor } from '@/features/staff/server';
import { salonBookingRulesFor } from '@/features/booking/server';
import { salonCatalogueFor } from '@/features/catalogue/server';

/** The catalogue actions of the workspace BFF (F05-S01), as the route wires them, against a fake API on loopback. */
const handleIdentity = createIdentityWeb(
  salonAccountFor,
  salonSettingsFor,
  salonHoursFor,
  salonBookingRulesFor,
  salonCatalogueFor,
);
let server: Server;
let calls: { method: string; path: string; host?: string; authorization?: string; body: string }[];
let answer: (method: string, path: string) => { status: number; data: unknown };
let permissions: string[];
const SERVICE = '11111111-0000-4000-8000-000000000001';
const COLOUR = '6b0a3c1e-9a4e-4b8e-9d1f-2a3b4c5d6e70';
const draft = { listVersion: 4, items: [] };

beforeEach(async () => {
  calls = [];
  permissions = ['session.manage', 'catalogue.read', 'catalogue.price.change'];
  vi.stubEnv('SALON_PLATFORM_DOMAIN', 'localhost');
  vi.stubEnv('SALON_WEB_LOCAL_HTTP', 'true');
  answer = (_method, path) =>
    path === '/api/v1/identity/me'
      ? {
          status: 200,
          data: {
            id: 's',
            kind: 'staff',
            role: 'MANAGER',
            permissions,
            scope: 'salon',
            staffId: 's',
            mfaEnabled: false,
            expiresAt: null,
          },
        }
      : { status: 200, data: draft };
  server = createServer((request, response) => {
    let body = '';
    request.on('data', (chunk) => {
      body += chunk;
    });
    request.on('end', () => {
      calls.push({
        method: request.method!,
        path: request.url!.split('?')[0]!,
        host: request.headers.host,
        authorization: request.headers.authorization,
        body,
      });
      const result = answer(request.method!, request.url!.split('?')[0]!);
      response.writeHead(result.status, {
        'Content-Type': result.status < 300 ? 'application/json' : 'application/problem+json',
      });
      response.end(JSON.stringify(result.data));
    });
  });
  await new Promise<void>((r) => server.listen(0, '127.0.0.1', r));
  vi.stubEnv('SALON_API_BASE_URL', `http://127.0.0.1:${(server.address() as AddressInfo).port}`);
});
afterEach(async () => {
  server.closeAllConnections();
  await new Promise<void>((r) => server.close(() => r()));
  vi.unstubAllEnvs();
});

function request(method: string, kind: 'staff' | 'support', action: string, body?: unknown) {
  if (body instanceof FormData) {
    return new NextRequest(`http://alpha.localhost:3100/identity-web/${kind}/${action}`, {
      method,
      headers: {
        Host: 'alpha.localhost:3100',
        Origin: 'http://alpha.localhost:3100',
        'Sec-Fetch-Site': 'same-origin',
        Cookie:
          kind === 'staff'
            ? 'salon_staff_access=private-access; salon_staff_session=s'
            : 'salon_support_access=private-support; salon_support_session=s',
      },
      body,
    });
  }
  return new NextRequest(`http://alpha.localhost:3100/identity-web/${kind}/${action}`, {
    method,
    headers: {
      Host: 'alpha.localhost:3100',
      Origin: 'http://alpha.localhost:3100',
      'Sec-Fetch-Site': 'same-origin',
      Cookie:
        kind === 'staff'
          ? 'salon_staff_access=private-access; salon_staff_session=s'
          : 'salon_support_access=private-support; salon_support_session=s',
      'Content-Type': 'application/json',
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
}
function run(method: string, kind: 'staff' | 'support', action: string, body?: unknown) {
  const context = {
    ...contextFromTraceparent(undefined),
    clientAddress: '127.0.0.2',
    deadline: performance.now() + 3000,
  };
  return runInRequest(context, () => handleIdentity(request(method, kind, action, body), kind, action));
}
const apiCalls = () => calls.filter((call) => call.path !== '/api/v1/identity/me');

describe('the catalogue actions (F05-S01)', () => {
  it('reads the categories and services for the salon’s hostname with the user’s credential', async () => {
    expect((await run('GET', 'staff', 'catalogue-categories')).status).toBe(200);
    expect((await run('GET', 'staff', 'catalogue-services')).status).toBe(200);

    expect(apiCalls()).toEqual([
      expect.objectContaining({
        method: 'GET',
        path: '/api/v1/catalogue/categories',
        host: 'alpha.localhost',
        authorization: 'Bearer private-access',
      }),
      expect.objectContaining({ method: 'GET', path: '/api/v1/catalogue/services' }),
    ]);
  });

  it('lets a role with catalogue.read read and refuses its changes without calling the API (AC-13)', async () => {
    permissions = ['session.manage', 'catalogue.read'];

    expect((await run('GET', 'staff', 'catalogue-services')).status).toBe(200);
    const refused = await run('POST', 'staff', 'catalogue-category-add', { name: 'Kids' });

    expect(refused.status).toBe(403);
    expect(await refused.json()).toMatchObject({ code: 'permission_denied' });
    expect(apiCalls().map((call) => call.path)).toEqual(['/api/v1/catalogue/services']);
  });

  it('refuses a role without catalogue.read the reads', async () => {
    permissions = ['session.manage'];
    expect((await run('GET', 'staff', 'catalogue-categories')).status).toBe(403);
    expect(apiCalls()).toEqual([]);
  });

  it('rebuilds a service from the contract’s own fields and drops anything else', async () => {
    const response = await run('POST', 'staff', 'catalogue-service-save', {
      id: SERVICE,
      name: 'Balayage',
      categoryId: COLOUR,
      description: null,
      priceMinor: 1_200_000,
      durationMinutes: 180,
      bufferMinutes: 0,
      bookableOnline: true,
      version: 2,
      tenantId: 'someone-else',
    });

    expect(response.status).toBe(200);
    const [call] = apiCalls();
    expect(call).toMatchObject({ method: 'PUT', path: `/api/v1/catalogue/services/${SERVICE}` });
    expect(JSON.parse(call!.body)).toEqual({
      name: 'Balayage',
      categoryId: COLOUR,
      description: null,
      priceMinor: 1_200_000,
      durationMinutes: 180,
      bufferMinutes: 0,
      bookableOnline: true,
      version: 2,
    });
  });

  it('passes a move, a delete with its version and a 201 on as the API answers them', async () => {
    answer = (method, path) =>
      path === '/api/v1/identity/me'
        ? {
            status: 200,
            data: {
              id: 's',
              kind: 'staff',
              role: 'OWNER',
              permissions,
              scope: 'salon',
              staffId: 's',
              mfaEnabled: false,
              expiresAt: null,
            },
          }
        : method === 'DELETE'
          ? { status: 204, data: null }
          : path.endsWith('/duplicate')
            ? { status: 201, data: { id: 'copy' } }
            : { status: 200, data: draft };

    expect(
      (await run('POST', 'staff', 'catalogue-category-move', { id: COLOUR, direction: 'up', listVersion: 4 })).status,
    ).toBe(200);
    expect(await (await run('POST', 'staff', 'catalogue-service-delete', { id: SERVICE, version: 3 })).json()).toEqual({
      ok: true,
    });
    expect((await run('POST', 'staff', 'catalogue-service-duplicate', { id: SERVICE })).status).toBe(201);

    expect(apiCalls().map((call) => [call.method, call.path, call.body])).toEqual([
      ['POST', `/api/v1/catalogue/categories/${COLOUR}/move`, JSON.stringify({ direction: 'up', listVersion: 4 })],
      ['DELETE', `/api/v1/catalogue/services/${SERVICE}`, ''],
      ['POST', `/api/v1/catalogue/services/${SERVICE}/duplicate`, ''],
    ]);
  });

  it('passes a photo on as the file alone, to the service the form names', async () => {
    const form = new FormData();
    form.append('serviceId', SERVICE);
    form.append('file', new Blob([new Uint8Array([0xff, 0xd8, 0xff])], { type: 'image/jpeg' }), 'photo.jpg');

    const response = await run('POST', 'staff', 'catalogue-photo', form);

    expect(response.status).toBe(200);
    const [call] = apiCalls();
    expect(call).toMatchObject({ method: 'PUT', path: `/api/v1/catalogue/services/${SERVICE}/photo` });
    expect(call!.body).toContain('name="file"');
    expect(call!.body).not.toContain('serviceId');
  });

  it('lets a support session read the catalogue and refuses its changes as the API does', async () => {
    answer = (_method, path) =>
      path === '/api/v1/identity/me'
        ? {
            status: 200,
            data: {
              id: 'p',
              kind: 'support',
              role: null,
              permissions: ['catalogue.read'],
              scope: 'salon',
              staffId: null,
              mfaEnabled: false,
              expiresAt: '2026-11-01T10:00:00Z',
            },
          }
        : { status: 200, data: draft };

    expect((await run('GET', 'support', 'catalogue-services')).status).toBe(200);
    const refused = await run('POST', 'support', 'catalogue-service-online', {
      id: SERVICE,
      bookableOnline: false,
      version: 0,
    });

    expect(refused.status).toBe(403);
    expect(await refused.json()).toMatchObject({ code: 'support_read_only' });
    expect(apiCalls().map((call) => call.path)).toEqual(['/api/v1/catalogue/services']);
  });
});
