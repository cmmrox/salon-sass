// @vitest-environment node
import { NextRequest } from 'next/server';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { config, proxy } from './proxy';

/** The matcher as the framework applies it: a path the proxy runs for. */
const runsFor = (path: string) => new RegExp(`^${config.matcher[0]}$`).test(path);

beforeEach(() => {
  vi.stubEnv('SALON_PLATFORM_DOMAIN', 'localhost');
});
afterEach(() => {
  vi.unstubAllEnvs();
});

// Web M6: the platform's own static files cost no salon lookup; everything about a salon still passes the proxy.
describe('what the proxy runs for', () => {
  it.each([
    '/images/staff-sign-in-interior.webp',
    '/icons/feather/menu.svg',
    '/brand/neutral-cover.svg',
    '/fonts/poppins/Poppins-Regular.ttf',
    '/_next/static/chunk.js',
    '/healthz',
  ])('skips the platform’s static file %s', (path) => {
    expect(runsFor(path)).toBe(false);
  });

  it.each([
    '/workspace/bookings',
    '/identity-web/staff/bookings',
    '/brand-images/3f1c2b9a-0000-4000-8000-000000000001',
    '/service-photos/3f1c2b9a-0000-4000-8000-000000000001',
    '/team-photos/3f1c2b9a-0000-4000-8000-000000000001',
    '/console/salons',
    '/',
  ])('runs for %s, a salon’s or the console’s', (path) => {
    expect(runsFor(path)).toBe(true);
  });
});

// Web M7: every answer carries the security headers and a policy with a nonce for this request alone.
describe('the security headers', () => {
  const ask = (headers: Record<string, string> = {}) =>
    proxy(new NextRequest('http://nowhere.example/', { headers: { host: 'nowhere.example', ...headers } }));

  it('sends a content security policy whose scripts need the request’s nonce, and the other security headers', async () => {
    const response = await ask();
    const policy = response.headers.get('content-security-policy') ?? '';
    const scripts = policy.split('; ').find((directive) => directive.startsWith('script-src')) ?? '';
    expect(scripts).toMatch(/^script-src 'self' 'nonce-[A-Za-z0-9+/=]{24}' 'strict-dynamic'$/);
    expect(scripts).not.toContain('unsafe-inline');
    expect(policy).toContain("frame-ancestors 'none'");
    expect(policy).toContain("object-src 'none'");
    expect(response.headers.get('x-content-type-options')).toBe('nosniff');
    expect(response.headers.get('referrer-policy')).toBe('same-origin');
    expect(response.headers.get('x-frame-options')).toBe('DENY');
  });

  it('makes a new nonce for every request', async () => {
    const nonce = async () => /'nonce-([^']+)'/.exec((await ask()).headers.get('content-security-policy') ?? '')?.[1];
    expect(await nonce()).not.toBe(await nonce());
  });
});
