import { createApiClient, type components } from '@salon/api-contracts/branding';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { Salon } from '../types';
import { findSalon } from './salon';

type Problem = components['schemas']['Problem'];

const alpha: Salon = { slug: 'alpha', name: 'Alpha Studio', brandColour: '#D6086A', timeZone: 'Asia/Colombo' };

function clientAnswering(status: number, body: unknown, contentType = 'application/json') {
  const requests: Request[] = [];
  const client = createApiClient({
    baseUrl: 'http://api.test',
    fetch: async (input, init) => {
      requests.push(new Request(input, init));
      return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': contentType } });
    },
  });
  return { client, requests };
}

function problem(status: number, code: Problem['code']): Problem {
  return { title: 'x', status, code, instance: '/api/v1/salon', traceId: '4bf92f3577b34da6a3ce929d0e0e4736' };
}

describe('findSalon', () => {
  beforeEach(() => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllEnvs();
  });

  it('returns the brand of the salon that was asked for', async () => {
    // The API's answer as it is from F01-S04: the brand, and the salon's time zone.
    const { client, requests } = clientAnswering(200, { ...alpha, timeZone: 'Asia/Colombo' });

    await expect(findSalon('alpha', 'alpha.localhost', client)).resolves.toEqual({ kind: 'found', salon: alpha });
    expect(requests.map((request) => request.url)).toEqual(['http://api.test/api/v1/salon']);
  });

  it('carries the API timezone but excludes unrelated fields', async () => {
    const { client } = clientAnswering(200, { ...alpha, timeZone: 'Europe/London', secret: 'x' });

    await expect(findSalon('alpha', 'alpha.localhost', client)).resolves.toEqual({
      kind: 'found',
      salon: { ...alpha, timeZone: 'Europe/London' },
    });
  });

  it('never returns the brand of another salon than the one asked for', async () => {
    const { client } = clientAnswering(200, {
      slug: 'beta',
      name: 'Beta',
      brandColour: '#1F2937',
      timeZone: 'Asia/Colombo',
    });

    await expect(findSalon('alpha', 'alpha.localhost', client)).resolves.toEqual({ kind: 'unavailable' });
  });

  it('is not found when the API says not_found', async () => {
    const { client } = clientAnswering(404, problem(404, 'not_found'), 'application/problem+json');

    await expect(findSalon('nosuch', 'nosuch.localhost', client)).resolves.toEqual({ kind: 'not-found' });
  });

  it('is suspended when the API says salon_suspended', async () => {
    const { client } = clientAnswering(403, problem(403, 'salon_suspended'), 'application/problem+json');

    await expect(findSalon('gamma', 'gamma.localhost', client)).resolves.toEqual({ kind: 'suspended' });
  });

  it.each([
    ['the database is down', 503, problem(503, 'service_unavailable')],
    ['an internal error', 500, problem(500, 'internal_error')],
    ["a 404 that is not the contract's", 404, { message: 'no' }],
    ['a 403 with another code', 403, problem(403, 'request_rejected')],
  ])('is unavailable, not "not found", when the answer is %s', async (_, status, body) => {
    const { client } = clientAnswering(status, body, 'application/problem+json');

    await expect(findSalon('alpha', 'alpha.localhost', client)).resolves.toEqual({ kind: 'unavailable' });
  });

  it.each([
    ['no name', { slug: 'alpha', brandColour: '#D6086A', timeZone: 'Asia/Colombo' }],
    ['an empty name', { slug: 'alpha', name: ' ', brandColour: '#D6086A', timeZone: 'Asia/Colombo' }],
    ['not an object', 'alpha'],
    ['no time zone', { slug: 'alpha', name: 'Alpha', brandColour: '#D6086A' }],
  ])('is unavailable when the brand has %s', async (_, body) => {
    const { client } = clientAnswering(200, body);

    await expect(findSalon('alpha', 'alpha.localhost', client)).resolves.toEqual({ kind: 'unavailable' });
  });

  // CR-013 AC-10, BUG-F02-010: a salon without a valid colour is still the salon, in the default accent.
  it.each([
    ['a colour that is not #RRGGBB', 'not-a-colour'],
    ['a style-sheet injection', '#fff;}body{display:none'],
    ['a short colour', '#fff'],
    ['no colour', undefined],
    ['a colour that is not text', 0xd6086a],
  ])('gives a salon with %s the default brand colour', async (_, brandColour) => {
    const { client } = clientAnswering(200, {
      slug: 'alpha',
      name: 'Alpha Studio',
      timeZone: 'Asia/Colombo',
      ...(brandColour === undefined ? {} : { brandColour }),
    });

    await expect(findSalon('alpha', 'alpha.localhost', client)).resolves.toEqual({
      kind: 'found',
      salon: { slug: 'alpha', name: 'Alpha Studio', brandColour: '#D6086A', timeZone: 'Asia/Colombo' },
    });
  });

  it('is unavailable when the API cannot be reached', async () => {
    const client = createApiClient({
      baseUrl: 'http://api.test',
      fetch: async () => {
        throw new TypeError('fetch failed');
      },
    });

    await expect(findSalon('alpha', 'alpha.localhost', client)).resolves.toEqual({ kind: 'unavailable' });
  });

  it('gives up when the API does not answer in time', async () => {
    vi.useFakeTimers();
    try {
      const client = createApiClient({
        baseUrl: 'http://api.test',
        fetch: (input, init) =>
          new Promise<Response>((_, reject) => {
            const signal = new Request(input, init).signal;
            signal.addEventListener('abort', () => reject(signal.reason));
          }),
      });

      const pending = findSalon('alpha', 'alpha.localhost', client);
      await vi.advanceTimersByTimeAsync(3000);

      await expect(pending).resolves.toEqual({ kind: 'unavailable' });
    } finally {
      vi.useRealTimers();
    }
  });

  it('is unavailable, and says why on the server, when the address of the API is not set', async () => {
    vi.stubEnv('SALON_API_BASE_URL', '');

    await expect(findSalon('alpha', 'alpha.localhost')).resolves.toEqual({ kind: 'unavailable' });
    expect(console.warn).toHaveBeenCalledWith(expect.stringContaining('SALON_API_BASE_URL'));
  });

  it('asks the API every time: nothing is kept between calls', async () => {
    let answer: unknown = alpha;
    let status = 200;
    const client = createApiClient({
      baseUrl: 'http://api.test',
      fetch: async () =>
        new Response(JSON.stringify(answer), { status, headers: { 'Content-Type': 'application/json' } }),
    });

    await expect(findSalon('alpha', 'alpha.localhost', client)).resolves.toEqual({ kind: 'found', salon: alpha });
    answer = problem(403, 'salon_suspended');
    status = 403;
    await expect(findSalon('alpha', 'alpha.localhost', client)).resolves.toEqual({ kind: 'suspended' });
  });
});
