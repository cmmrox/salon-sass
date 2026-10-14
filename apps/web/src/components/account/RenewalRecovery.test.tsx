import { fireEvent, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { renderWithMessages } from '@/test/render';
import { AccountScreen } from './AccountScreen';

const navigation = vi.hoisted(() => ({ replace: vi.fn(), refresh: vi.fn() }));
vi.mock('next/navigation', () => ({ useRouter: () => navigation }));
afterEach(() => { vi.unstubAllGlobals(); vi.clearAllMocks(); });

it.each([
  ['staff', '/workspace/sign-in'],
  ['customer', '/sign-in'],
] as const)('recovers %s sign-in after one committed renewal loses its response', async (kind, destination) => {
  const calls: string[] = [];
  let committedRenewals = 0;
  let cleared = false;
  vi.stubGlobal('navigator', { locks: { request: (_name: string, work: () => Promise<unknown>) => work() } });
  vi.stubGlobal('BroadcastChannel', undefined);
  vi.stubGlobal('fetch', async (url: string) => {
    calls.push(url);
    if (url === `/identity-web/${kind}/refresh`) {
      committedRenewals++;
      throw new TypeError('Committed response connection reset');
    }
    if (url === `/identity-web/${kind}/clear`) { cleared = true; return Response.json({ ok: true }); }
    return Response.json({ code: 'invalid_session', renewable: !cleared }, { status: 401 });
  });
  renderWithMessages(<AccountScreen kind={kind} initialPrincipal={null} timeZone="Asia/Colombo" />);

  await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith(destination));
  expect(navigation.refresh).toHaveBeenCalled();
  expect(screen.getByTestId('account-sign-in-link')).toHaveAttribute('href', destination);
  expect(committedRenewals).toBe(1);
  expect(calls.filter((url) => url.endsWith('/clear'))).toEqual([`/identity-web/${kind}/clear`]);
  expect(calls.every((url) => url.startsWith(`/identity-web/${kind}/`))).toBe(true);

  // Even a retry while navigation is settling must not rotate the spent family.
  fireEvent.click(screen.getByTestId('account-retry'));
  await waitFor(() => expect(navigation.replace).toHaveBeenCalledTimes(2));
  expect(committedRenewals).toBe(1);
});

it('keeps an ordinary account outage recoverable without signing out', async () => {
  vi.stubGlobal('navigator', { locks: { request: (_name: string, work: () => Promise<unknown>) => work() } });
  vi.stubGlobal('BroadcastChannel', undefined);
  const fetch = vi.fn().mockRejectedValue(new TypeError('API unavailable'));
  vi.stubGlobal('fetch', fetch);
  renderWithMessages(<AccountScreen kind="customer" initialPrincipal={null} timeZone="Asia/Colombo" />);
  await screen.findByTestId('account-error');
  expect(navigation.replace).not.toHaveBeenCalled();
  expect(screen.queryByTestId('account-sign-in-link')).not.toBeInTheDocument();
  expect(fetch).toHaveBeenCalledTimes(1);
  expect(fetch).toHaveBeenCalledWith('/identity-web/customer/principal', expect.any(Object));
});
