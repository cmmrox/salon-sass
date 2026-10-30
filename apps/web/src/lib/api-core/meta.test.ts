import { createApiClient, type Meta } from '@salon/api-contracts';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { createServerApiClient } from './client';
import { getMeta } from './meta';

const meta: Meta = {
  service: 'salon-api',
  version: '1.2.3',
  commit: '0123456789abcdef0123456789abcdef01234567',
  time: '2026-10-12T02:17:00.123Z',
};

function clientAnswering(fetch: typeof globalThis.fetch) {
  return createApiClient({ baseUrl: 'http://api.test', fetch });
}

function json(status: number, body: unknown, contentType = 'application/json') {
  return async () => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': contentType } });
}

describe('getMeta', () => {
  beforeEach(() => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllEnvs();
  });

  it('returns what the API said about itself', async () => {
    const requested: string[] = [];
    const client = clientAnswering(async (input) => {
      requested.push(new Request(input).url);
      return new Response(JSON.stringify(meta), { status: 200, headers: { 'Content-Type': 'application/json' } });
    });

    await expect(getMeta(client)).resolves.toEqual({ ok: true, meta });
    expect(requested).toEqual(['http://api.test/api/v1/meta']);
  });

  it('is unavailable when the API cannot be reached', async () => {
    const client = clientAnswering(async () => {
      throw new TypeError('fetch failed');
    });

    await expect(getMeta(client)).resolves.toEqual({ ok: false });
  });

  it('is unavailable when the API answers with an error', async () => {
    const problem = { title: 'Service Unavailable', status: 503, code: 'service_unavailable' };
    const client = clientAnswering(json(503, problem, 'application/problem+json'));

    await expect(getMeta(client)).resolves.toEqual({ ok: false });
  });

  it('is unavailable when the answer is not JSON', async () => {
    const client = clientAnswering(
      async () => new Response('<html>not the API</html>', { status: 200, headers: { 'Content-Type': 'text/html' } }),
    );

    await expect(getMeta(client)).resolves.toEqual({ ok: false });
  });

  it('is unavailable when the answer is JSON of another shape', async () => {
    const client = clientAnswering(json(200, { hello: 'world' }));

    await expect(getMeta(client)).resolves.toEqual({ ok: false });
  });

  it('gives up when the API does not answer in time', async () => {
    vi.useFakeTimers();
    try {
      const client = clientAnswering(
        (input) =>
          new Promise<Response>((_, reject) => {
            const signal = new Request(input).signal;
            signal.addEventListener('abort', () => reject(signal.reason));
          }),
      );

      const pending = getMeta(client);
      await vi.advanceTimersByTimeAsync(60_000);

      await expect(pending).resolves.toEqual({ ok: false });
    } finally {
      vi.useRealTimers();
    }
  });

  it('is unavailable, and says why on the server, when the address of the API is not set', async () => {
    vi.stubEnv('SALON_API_BASE_URL', '');

    await expect(getMeta()).resolves.toEqual({ ok: false });
    expect(console.warn).toHaveBeenCalledWith(expect.stringContaining('SALON_API_BASE_URL'));
  });
});

describe('createServerApiClient', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it('reads the address of the API from the environment and ignores a trailing slash', async () => {
    vi.stubEnv('SALON_API_BASE_URL', 'http://configured.test:8090/');
    const requested: string[] = [];
    vi.stubGlobal('fetch', async (input: RequestInfo | URL) => {
      requested.push(new Request(input).url);
      return new Response(JSON.stringify(meta), { status: 200, headers: { 'Content-Type': 'application/json' } });
    });
    try {
      await createServerApiClient()?.GET('/api/v1/meta');
    } finally {
      vi.unstubAllGlobals();
    }

    expect(requested).toEqual(['http://configured.test:8090/api/v1/meta']);
  });

  it('is null when the address is not set', () => {
    vi.stubEnv('SALON_API_BASE_URL', '');

    expect(createServerApiClient()).toBeNull();
  });
});
