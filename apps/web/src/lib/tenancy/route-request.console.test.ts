// @vitest-environment node
import { getRewrittenUrl, isRewrite } from 'next/experimental/testing/server';
import { NextRequest } from 'next/server';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { OperatorEntry } from '@/lib/operator/entry';
import { routeRequest } from './route-request';
import type { Site } from './site';

beforeEach(() => { vi.stubEnv('SALON_PLATFORM_DOMAIN', 'localhost'); vi.stubEnv('SALON_WEB_LOCAL_HTTP', 'true'); });
afterEach(() => vi.unstubAllEnvs());

const operatorCookies = 'salon_operator_access=a; salon_operator_refresh=r; salon_operator_expires=2026-01-01T00:05:00Z';

function request(host: string, path: string, cookie = ''): NextRequest {
  return new NextRequest(`http://${host}${path}`, { headers: { host, ...(cookie ? { cookie } : {}) } });
}
const resolving = () => vi.fn<(host: string | null) => Promise<Site>>(async () => ({ kind: 'salon', salon: { slug: 'alpha', name: 'Alpha', brandColour: '#D6086A', timeZone: 'Asia/Colombo' } }));
const deciding = (entry: OperatorEntry) => vi.fn(async () => entry);
const staff = vi.fn(async () => ({ kind: 'signed-out' as const }));

describe('the console hostname in the proxy', () => {
  it.each(['/', '/console'])('enters %s at the salon directory', async (path) => {
    const resolve = resolving();
    const response = await routeRequest(request('console.localhost:3100', path), resolve, staff, deciding({ kind: 'pending' }));
    expect(response.status).toBe(307);
    expect(new URL(response.headers.get('location')!).pathname).toBe('/console/salons');
    expect(resolve).not.toHaveBeenCalled();
  });

  it.each(['/console/salons', '/console/plans', '/console/salons/3f1c2b9a-0000-4000-8000-000000000001'])('sends %s to sign-in without an operator session, before any lookup', async (path) => {
    const decide = deciding({ kind: 'signed-in', principal: { id: 'x', kind: 'operator', role: 'OPERATOR_ADMIN', permissions: [], mfaEnabled: true } });
    const response = await routeRequest(request('console.localhost:3100', path, 'salon_staff_access=staff-cookie'), resolving(), staff, decide);
    expect(response.status).toBe(307);
    expect(new URL(response.headers.get('location')!).pathname).toBe('/console/sign-in');
    expect(decide).not.toHaveBeenCalled();
  });

  it('clears the operator cookies of a spent session on the way to sign-in', async () => {
    const response = await routeRequest(request('console.localhost:3100', '/console/salons', operatorCookies), resolving(), staff, deciding({ kind: 'signed-out' }));
    expect(new URL(response.headers.get('location')!).pathname).toBe('/console/sign-in');
    expect(response.cookies.get('salon_operator_refresh')?.value).toBe('');
  });

  it('lets a confirmed or renewable session, sign-in and the BFF through, never cached', async () => {
    for (const path of ['/console/salons', '/console/sign-in', '/operator-web/principal']) {
      const response = await routeRequest(request('console.localhost:3100', path, operatorCookies), resolving(), staff, deciding({ kind: 'pending' }));
      expect(response.status, path).toBe(200);
      expect(response.headers.get('cache-control'), path).toContain('no-store');
    }
  });

  it('serves no salon or workspace path on the console hostname', async () => {
    const resolve = resolving();
    for (const path of ['/workspace', '/workspace/sign-in', '/identity-web/staff/principal', '/salon-site']) {
      const response = await routeRequest(request('console.localhost:3100', path), resolve, staff, deciding({ kind: 'pending' }));
      expect(isRewrite(response), path).toBe(true);
      expect(new URL(getRewrittenUrl(response)!).pathname).toBe('/salon-site/nowhere');
    }
    expect(resolve).not.toHaveBeenCalled();
  });

  it.each(['alpha.localhost:3100', 'localhost:3100', 'admin.localhost:3100', 'consolex.localhost:3100', 'a.console.localhost:3100', 'console.localhost.example.test'])('answers console paths on %s as not found, before any lookup', async (host) => {
    const resolve = resolving();
    const decide = deciding({ kind: 'pending' });
    for (const path of ['/console', '/console/salons', '/console/sign-in', '/operator-web/principal', '/operator-web/sign-in']) {
      const response = await routeRequest(request(host, path, operatorCookies), resolve, staff, decide);
      expect(isRewrite(response), `${host}${path}`).toBe(true);
      expect(new URL(getRewrittenUrl(response)!).pathname).toBe('/salon-site/nowhere');
    }
    expect(resolve).not.toHaveBeenCalled();
    expect(decide).not.toHaveBeenCalled();
  });

  it('a forged forwarded host never makes a salon hostname the console', async () => {
    const req = new NextRequest('http://alpha.localhost:3100/console/salons', { headers: { host: 'alpha.localhost:3100', 'x-forwarded-host': 'console.localhost:3100', forwarded: 'host=console.localhost:3100', cookie: operatorCookies } });
    const response = await routeRequest(req, resolving(), staff, deciding({ kind: 'pending' }));
    expect(new URL(getRewrittenUrl(response)!).pathname).toBe('/salon-site/nowhere');
  });

  it.each(['/console;x/salons', '/console/%73alons', '/operator-web/principal;a', '/x//y'])('refuses the non-canonical path %s on every host', async (path) => {
    for (const host of ['console.localhost:3100', 'alpha.localhost:3100']) {
      const response = await routeRequest(request(host, path, operatorCookies), resolving(), staff, deciding({ kind: 'pending' }));
      expect(isRewrite(response)).toBe(true);
      expect(new URL(getRewrittenUrl(response)!).pathname).toBe('/salon-site/nowhere');
    }
  });
});

describe('the Owner export page and its BFF (F03-S03)', () => {
  it.each(['/exports', '/platform-web/export-info'])('%s is the bare platform host\'s alone, never stored', async (path) => {
    const platform = await routeRequest(request('localhost:3100', path), vi.fn(async () => ({ kind: 'platform' as const })), staff, deciding({ kind: 'pending' }));
    expect(isRewrite(platform)).toBe(false);
    expect(platform.headers.get('cache-control')).toContain('no-store');
    const salon = await routeRequest(request('alpha.localhost:3100', path), resolving(), staff, deciding({ kind: 'pending' }));
    expect(isRewrite(salon)).toBe(true);
    expect(new URL(getRewrittenUrl(salon)!).pathname).toBe('/salon-site/nowhere');
    const console = await routeRequest(request('console.localhost:3100', path), resolving(), staff, deciding({ kind: 'pending' }));
    expect(isRewrite(console)).toBe(true);
  });
});
