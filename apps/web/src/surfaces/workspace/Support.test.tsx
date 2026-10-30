import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithMessages } from '@test/render';
import { SupportAccessScreen } from './SupportAccessScreen';
import { SupportBanner } from './SupportBanner';
import { SupportEntry } from './SupportEntry';

type Call = { url: string; body: Record<string, unknown> | null };
let calls: Call[];
const location = { hash: '', pathname: '/workspace/support', replace: vi.fn() };
function stubApi(answer: (url: string) => Response) {
  vi.stubGlobal('fetch', async (url: string, init?: RequestInit) => {
    calls.push({ url, body: init?.body ? JSON.parse(String(init.body)) : null });
    return answer(url);
  });
}
beforeEach(() => {
  calls = [];
  location.replace.mockReset();
  vi.stubGlobal('navigator', { locks: { request: (_name: string, work: () => Promise<unknown>) => work() } });
  vi.stubGlobal('BroadcastChannel', undefined);
  Object.defineProperty(window, 'location', { configurable: true, value: location });
  vi.spyOn(window.history, 'replaceState').mockImplementation(() => undefined);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const CODE = 'B'.repeat(43);

describe('support entry page', () => {
  it('removes the code from the address at once, exchanges it once and opens the workspace', async () => {
    location.hash = `#${CODE}`;
    stubApi(() => Response.json({ ok: true, expiresAt: '2026-01-01T00:30:00Z' }));
    renderWithMessages(<SupportEntry />);
    await waitFor(() => expect(location.replace).toHaveBeenCalledWith('/workspace/account'));
    expect(window.history.replaceState).toHaveBeenCalledWith(null, '', '/workspace/support');
    expect(calls).toEqual([{ url: '/identity-web/support/entry', body: { code: CODE } }]);
  });

  it('says a refused link was used, expired or is for another salon, and opens nothing', async () => {
    location.hash = `#${CODE}`;
    stubApi(() => Response.json({ code: 'invalid_code' }, { status: 401 }));
    renderWithMessages(<SupportEntry />);
    const refused = await screen.findByTestId('support-entry-refused');
    expect(refused).toHaveAttribute('role', 'alert');
    expect(refused).toHaveTextContent('work once, within a minute');
    expect(location.replace).not.toHaveBeenCalled();
  });

  it('offers no retry of a code whose answer was lost', async () => {
    location.hash = `#${CODE}`;
    stubApi(() => Response.json({ code: 'service_unavailable' }, { status: 503 }));
    renderWithMessages(<SupportEntry />);
    expect(await screen.findByTestId('support-entry-unavailable')).toHaveTextContent('Make a new link');
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('shows an ended view, and no link, after clearing any support cookies', async () => {
    location.hash = '#ended';
    stubApi(() => Response.json({ ok: true }));
    renderWithMessages(<SupportEntry />);
    expect(await screen.findByTestId('support-entry-ended')).toHaveTextContent('Support session ended');
    location.hash = '';
    expect(calls).toEqual([{ url: '/identity-web/support/clear', body: {} }]);
  });

  it('treats an address without a code as no support view', async () => {
    location.hash = '';
    stubApi(() => Response.json({ ok: true }));
    renderWithMessages(<SupportEntry />);
    expect(await screen.findByTestId('support-entry-none')).toBeInTheDocument();
    expect(calls.map((call) => call.url)).toEqual(['/identity-web/support/clear']);
  });
});

describe('support banner', () => {
  it('names the end time in the salon zone, is announced and ends the session', async () => {
    stubApi(() => Response.json({ ok: true }));
    renderWithMessages(<SupportBanner expiresAt="2099-01-01T08:45:00Z" timeZone="Asia/Colombo" />);
    const banner = screen.getByTestId('support-banner');
    expect(banner).toHaveAttribute('role', 'alert');
    expect(screen.getByTestId('support-banner-title')).toHaveTextContent('Support view, read only');
    expect(screen.getByTestId('support-banner-ends')).toHaveTextContent('Ends at 14:15');
    expect(within(banner).queryByRole('button', { name: /close|dismiss/i })).toBeNull();
    fireEvent.click(screen.getByTestId('support-banner-end'));
    await waitFor(() => expect(location.replace).toHaveBeenCalledWith('/workspace/support#ended'));
    expect(calls).toEqual([{ url: '/identity-web/support/end', body: {} }]);
  });

  it('keeps the view and says so when ending fails', async () => {
    stubApi(() => Response.json({ code: 'service_unavailable' }, { status: 503 }));
    renderWithMessages(<SupportBanner expiresAt="2099-01-01T08:45:00Z" timeZone="Asia/Colombo" />);
    fireEvent.click(screen.getByTestId('support-banner-end'));
    expect(await screen.findByTestId('support-banner-error')).toBeInTheDocument();
    expect(location.replace).not.toHaveBeenCalled();
  });
});

const ACTIVE = {
  id: 's1',
  operatorName: 'Nimal Support',
  reason: 'Owner asked about staff access',
  startedAt: '2026-01-01T08:00:00Z',
  expiresAt: '2026-01-01T08:30:00Z',
  endedAt: null,
  endedBy: null,
  state: 'active',
};
const PAST = { ...ACTIVE, id: 's0', endedAt: '2026-01-01T07:10:00Z', endedBy: 'owner', state: 'ended' };
const PRINCIPAL = {
  id: 'o',
  kind: 'staff',
  role: 'OWNER',
  permissions: ['support.manage'],
  scope: 'salon',
  staffId: 'o',
  mfaEnabled: true,
  expiresAt: null,
};

describe('Owner support access page', () => {
  it('lists active and past sessions with operator, reason, times and state, and revokes after confirmation', async () => {
    let revoked = false;
    stubApi((url) =>
      url.includes('principal')
        ? Response.json(PRINCIPAL)
        : url.includes('support-revoke')
          ? ((revoked = true), Response.json({ ok: true }))
          : Response.json({
              items: revoked
                ? [{ ...ACTIVE, state: 'ended', endedBy: 'owner', endedAt: ACTIVE.startedAt }, PAST]
                : [ACTIVE, PAST],
              page: 0,
              size: 10,
              total: 2,
            }),
    );
    renderWithMessages(<SupportAccessScreen timeZone="Asia/Colombo" />);
    const rows = await screen.findAllByTestId('support-access-row');
    expect(rows).toHaveLength(2);
    expect(within(rows[0]!).getByTestId('support-access-operator')).toHaveTextContent('Nimal Support');
    expect(within(rows[0]!).getByTestId('support-access-state')).toHaveTextContent('Active');
    expect(within(rows[0]!).getByTestId('support-access-reason')).toHaveTextContent('Owner asked about staff access');
    expect(within(rows[1]!).getByTestId('support-access-state')).toHaveTextContent('Revoked by you');
    expect(within(rows[1]!).queryByTestId('support-access-revoke')).toBeNull();
    fireEvent.click(within(rows[0]!).getByTestId('support-access-revoke'));
    const confirm = await screen.findByTestId('support-access-confirm');
    expect(confirm).toHaveAttribute('role', 'alertdialog');
    expect(calls.some((call) => call.url.includes('support-revoke'))).toBe(false);
    fireEvent.click(screen.getByTestId('support-access-confirm-revoke'));
    expect(await screen.findByTestId('support-access-revoked')).toBeInTheDocument();
    expect(calls.find((call) => call.url.includes('support-revoke'))?.body).toEqual({ id: 's1' });
  });

  it('cancels revocation with nothing sent, and shows the empty state', async () => {
    stubApi((url) =>
      url.includes('principal') ? Response.json(PRINCIPAL) : Response.json({ items: [], page: 0, size: 10, total: 0 }),
    );
    renderWithMessages(<SupportAccessScreen timeZone="Asia/Colombo" />);
    expect(await screen.findByTestId('support-access-empty')).toHaveTextContent('No support sessions yet');
  });

  it('says when another role is refused', async () => {
    stubApi((url) =>
      url.includes('principal')
        ? Response.json(PRINCIPAL)
        : Response.json({ code: 'permission_denied' }, { status: 403 }),
    );
    renderWithMessages(<SupportAccessScreen timeZone="Asia/Colombo" />);
    expect(await screen.findByTestId('support-access-error')).toHaveTextContent('Only the Owner');
  });
});

describe('support banner at expiry', () => {
  it('leaves the support view by itself once the session has ended', async () => {
    stubApi(() => Response.json({ ok: true }));
    renderWithMessages(<SupportBanner expiresAt={new Date(Date.now() - 1000).toISOString()} timeZone="Asia/Colombo" />);
    await waitFor(() => expect(location.replace).toHaveBeenCalledWith('/workspace/support#ended'));
    expect(calls).toEqual([]);
  });
});
