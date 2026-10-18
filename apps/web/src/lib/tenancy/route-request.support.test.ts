// @vitest-environment node
import type { Salon } from '@/lib/api/salon';
import { NextRequest } from 'next/server';
import { describe, expect, it, vi } from 'vitest';
import { routeRequest } from './route-request';
import type { Site } from './site';

const alpha: Salon = { slug: 'alpha', name: 'Alpha Studio', brandColour: '#D6086A', timeZone: 'Asia/Colombo' };
const salonSite = () => vi.fn<(host: string | null) => Promise<Site>>(async () => ({ kind: 'salon', salon: alpha }));
function request(path: string, cookie: string): NextRequest {
  return new NextRequest(`http://alpha.localhost:3100${path}`, { headers: { host: 'alpha.localhost:3100', cookie } });
}

describe('the support view in the proxy (F03-S04)', () => {
  it('lets the entry page through without any credential, so it can read its fragment', async () => {
    const decide = vi.fn();
    const response = await routeRequest(request('/workspace/support', ''), salonSite(), decide);
    expect(response.status).toBe(200);
    expect(response.headers.get('location')).toBeNull();
    expect(decide).not.toHaveBeenCalled();
  });

  it('enters the support view at the account page, decided on the support credential before any staff session', async () => {
    const decide = vi.fn(async () => ({ kind: 'signed-in' as const, principal: { kind: 'support' } as never }));
    expect((await routeRequest(request('/workspace', 'salon_support_access=s'), salonSite(), decide)).headers.get('location'))
      .toBe('http://alpha.localhost:3100/workspace/account');
    const response = await routeRequest(request('/workspace/staff', 'salon_support_access=s; salon_staff_access=t'), salonSite(), decide);
    expect(response.status).toBe(200);
    expect(decide).toHaveBeenCalledExactlyOnceWith(expect.anything(), { access: 's' }, 'support');
  });

  it('leaves an ended support view for the entry page, clearing only the support cookies', async () => {
    const decide = vi.fn(async () => ({ kind: 'signed-out' as const }));
    const response = await routeRequest(request('/workspace/audit', 'salon_support_access=ended; salon_staff_access=t'), salonSite(), decide);
    expect(response.status).toBe(307);
    expect(response.headers.get('location')).toBe('http://alpha.localhost:3100/workspace/support#ended');
    const cleared = response.headers.getSetCookie();
    expect(cleared.filter((cookie) => /^salon_support_(access|session)=;/.test(cookie) && /Max-Age=0/i.test(cookie) && /HttpOnly/i.test(cookie))).toHaveLength(2);
    expect(cleared.some((cookie) => cookie.startsWith('salon_staff_'))).toBe(false);
  });

  it('protects the Owner\'s Support access page like every workspace page', async () => {
    const response = await routeRequest(request('/workspace/support-access', ''), salonSite(), vi.fn());
    expect(response.headers.get('location')).toBe('http://alpha.localhost:3100/workspace/sign-in');
  });
});
