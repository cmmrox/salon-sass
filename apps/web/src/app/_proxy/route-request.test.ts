// @vitest-environment node
import type { Salon } from '@/features/branding';
import { SALON_HEADER, decodeSalon, encodeSalon } from '@/features/branding/server';
import { getRewrittenUrl, isRewrite, unstable_doesMiddlewareMatch } from 'next/experimental/testing/server';
import { NextRequest } from 'next/server';
import { describe, expect, it, vi } from 'vitest';

import { config } from '@/proxy';

import { SALON_SHELL_ROUTE, routeRequest } from './route-request';
import type { Site } from './site';

const alpha: Salon = { slug: 'alpha', name: 'Alpha Studio', brandColour: '#D6086A', timeZone: 'Asia/Colombo' };
const beta: Salon = {
  slug: 'beta',
  name: 'Beta Hair, Beauty & Wellness Lounge of Colombo Seven',
  brandColour: '#1F2937',
  timeZone: 'Asia/Colombo',
};

function request(path: string, headers: Record<string, string> = {}): NextRequest {
  return new NextRequest(`http://alpha.localhost:3100${path}`, {
    headers: { host: 'alpha.localhost:3100', ...headers },
  });
}

function resolving(site: Site) {
  return vi.fn<(host: string | null) => Promise<Site>>(async () => site);
}

/** The salon handed to the page, as the framework passes the proxy's request headers on. */
function handedOn(response: Response): Salon | null {
  const overridden = (response.headers.get('x-middleware-override-headers') ?? '').split(',');
  expect(overridden, 'the proxy replaces the request headers').not.toHaveLength(0);
  return overridden.includes(SALON_HEADER)
    ? decodeSalon(response.headers.get(`x-middleware-request-${SALON_HEADER}`))
    : null;
}

describe('routeRequest', () => {
  it.each(['GET', 'POST'])(
    'rejects removed customer BFF %s before tenant resolution on every hostname',
    async (method) => {
      const resolve = resolving({ kind: 'unavailable' });
      for (const host of [
        'alpha.localhost:3100',
        'gamma.localhost:3100',
        'missing.localhost:3100',
        'localhost:3100',
        'alpha.localhost.example.com',
      ]) {
        for (const action of [
          '',
          '/',
          '/principal',
          '/sessions',
          '/profile',
          '/code',
          '/sign-in',
          '/refresh',
          '/clear',
          '/export',
          '/delete',
          '/logout',
          '/unknown',
        ]) {
          const req = new NextRequest(`http://alpha.localhost:3100/identity-web/customer${action}?salon=beta`, {
            method,
            headers: {
              host,
              cookie: 'salon_customer_access=old; salon_staff_access=private',
              [SALON_HEADER]: encodeSalon(beta),
            },
          });
          const response = await routeRequest(req, resolve);
          expect(response.status).toBe(404);
          expect(await response.json()).toEqual({ code: 'not_found' });
          expect(response.headers.get('cache-control')).toContain('no-store');
          expect(response.headers.has('set-cookie')).toBe(false);
          expect(response.headers.has('x-middleware-request-' + SALON_HEADER)).toBe(false);
        }
      }
      expect(resolve).not.toHaveBeenCalled();
    },
  );

  it.each([
    '/identity-web/staff/principal',
    '/identity-web/customer-like/principal',
    '/identity-web/customers/principal',
  ])('retains host resolution and unavailable status for other namespaces: %s', async (path) => {
    const resolve = resolving({ kind: 'unavailable' });
    const response = await routeRequest(request(path), resolve);
    expect(resolve).toHaveBeenCalledExactlyOnceWith('alpha.localhost:3100');
    expect(response.status).toBe(503);
    expect(await response.text()).toContain('data-testid="status-unavailable"');
  });

  it.each([
    ['/', '/workspace'],
    ['/sign-in', '/workspace/sign-in'],
    ['/account', '/workspace/sign-in'],
  ])('redirects %s to staff entry %s without a customer surface', async (path, destination) => {
    const resolve = resolving({ kind: 'salon', salon: alpha });
    const response = await routeRequest(request(`${path}?returnTo=https://outside.test&salon=beta`), resolve);
    expect(resolve).toHaveBeenCalledExactlyOnceWith('alpha.localhost:3100');
    expect(response.status).toBe(307);
    expect(response.headers.get('location')).toBe(`http://alpha.localhost:3100${destination}`);
    expect(response.headers.get('cache-control')).toContain('no-store');
    expect(await response.text()).not.toContain('salon-shell');
  });

  it('reads the salon from Host only: the query, cookies and other headers naming another salon change nothing', async () => {
    const resolve = resolving({ kind: 'salon', salon: alpha });

    const response = await routeRequest(
      request(`/workspace/sign-in?salon=beta&tenantId=b2b2b2b2-0000-4000-8000-000000000002`, {
        cookie: 'salon=beta; tenant=b2b2b2b2-0000-4000-8000-000000000002',
        'x-forwarded-host': 'beta.localhost:3100',
        'x-tenant-id': 'b2b2b2b2-0000-4000-8000-000000000002',
        forwarded: 'host=beta.localhost',
      }),
      resolve,
    );

    expect(resolve).toHaveBeenCalledExactlyOnceWith('alpha.localhost:3100');
    expect(handedOn(response)).toEqual(alpha);
    // The rewrite carries no query: nothing the client sent reaches the shell that way either.
    expect(response.headers.get('x-middleware-next')).toBe('1');
  });

  it('a salon header the client sends never reaches a page', async () => {
    const forged = { [SALON_HEADER]: encodeSalon(beta) };

    const onSalon = await routeRequest(
      request('/workspace/sign-in', forged),
      resolving({ kind: 'salon', salon: alpha }),
    );
    expect(handedOn(onSalon)).toEqual(alpha);

    const onPlatform = await routeRequest(request('/', forged), resolving({ kind: 'platform' }));
    expect(handedOn(onPlatform)).toBeNull();
  });

  it('passes the bare platform domain to the application unchanged: the status page', async () => {
    const response = await routeRequest(request('/'), resolving({ kind: 'platform' }));

    expect(isRewrite(response)).toBe(false);
    expect(response.headers.get('x-middleware-next')).toBe('1');
  });

  it.each([
    ['salon not found', { kind: 'not-found' }, 404, 'salon-not-found'],
    ['temporarily unavailable', { kind: 'suspended' }, 503, 'salon-unavailable'],
    ['service unavailable', { kind: 'unavailable' }, 503, 'status-unavailable'],
  ] as const)(
    'answers %s itself, as complete HTML with its status and nothing stored',
    async (_, site, status, testId) => {
      for (const path of ['/', '/sign-in', '/account', '/workspace/sign-in', '/anything/else']) {
        const response = await routeRequest(request(path), resolving(site));

        expect(isRewrite(response)).toBe(false);
        expect(response.status).toBe(status);
        expect(response.headers.get('content-type')).toBe('text/html; charset=utf-8');
        expect(response.headers.get('cache-control')).toContain('no-store');
        const html = await response.text();
        expect(html).toContain(`data-testid="${testId}"`);
        expect(html).not.toContain(alpha.name);
      }
    },
  );

  it("hands the salon on to any other path of an active salon's hostname, without rewriting it", async () => {
    const response = await routeRequest(request('/somewhere'), resolving({ kind: 'salon', salon: alpha }));

    expect(isRewrite(response)).toBe(false);
    expect(handedOn(response)).toEqual(alpha);
    expect(response.headers.get('cache-control')).toContain('no-store');
  });

  it.each([SALON_SHELL_ROUTE, `${SALON_SHELL_ROUTE}/`, `${SALON_SHELL_ROUTE}/missing`])(
    'answers the shell route asked for from outside as a page that does not exist, with no salon: %s',
    async (path) => {
      const resolve = resolving({ kind: 'salon', salon: alpha });

      const response = await routeRequest(request(path, { [SALON_HEADER]: encodeSalon(beta) }), resolve);

      expect(isRewrite(response)).toBe(true);
      expect(new URL(getRewrittenUrl(response)!).pathname).not.toBe(SALON_SHELL_ROUTE);
      expect(handedOn(response)).toBeNull();
      expect(resolve).not.toHaveBeenCalled();
    },
  );

  // BUG-F02-014: a server bound to one address (HOSTNAME=127.0.0.1) proxies a rewrite whose origin is
  // the Host header's over HTTP to itself, so the not-found target comes back through the proxy. It must
  // end there, on every hostname and whatever the API says, or the request rewrites itself without end.
  it.each([
    ['alpha.localhost:3100', SALON_SHELL_ROUTE],
    ['alpha.localhost:3100', `${SALON_SHELL_ROUTE}/x`],
    ['alpha.localhost:3100', '/workspace;x'],
    ['alpha.localhost:3100', '/exports/x'],
    ['alpha.localhost:3100', '/console/salons'],
    ['console.localhost:3100', '/workspace'],
    ['console.localhost:3100', SALON_SHELL_ROUTE],
  ])(
    'answers the not-found target %s%s rewrites to when it comes back, without rewriting again',
    async (host, path) => {
      vi.stubEnv('SALON_PLATFORM_DOMAIN', 'localhost');
      try {
        const resolve = resolving({ kind: 'salon', salon: alpha });
        const first = await routeRequest(
          new NextRequest(`http://${host}${path}`, { headers: { host } }),
          resolve,
          vi.fn(),
          vi.fn(),
        );
        expect(isRewrite(first)).toBe(true);
        const target = new URL(getRewrittenUrl(first)!);

        const unavailable = resolving({ kind: 'unavailable' });
        const again = await routeRequest(
          new NextRequest(target, { headers: { host, [SALON_HEADER]: encodeSalon(beta) } }),
          unavailable,
          vi.fn(),
          vi.fn(),
        );

        expect(isRewrite(again)).toBe(false);
        expect(again.headers.has('location')).toBe(false);
        expect(again.status).toBe(200);
        expect(handedOn(again)).toBeNull();
        expect(again.headers.get('cache-control')).toContain('no-store');
        expect(unavailable).not.toHaveBeenCalled();
      } finally {
        vi.unstubAllEnvs();
      }
    },
  );
});

describe('the proxy runs on', () => {
  it.each(['/', '/workspace', '/salon-site', '/anything/else'])('every page: %s', (url) => {
    expect(unstable_doesMiddlewareMatch({ config, url })).toBe(true);
  });

  it.each(['/_next/static/chunks/x.js', '/_next/image', '/favicon.ico', '/icon.svg'])(
    'but not the static files that are the same on every hostname: %s',
    (url) => {
      expect(unstable_doesMiddlewareMatch({ config, url })).toBe(false);
    },
  );
});

describe('workspace entry decided before anything renders (CR-013)', () => {
  const salonSite = () => resolving({ kind: 'salon', salon: alpha });
  const deciding = (kind: 'signed-out' | 'signed-in' | 'pending') =>
    vi.fn(async () =>
      kind === 'signed-in' ? { kind, principal: { kind: 'staff', permissions: [] } as never } : { kind },
    );

  it.each(['/workspace', '/workspace/account', '/workspace/staff', '/workspace/audit'])(
    'redirects %s without any staff credential to sign-in, asking the API nothing',
    async (path) => {
      const decide = deciding('signed-in');
      const response = await routeRequest(
        request(`${path}?next=https://outside.test`, { cookie: 'salon_customer_access=other; unrelated=1' }),
        salonSite(),
        decide,
      );
      expect(response.status).toBe(307);
      expect(response.headers.get('location')).toBe('http://alpha.localhost:3100/workspace/sign-in');
      expect(response.headers.get('cache-control')).toContain('no-store');
      expect(decide).not.toHaveBeenCalled();
    },
  );

  it('enters /workspace at the account when a credential is present; the account decides on it', async () => {
    const decide = deciding('signed-out');
    const response = await routeRequest(
      request('/workspace', { cookie: 'salon_staff_refresh=family' }),
      salonSite(),
      decide,
    );
    expect(response.status).toBe(307);
    expect(response.headers.get('location')).toBe('http://alpha.localhost:3100/workspace/account');
    expect(decide).not.toHaveBeenCalled();
  });

  it.each(['/workspace/account', '/workspace/staff', '/workspace/audit'])(
    'redirects %s with an unusable session to sign-in',
    async (path) => {
      const decide = deciding('signed-out');
      const response = await routeRequest(
        request(path, {
          cookie: 'salon_staff_access=revoked; salon_staff_refresh=spent; salon_staff_expires=2026-01-01T00:05:00Z',
        }),
        salonSite(),
        decide,
      );
      expect(response.status).toBe(307);
      expect(response.headers.get('location')).toBe('http://alpha.localhost:3100/workspace/sign-in');
      expect(decide).toHaveBeenCalledExactlyOnceWith(expect.anything(), {
        access: 'revoked',
        refresh: 'spent',
        expiry: '2026-01-01T00:05:00Z',
      });
    },
  );

  // BUG-F02-011: the revoked family's credentials do not survive the redirect.
  it.each([
    ['true', ''],
    ['false', ' Secure;'],
  ])('clears every staff credential cookie, host-only, on an unusable session (local HTTP %s)', async (localHttp) => {
    vi.stubEnv('SALON_WEB_LOCAL_HTTP', localHttp);
    try {
      const response = await routeRequest(
        request('/workspace/account', {
          cookie:
            'salon_staff_access=revoked; salon_staff_refresh=spent; salon_staff_expires=2026-01-01T00:05:00Z; salon_staff_session=s1',
        }),
        salonSite(),
        deciding('signed-out'),
      );
      expect(response.status).toBe(307);
      const cleared = response.headers.getSetCookie();
      for (const name of ['access', 'refresh', 'expires', 'session', 'challenge', 'challenge_id']) {
        const cookie = cleared.find((value) => value.startsWith(`salon_staff_${name}=;`));
        expect(cookie, name).toBeDefined();
        expect(cookie).toMatch(/Max-Age=0/i);
        expect(cookie).toMatch(/Path=\//);
        expect(cookie).toMatch(/HttpOnly/i);
        expect(cookie).toMatch(/SameSite=lax/i);
        expect(cookie).not.toMatch(/Domain=/i);
        expect(/;\s*Secure/i.test(cookie!)).toBe(localHttp === 'false');
      }
      expect(cleared.some((value) => value.startsWith('salon_customer'))).toBe(false);
    } finally {
      vi.unstubAllEnvs();
    }
  });

  it('sets no cookie when there was no credential, or the session is usable or only renewable', async () => {
    expect(
      (await routeRequest(request('/workspace/account'), salonSite(), deciding('signed-out'))).headers.getSetCookie(),
    ).toEqual([]);
    for (const kind of ['signed-in', 'pending'] as const) {
      expect(
        (
          await routeRequest(
            request('/workspace/account', { cookie: 'salon_staff_refresh=family' }),
            salonSite(),
            deciding(kind),
          )
        ).headers.getSetCookie(),
      ).toEqual([]);
    }
    expect(
      (
        await routeRequest(
          request('/workspace', { cookie: 'salon_staff_refresh=family' }),
          salonSite(),
          deciding('signed-out'),
        )
      ).headers.getSetCookie(),
    ).toEqual([]);
  });

  it.each(['signed-in', 'pending'] as const)(
    "lets a %s member reach the salon's page, with the salon handed on",
    async (kind) => {
      const response = await routeRequest(
        request('/workspace/account', { cookie: 'salon_staff_access=private' }),
        salonSite(),
        deciding(kind),
      );
      expect(response.status).toBe(200);
      expect(isRewrite(response)).toBe(false);
      expect(handedOn(response)).toEqual(alpha);
    },
  );

  describe('Settings (F04-S01)', () => {
    const signedInWith = (permissions: string[]) =>
      vi.fn(async () => ({ kind: 'signed-in' as const, principal: { kind: 'staff', permissions } as never }));

    it('sends settings staff to the first tab with a real redirect', async () => {
      const response = await routeRequest(
        request('/workspace/settings', { cookie: 'salon_staff_access=private' }),
        salonSite(),
        signedInWith(['settings.read', 'settings.manage']),
      );
      expect(response.status).toBe(307);
      expect(response.headers.get('location')).toBe('http://alpha.localhost:3100/workspace/settings/profile');
    });

    it('lets a member without settings.read reach the page, which shows its no-access state', async () => {
      const response = await routeRequest(
        request('/workspace/settings', { cookie: 'salon_staff_access=private' }),
        salonSite(),
        signedInWith(['calendar.read']),
      );
      expect(response.status).toBe(200);
      expect(handedOn(response)).toEqual(alpha);
    });

    it.each(['/workspace/settings', '/workspace/settings/profile'])(
      'protects %s like every other workspace page',
      async (path) => {
        const response = await routeRequest(request(path), salonSite(), deciding('signed-in'));
        expect(response.status).toBe(307);
        expect(response.headers.get('location')).toBe('http://alpha.localhost:3100/workspace/sign-in');
      },
    );
  });

  it("never decides entry for sign-in itself or for other hostnames' answers", async () => {
    const decide = deciding('signed-out');
    expect((await routeRequest(request('/workspace/sign-in'), salonSite(), decide)).status).toBe(200);
    expect((await routeRequest(request('/workspace/account'), resolving({ kind: 'suspended' }), decide)).status).toBe(
      503,
    );
    expect(decide).not.toHaveBeenCalled();
  });
});
