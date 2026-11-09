// @vitest-environment node
import { NextRequest } from 'next/server';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const teamPhoto = vi.fn();
vi.mock('@/features/staff/server', () => ({ teamPhoto: (...args: unknown[]) => teamPhoto(...args) }));
vi.mock('@/features/identity/server', () => ({
  requestHost: (raw: string | null) => (raw === 'alpha.salon.test' ? { hostname: raw } : null),
}));

const { GET } = await import('./route');

const ID = '33333333-0000-4000-8000-000000000001';
const get = (host: string, id = ID) =>
  GET(new NextRequest(`http://${host}/team-photos/${id}`, { headers: { host } }), {
    params: Promise.resolve({ id }),
  });

describe('a team photo on the salon’s own hostname (F06-S01 AC-9)', () => {
  beforeEach(() => teamPhoto.mockReset());

  it('passes the bytes through as a JPEG that nothing stores, with nosniff', async () => {
    teamPhoto.mockResolvedValue({ kind: 'image', bytes: new Uint8Array([1, 2, 3]).buffer });

    const response = await get('alpha.salon.test');

    expect(response.status).toBe(200);
    expect(response.headers.get('content-type')).toBe('image/jpeg');
    expect(response.headers.get('cache-control')).toContain('no-store');
    expect(response.headers.get('x-content-type-options')).toBe('nosniff');
    expect(teamPhoto).toHaveBeenCalledWith('alpha.salon.test', ID);
    expect(new Uint8Array(await response.arrayBuffer())).toEqual(new Uint8Array([1, 2, 3]));
  });

  it('answers what the API answered for a photo it does not have, with the same headers', async () => {
    teamPhoto.mockResolvedValue({ kind: 'none', status: 404 });

    const response = await get('alpha.salon.test');

    expect(response.status).toBe(404);
    expect(response.headers.get('x-content-type-options')).toBe('nosniff');
  });

  it('asks nothing for a hostname that names no salon', async () => {
    const response = await get('elsewhere.test');

    expect(response.status).toBe(404);
    expect(teamPhoto).not.toHaveBeenCalled();
  });
});
