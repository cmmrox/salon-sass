import type { Salon } from '@/lib/api/salon';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { SalonLookup } from '@/lib/api/salon';

import { contextFromTraceparent, runInRequest } from '@/lib/observability/request-context';

import { resolveSite, type FindSalon } from './site';

const alpha: Salon = { slug: 'alpha', name: 'Alpha Studio', brandColour: '#D6086A', timeZone: 'Asia/Colombo' };

function finder(answer: SalonLookup) {
  return vi.fn<FindSalon>(async () => answer);
}

describe('resolveSite', () => {
  beforeEach(() => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('asks the API about the slug, by its canonical hostname, and shows the salon it found', async () => {
    const find = finder({ kind: 'found', salon: alpha });

    await expect(resolveSite('Alpha.LocalHost:3100', find, 'localhost')).resolves.toEqual({ kind: 'salon', salon: alpha });
    expect(find).toHaveBeenCalledExactlyOnceWith('alpha', 'alpha.localhost');
  });

  it.each([
    ['not found', { kind: 'not-found' }, { kind: 'not-found' }],
    ['suspended', { kind: 'suspended' }, { kind: 'suspended' }],
    ['unavailable', { kind: 'unavailable' }, { kind: 'unavailable' }],
  ] as const)('passes on what the API said: %s', async (_, lookup, site) => {
    await expect(resolveSite('gamma.localhost:3100', finder(lookup), 'localhost')).resolves.toEqual(site);
  });

  it.each(['localhost', 'localhost:3100', 'LOCALHOST'])('the platform domain itself is the platform, without asking the API: %j', async (host) => {
    const find = finder({ kind: 'found', salon: alpha });

    await expect(resolveSite(host, find, 'localhost')).resolves.toEqual({ kind: 'platform' });
    expect(find).not.toHaveBeenCalled();
  });

  it.each([
    'alpha.beta.localhost',
    'alpha.localhost.example.com',
    'alpha.localhost.',
    '127.0.0.1:3100',
    '[::1]:3100',
    'example.com',
    '',
    // One label, but not in the form of a slug: the database refuses any such slug.
    'x.localhost',
    'al_pha.localhost',
    `${'a'.repeat(31)}.localhost`,
  ])('a hostname that cannot name a salon is not found, without asking the API: %j', async (host) => {
    const find = finder({ kind: 'found', salon: alpha });

    await expect(resolveSite(host, find, 'localhost')).resolves.toEqual({ kind: 'not-found' });
    expect(find).not.toHaveBeenCalled();
  });

  it('no Host header is not found, without asking the API', async () => {
    const find = finder({ kind: 'found', salon: alpha });

    await expect(resolveSite(null, find, 'localhost')).resolves.toEqual({ kind: 'not-found' });
    expect(find).not.toHaveBeenCalled();
  });

  it.each([undefined, '', 'localhost:3100'])(
    'is unavailable, and says why, when the platform domain is not usable: %j',
    async (domain) => {
      const find = finder({ kind: 'found', salon: alpha });

      await expect(resolveSite('alpha.localhost', find, domain)).resolves.toEqual({ kind: 'unavailable' });
      expect(find).not.toHaveBeenCalled();
      expect(console.warn).toHaveBeenCalledWith(expect.stringContaining('SALON_PLATFORM_DOMAIN'));
    },
  );

  it.each([
    ['an active salon', { kind: 'found', salon: alpha }, 'alpha'],
    ['a suspended salon', { kind: 'suspended' }, 'alpha'],
    ['no salon', { kind: 'not-found' }, undefined],
    ['an API that cannot say', { kind: 'unavailable' }, undefined],
  ] as const)("names the salon on the request's log line only once the API says it is one: %s", async (_, lookup, slug) => {
    const request = contextFromTraceparent(undefined);

    await runInRequest(request, () => resolveSite('alpha.localhost:3100', finder(lookup), 'localhost'));

    expect(request.tenantSlug).toBe(slug);
  });
});
