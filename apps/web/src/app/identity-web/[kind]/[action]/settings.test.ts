// @vitest-environment node
import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { NextRequest } from 'next/server';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { contextFromTraceparent, runInRequest } from '@/lib/observability/request-context';
import { salonSettingsFor } from '@/features/branding/server';
import { createIdentityWeb } from '@/features/identity/server';
import { salonAccountFor } from '@/features/platform/server';

/** The settings actions of the workspace BFF (F04-S01), as the route wires them, against a fake API on loopback. */
const handleIdentity = createIdentityWeb(salonAccountFor, salonSettingsFor);
let server: Server;
let calls: { method: string; path: string; host?: string; authorization?: string; body: string }[];
let answer: (method: string, path: string) => { status: number; data: unknown };
let permissions: string[];
const BRANCH = '0b6f9f43-5a1d-4c5e-9a2b-3f3f6e1d2c10';
const draft = {
  version: 3,
  profile: { description: null, links: { instagram: null, facebook: null, website: null }, branches: [] },
  hasUnpublishedChanges: false,
  publishedAt: null,
  publishedBy: null,
};

beforeEach(async () => {
  calls = [];
  permissions = ['session.manage', 'settings.read', 'settings.manage'];
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

describe('the settings user’s profile actions', () => {
  it('reads the draft for the salon’s hostname with the user’s credential', async () => {
    const response = await run('GET', 'staff', 'profile-draft');

    expect(response.status).toBe(200);
    expect(await response.json()).toEqual(draft);
    expect(apiCalls()).toEqual([
      expect.objectContaining({
        method: 'GET',
        path: '/api/v1/salon/profile/draft',
        host: 'alpha.localhost',
        authorization: 'Bearer private-access',
      }),
    ]);
  });

  it('sends a save rebuilt from the contract’s fields, and nothing else the browser sent', async () => {
    const response = await run('POST', 'staff', 'profile-save', {
      version: 3,
      tenantId: 'beta',
      profile: {
        description: 'Words',
        links: { instagram: 'https://instagram.com/x', facebook: 7, website: null, extra: 'x' },
        branches: [
          {
            id: BRANCH,
            address: 'Colombo',
            location: { latitude: 6.9, longitude: 79.8, altitude: 1 },
            phone: '077 123 4567',
            whatsapp: null,
            tenantId: 'beta',
          },
        ],
        salon: 'beta',
      },
    });

    expect(response.status).toBe(200);
    const [save] = apiCalls();
    expect(save).toMatchObject({ method: 'PUT', path: '/api/v1/salon/profile/draft' });
    expect(JSON.parse(save!.body)).toEqual({
      version: 3,
      profile: {
        description: 'Words',
        links: { instagram: 'https://instagram.com/x', facebook: null, website: null },
        branches: [
          {
            id: BRANCH,
            address: 'Colombo',
            location: { latitude: 6.9, longitude: 79.8 },
            phone: '077 123 4567',
            whatsapp: null,
          },
        ],
      },
    });
  });

  it.each([
    ['profile-publish', 'POST', '/api/v1/salon/profile/publication'],
    ['profile-discard', 'DELETE', '/api/v1/salon/profile/draft'],
  ])('%s sends only the version', async (action, method, path) => {
    const response = await run('POST', 'staff', action, { version: 3, profile: { description: 'x' } });

    expect(response.status).toBe(200);
    expect(apiCalls()).toEqual([expect.objectContaining({ method, path, body: JSON.stringify({ version: 3 }) })]);
  });

  it('hands on the API’s refusal with its code and field names', async () => {
    answer = (_method, path) =>
      path === '/api/v1/identity/me'
        ? { status: 200, data: { kind: 'staff', permissions, staffId: 's' } }
        : {
            status: 400,
            data: { code: 'bad_request', errors: [{ field: 'profile.branches[0].phone', detail: 'x' }] },
          };

    const response = await run('POST', 'staff', 'profile-save', { version: 3, profile: {} });

    expect(response.status).toBe(400);
    expect(await response.json()).toMatchObject({
      code: 'bad_request',
      errors: [{ field: 'profile.branches[0].phone' }],
    });
  });

  it.each(['profile-draft', 'profile-save', 'profile-publish', 'profile-discard'])(
    'refuses %s to a user without the settings permission before any API call',
    async (action) => {
      permissions = ['session.manage', 'calendar.read'];
      const response =
        action === 'profile-draft'
          ? await run('GET', 'staff', action)
          : await run('POST', 'staff', action, { version: 3 });

      expect(response.status).toBe(403);
      expect(await response.json()).toMatchObject({ code: 'permission_denied' });
      expect(apiCalls()).toEqual([]);
    },
  );
});

describe('a support session and the profile', () => {
  beforeEach(() => {
    permissions = ['staff.list', 'audit.list', 'entitlement.read', 'invoice.read', 'settings.read'];
    const base = answer;
    answer = (method, path) =>
      path === '/api/v1/identity/me'
        ? { status: 200, data: { kind: 'support', permissions, staffId: null } }
        : base(method, path);
  });

  it('reads the draft', async () => {
    const response = await run('GET', 'support', 'profile-draft');

    expect(response.status).toBe(200);
    expect(apiCalls()).toEqual([expect.objectContaining({ path: '/api/v1/salon/profile/draft' })]);
  });

  it.each(['profile-save', 'profile-publish', 'profile-discard'])(
    'is refused %s as read-only, with no API call',
    async (action) => {
      const response = await run('POST', 'support', action, { version: 3 });

      expect(response.status).toBe(403);
      expect(await response.json()).toMatchObject({ code: 'support_read_only' });
      expect(calls).toEqual([]);
    },
  );
});

describe('the settings user’s brand actions (F04-S02)', () => {
  const BRAND_ACTIONS = [
    ['brand-reset', 'POST', '/api/v1/salon/brand/draft/reset'],
    ['brand-publish', 'POST', '/api/v1/salon/brand/publication'],
    ['brand-discard', 'DELETE', '/api/v1/salon/brand/draft'],
  ];

  it('reads the brand draft for the salon’s hostname', async () => {
    const response = await run('GET', 'staff', 'brand-draft');

    expect(response.status).toBe(200);
    expect(apiCalls()).toEqual([
      expect.objectContaining({ method: 'GET', path: '/api/v1/salon/brand/draft', host: 'alpha.localhost' }),
    ]);
  });

  it('sends a brand save rebuilt from the contract’s fields only', async () => {
    await run('POST', 'staff', 'brand-save', {
      version: 2,
      tenantId: 'beta',
      brand: { name: 'Alpha', tagline: null, colour: '#D6086A', logo: 7, cover: null, extra: 'x' },
    });

    expect(JSON.parse(apiCalls()[0]!.body)).toEqual({
      version: 2,
      brand: { name: 'Alpha', tagline: null, colour: '#D6086A', logo: null, cover: null },
    });
  });

  it.each(BRAND_ACTIONS)('%s sends only the version', async (action, method, path) => {
    const response = await run('POST', 'staff', action!, { version: 2, brand: { name: 'x' } });

    expect(response.status).toBe(200);
    expect(apiCalls()).toEqual([expect.objectContaining({ method, path, body: JSON.stringify({ version: 2 }) })]);
  });

  it('passes an upload on as a new form of its kind and file only', async () => {
    answer = (_method, path) =>
      path === '/api/v1/identity/me'
        ? { status: 200, data: { kind: 'staff', permissions, staffId: 's' } }
        : { status: 201, data: { id: '0b6f9f43-5a1d-4c5e-9a2b-3f3f6e1d2c10', kind: 'logo', width: 512, height: 512 } };
    const form = new FormData();
    form.append('kind', 'logo');
    form.append('file', new Blob([new Uint8Array([0x89, 0x50, 0x4e, 0x47])], { type: 'image/png' }), 'secret-name.png');
    form.append('tenantId', 'beta');

    const response = await run('POST', 'staff', 'brand-image', form);

    expect(response.status).toBe(201);
    const [upload] = apiCalls();
    expect(upload).toMatchObject({
      method: 'POST',
      path: '/api/v1/salon/brand/images',
      authorization: 'Bearer private-access',
    });
    expect(upload!.body).toContain('name="kind"');
    expect(upload!.body).toContain('name="file"');
    expect(upload!.body).not.toContain('tenantId');
    expect(upload!.body).not.toContain('secret-name.png');
  });

  it('refuses an upload without a file, and one declared over the request limit, before any API call', async () => {
    const empty = new FormData();
    empty.append('kind', 'logo');
    const missing = await run('POST', 'staff', 'brand-image', empty);
    expect(missing.status).toBe(400);
    expect(await missing.json()).toMatchObject({ code: 'bad_request', errors: [{ field: 'file' }] });

    const large = new NextRequest('http://alpha.localhost:3100/identity-web/staff/brand-image', {
      method: 'POST',
      headers: {
        Host: 'alpha.localhost:3100',
        Origin: 'http://alpha.localhost:3100',
        'Sec-Fetch-Site': 'same-origin',
        Cookie: 'salon_staff_access=private-access',
        'Content-Type': 'multipart/form-data; boundary=x',
        'Content-Length': String(11 * 1024 * 1024),
      },
      body: '--x--',
    });
    const context = {
      ...contextFromTraceparent(undefined),
      clientAddress: '127.0.0.2',
      deadline: performance.now() + 3000,
    };
    const tooLarge = await runInRequest(context, () => handleIdentity(large, 'staff', 'brand-image'));
    expect(tooLarge.status).toBe(413);
    expect(apiCalls()).toEqual([]);
  });

  it('refuses a chunked upload, which declares no length, as soon as it passes the limit, reading no more', async () => {
    let sent = 0;
    let pulls = 0;
    const chunk = new Uint8Array(1024 * 1024);
    const body = new ReadableStream<Uint8Array>({
      pull(controller) {
        pulls++;
        if (sent >= 64 * 1024 * 1024) return controller.close();
        sent += chunk.byteLength;
        controller.enqueue(chunk);
      },
    });
    const chunked = new NextRequest('http://alpha.localhost:3100/identity-web/staff/brand-image', {
      method: 'POST',
      headers: {
        Host: 'alpha.localhost:3100',
        Origin: 'http://alpha.localhost:3100',
        'Sec-Fetch-Site': 'same-origin',
        Cookie: 'salon_staff_access=private-access',
        'Content-Type': 'multipart/form-data; boundary=x',
      },
      body,
      duplex: 'half',
    } as ConstructorParameters<typeof NextRequest>[1]);
    expect(chunked.headers.get('content-length')).toBeNull();
    const context = {
      ...contextFromTraceparent(undefined),
      clientAddress: '127.0.0.2',
      deadline: performance.now() + 3000,
    };

    const response = await runInRequest(context, () => handleIdentity(chunked, 'staff', 'brand-image'));

    expect(response.status).toBe(413);
    expect(await response.json()).toMatchObject({ code: 'request_rejected' });
    expect(sent).toBeLessThanOrEqual(12 * 1024 * 1024);
    expect(pulls).toBeLessThan(16);
    expect(apiCalls()).toEqual([]);
  });

  it.each(['brand-save', 'brand-reset', 'brand-publish', 'brand-discard', 'brand-image'])(
    'refuses %s to a user without settings.manage before any API call',
    async (action) => {
      permissions = ['session.manage', 'settings.read'];
      const response = await run('POST', 'staff', action, { version: 2 });
      expect(response.status).toBe(403);
      expect(apiCalls()).toEqual([]);
    },
  );
});

describe('a support session and the brand', () => {
  beforeEach(() => {
    permissions = ['staff.list', 'audit.list', 'entitlement.read', 'invoice.read', 'settings.read'];
    const base = answer;
    answer = (method, path) =>
      path === '/api/v1/identity/me'
        ? { status: 200, data: { kind: 'support', permissions, staffId: null } }
        : base(method, path);
  });

  it('reads the brand draft', async () => {
    expect((await run('GET', 'support', 'brand-draft')).status).toBe(200);
  });

  it.each(['brand-save', 'brand-reset', 'brand-publish', 'brand-discard', 'brand-image'])(
    'is refused %s as read-only, with no API call',
    async (action) => {
      const response = await run('POST', 'support', action, { version: 2 });
      expect(response.status).toBe(403);
      expect(await response.json()).toMatchObject({ code: 'support_read_only' });
      expect(calls).toEqual([]);
    },
  );
});
