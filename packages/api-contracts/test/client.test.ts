import { describe, expect, it } from 'vitest';

import { createApiClient, type Meta, type Problem, type Salon } from '../src/index';

function respondingWith(status: number, body: unknown, contentType: string) {
  const requests: Request[] = [];
  const fetch = async (input: RequestInfo | URL, init?: RequestInit) => {
    requests.push(new Request(input, init));
    return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': contentType } });
  };
  return { fetch, requests };
}

describe('createApiClient', () => {
  it('requests meta from the base URL and returns the typed body', async () => {
    const meta: Meta = {
      service: 'salon-api',
      version: '1.2.3',
      commit: '0123456789abcdef0123456789abcdef01234567',
      time: '2026-10-12T02:17:00.123Z',
    };
    const { fetch, requests } = respondingWith(200, meta, 'application/json');
    const client = createApiClient({ baseUrl: 'http://api.test:8090', fetch });

    const { data, error, response } = await client.GET('/api/v1/meta');

    expect(requests).toHaveLength(1);
    expect(requests[0]?.method).toBe('GET');
    expect(requests[0]?.url).toBe('http://api.test:8090/api/v1/meta');
    expect(response.status).toBe(200);
    expect(error).toBeUndefined();
    expect(data).toEqual(meta);
  });

  it('returns an error response as problem details, with its code', async () => {
    const problem: Problem = { title: 'Not Found', status: 404, code: 'not_found', traceId: '4bf92f3577b34da6a3ce929d0e0e4736' };
    const { fetch } = respondingWith(404, problem, 'application/problem+json');
    const client = createApiClient({ baseUrl: 'http://api.test:8090', fetch });

    const { data, error, response } = await client.GET('/api/v1/meta');

    expect(response.status).toBe(404);
    expect(data).toBeUndefined();
    expect(error?.code).toBe('not_found');
    expect(error?.traceId).toBe('4bf92f3577b34da6a3ce929d0e0e4736');
  });

  it('requests the salon of the hostname it was given, with no parameter naming one', async () => {
    const salon: Salon = { slug: 'alpha', name: 'Alpha Studio', brandColour: '#D6086A', timeZone: 'Asia/Colombo' };
    const { fetch, requests } = respondingWith(200, salon, 'application/json');
    const client = createApiClient({ baseUrl: 'http://alpha.localhost:8090', fetch });

    const { data, error } = await client.GET('/api/v1/salon');

    expect(requests[0]?.url).toBe('http://alpha.localhost:8090/api/v1/salon');
    expect(error).toBeUndefined();
    expect(data).toEqual(salon);
  });

  it('returns not found for a hostname that names no salon, with its code', async () => {
    const problem: Problem = {
      title: 'Not Found',
      status: 404,
      code: 'not_found',
      instance: '/api/v1/salon',
      traceId: '4bf92f3577b34da6a3ce929d0e0e4736',
    };
    const { fetch } = respondingWith(404, problem, 'application/problem+json');
    const client = createApiClient({ baseUrl: 'http://localhost:8090', fetch });

    const { data, error, response } = await client.GET('/api/v1/salon');

    expect(response.status).toBe(404);
    expect(data).toBeUndefined();
    expect(error?.code).toBe('not_found');
  });

  it('returns forbidden for a suspended salon, with its code and nothing of its brand', async () => {
    // The contract lists every code, so a code it does not list does not compile.
    const code: Problem['code'] = 'salon_suspended';
    const problem: Problem = { title: 'Forbidden', status: 403, code, instance: '/api/v1/salon', traceId: '4bf92f3577b34da6a3ce929d0e0e4736' };
    const { fetch } = respondingWith(403, problem, 'application/problem+json');
    const client = createApiClient({ baseUrl: 'http://gamma.localhost:8090', fetch });

    const { data, error, response } = await client.GET('/api/v1/salon');

    expect(response.status).toBe(403);
    expect(data).toBeUndefined();
    expect(error?.code).toBe('salon_suspended');
    expect(error).not.toHaveProperty('name');
    expect(error).not.toHaveProperty('brandColour');
  });

  it('sends the headers it was given with every request', async () => {
    const { fetch, requests } = respondingWith(200, {}, 'application/json');
    const client = createApiClient({ baseUrl: 'http://api.test:8090', fetch, headers: { 'X-Test': 'yes' } });

    await client.GET('/api/v1/meta');

    expect(requests[0]?.headers.get('X-Test')).toBe('yes');
  });
});
