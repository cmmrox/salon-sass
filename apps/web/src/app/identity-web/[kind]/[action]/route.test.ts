// @vitest-environment node
import { NextRequest } from 'next/server';
import { beforeEach, describe, expect, it, vi } from 'vitest';
const handleIdentity = vi.hoisted(() => vi.fn());
vi.mock('@/features/identity/server', () => ({ createIdentityWeb: () => handleIdentity }));
import { GET, POST } from './route';
beforeEach(() => handleIdentity.mockReset());
describe('staff-only web boundary', () => {
  it.each(['code', 'sign-in', 'principal', 'profile', 'sessions', 'refresh', 'clear', 'export', 'delete', 'logout'])(
    'rejects customer %s without reaching identity or issuing cookies',
    async (action) => {
      for (const method of ['GET', 'POST'] as const) {
        const request = new NextRequest(`http://alpha.localhost/identity-web/customer/${action}`, {
          method,
          headers: { host: 'alpha.localhost', cookie: 'salon_customer_access=stale; salon_staff_access=private' },
        });
        const response = await (method === 'GET' ? GET : POST)(request, {
          params: Promise.resolve({ kind: 'customer', action }),
        });
        expect(response.status).toBe(404);
        expect(await response.json()).toEqual({ code: 'not_found' });
        expect(response.headers.get('cache-control')).toContain('no-store');
        expect(response.headers.has('set-cookie')).toBe(false);
      }
      expect(handleIdentity).not.toHaveBeenCalled();
    },
  );
  it('delegates staff requests to the existing protected boundary', async () => {
    const request = new NextRequest('http://alpha.localhost/identity-web/staff/principal');
    const response = Response.json({ ok: true });
    handleIdentity.mockResolvedValue(response);
    expect(await GET(request, { params: Promise.resolve({ kind: 'staff', action: 'principal' }) })).toBe(response);
    expect(handleIdentity).toHaveBeenCalledExactlyOnceWith(request, 'staff', 'principal');
  });
});
