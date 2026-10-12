import { describe, expect, it } from 'vitest';

import { createApiClient, type Meta, type Problem } from '../src/index';

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
    const problem: Problem = { title: 'Not Found', status: 404, code: 'not_found' };
    const { fetch } = respondingWith(404, problem, 'application/problem+json');
    const client = createApiClient({ baseUrl: 'http://api.test:8090', fetch });

    const { data, error, response } = await client.GET('/api/v1/meta');

    expect(response.status).toBe(404);
    expect(data).toBeUndefined();
    expect(error?.code).toBe('not_found');
  });

  it('sends the headers it was given with every request', async () => {
    const { fetch, requests } = respondingWith(200, {}, 'application/json');
    const client = createApiClient({ baseUrl: 'http://api.test:8090', fetch, headers: { 'X-Test': 'yes' } });

    await client.GET('/api/v1/meta');

    expect(requests[0]?.headers.get('X-Test')).toBe('yes');
  });
});
