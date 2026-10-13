// @vitest-environment node
import { afterEach, describe, expect, it, vi } from 'vitest';

import { serverState } from '@/lib/observability/server-state';

vi.mock('next/server', () => ({ connection: async () => {} }));

const { GET } = await import('./route');

afterEach(() => {
  serverState().draining = false;
});

describe('GET /healthz', () => {
  it('answers 200 UP while the server takes traffic, and is never cached', async () => {
    const response = await GET();

    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ status: 'UP' });
    expect(response.headers.get('cache-control')).toBe('no-store');
  });

  it('answers 503 OUT_OF_SERVICE once the server has been told to stop', async () => {
    serverState().draining = true;

    const response = await GET();

    expect(response.status).toBe(503);
    expect(await response.json()).toEqual({ status: 'OUT_OF_SERVICE' });
  });
});
