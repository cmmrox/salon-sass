// @vitest-environment node
import type { Salon } from '@/lib/api/salon';
import { getRewrittenUrl, isRewrite, unstable_doesMiddlewareMatch } from 'next/experimental/testing/server';
import { NextRequest } from 'next/server';
import { describe, expect, it, vi } from 'vitest';

import { config } from '@/proxy';

import { SALON_SHELL_ROUTE, routeRequest } from './route-request';
import { SALON_HEADER, decodeSalon, encodeSalon } from './salon-header';
import type { Site } from './site';

const alpha: Salon = { slug: 'alpha', name: 'Alpha Studio', brandColour: '#D6086A', timeZone: 'Asia/Colombo' };
const beta: Salon = { slug: 'beta', name: 'Beta Hair, Beauty & Wellness Lounge of Colombo Seven', brandColour: '#1F2937', timeZone: 'Asia/Colombo' };

function request(path: string, headers: Record<string, string> = {}): NextRequest {
  return new NextRequest(`http://alpha.localhost:3100${path}`, { headers: { host: 'alpha.localhost:3100', ...headers } });
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
  it("rewrites / on an active salon's hostname to its shell, with the salon handed on and nothing stored", async () => {
    const resolve = resolving({ kind: 'salon', salon: alpha });

    const response = await routeRequest(request('/'), resolve);

    expect(resolve).toHaveBeenCalledExactlyOnceWith('alpha.localhost:3100');
    expect(isRewrite(response)).toBe(true);
    expect(new URL(getRewrittenUrl(response)!).pathname).toBe(SALON_SHELL_ROUTE);
    expect(new URL(getRewrittenUrl(response)!).host).toBe('alpha.localhost:3100');
    expect(response.status).toBe(200);
    expect(handedOn(response)).toEqual(alpha);
    expect(response.headers.get('cache-control')).toContain('no-store');
  });

  it('reads the salon from Host only: the query, cookies and other headers naming another salon change nothing', async () => {
    const resolve = resolving({ kind: 'salon', salon: alpha });

    const response = await routeRequest(
      request(`/?salon=beta&tenantId=b2b2b2b2-0000-4000-8000-000000000002`, {
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
    expect(new URL(getRewrittenUrl(response)!).search).toBe('');
  });

  it('a salon header the client sends never reaches a page', async () => {
    const forged = { [SALON_HEADER]: encodeSalon(beta) };

    const onSalon = await routeRequest(request('/', forged), resolving({ kind: 'salon', salon: alpha }));
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
  ] as const)('answers %s itself, as complete HTML with its status and nothing stored', async (_, site, status, testId) => {
    for (const path of ['/', '/anything/else']) {
      const response = await routeRequest(request(path), resolving(site));

      expect(isRewrite(response)).toBe(false);
      expect(response.status).toBe(status);
      expect(response.headers.get('content-type')).toBe('text/html; charset=utf-8');
      expect(response.headers.get('cache-control')).toContain('no-store');
      const html = await response.text();
      expect(html).toContain(`data-testid="${testId}"`);
      expect(html).not.toContain(alpha.name);
    }
  });

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
