import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import type { AnchorHTMLAttributes } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithMessages } from '@/test/render';
import { chooseOption } from '@/test/select';
import { ConsoleShell } from './ConsoleShell';
import { SalonDetail } from './SalonScreens';
import { SupportCard, SupportSessionList } from './SupportScreens';

vi.mock('next/navigation', () => ({ useRouter: () => ({ replace: vi.fn(), refresh: vi.fn() }), usePathname: () => '/console/support' }));
vi.mock('next/link', () => ({ default: (props: AnchorHTMLAttributes<HTMLAnchorElement>) => <a {...props} /> }));

type Call = { url: string; body: Record<string, unknown> | null };
let calls: Call[];
function stubApi(answer: (url: string, body: Record<string, unknown> | null) => Response) {
  vi.stubGlobal('fetch', async (url: string, init?: RequestInit) => {
    const body = init?.body ? JSON.parse(String(init.body)) : null;
    calls.push({ url, body });
    return answer(url, body);
  });
}
beforeEach(() => {
  calls = [];
  vi.stubGlobal('navigator', { locks: { request: (_name: string, work: () => Promise<unknown>) => work() } });
  vi.stubGlobal('BroadcastChannel', undefined);
});
afterEach(() => { vi.unstubAllGlobals(); });

const SALON = { id: '22222222-0000-4000-8000-000000000002', version: 0, name: 'Synthetic Salon', slug: 'synthetic', hostname: 'synthetic.localhost', status: 'active', country: 'LK', currency: 'LKR', timeZone: 'Asia/Colombo', defaultLanguage: 'en', contactEmail: null, contactPhone: null, address: null, plan: null, ownerEmail: null, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z', closedAt: null, deleteAt: null, deletedAt: null };
const ENTRY = { id: '33333333-0000-4000-8000-000000000003', expiresAt: '2026-01-01T00:30:00Z', entryUrl: 'http://synthetic.localhost:3100/workspace/support#' + 'C'.repeat(43), codeExpiresAt: '2026-01-01T00:01:00Z' };
const PRINCIPAL = { id: 'op', kind: 'operator', role: 'OPERATOR_SUPPORT', permissions: ['operator.salon.read', 'operator.session.manage', 'operator.support.start'], mfaEnabled: true };
const SESSION = { id: ENTRY.id, salon: { id: SALON.id, slug: 'synthetic', name: 'Synthetic Salon', hostname: 'synthetic.localhost' }, operator: { id: 'op', displayName: 'Nimal Support' }, reason: 'Owner asked about staff access', startedAt: '2026-01-01T00:00:00Z', expiresAt: ENTRY.expiresAt, endedAt: null, endedBy: null, state: 'active' };

describe('console navigation by role (F03-S04)', () => {
  it('shows Salons, Plans and Support sessions to an Admin and Salons and Support sessions to Support', () => {
    const { unmount } = renderWithMessages(<ConsoleShell permissions={['operator.salon.read', 'operator.plan.read', 'operator.support.read']} view="salons"><p /></ConsoleShell>);
    expect(screen.getAllByTestId(/^console-nav-/).map((item) => item.getAttribute('data-testid'))).toEqual(['console-nav-salons', 'console-nav-plans', 'console-nav-support']);
    unmount();
    renderWithMessages(<ConsoleShell permissions={PRINCIPAL.permissions} view="support"><p /></ConsoleShell>);
    expect(screen.getAllByTestId(/^console-nav-/).map((item) => item.getAttribute('data-testid'))).toEqual(['console-nav-salons', 'console-nav-support']);
  });
});

describe('a salon for Support', () => {
  it('shows the profile and Start support session, and no lifecycle, export or billing management', async () => {
    stubApi(() => Response.json(SALON));
    renderWithMessages(<SalonDetail id={SALON.id} permissions={PRINCIPAL.permissions} />);
    expect(await screen.findByTestId('console-support-card')).toBeInTheDocument();
    expect(screen.queryByTestId('console-lifecycle-card')).toBeNull();
    expect(screen.queryByTestId('console-subscription-card')).toBeNull();
    expect(calls.map((call) => call.url)).toEqual([`/operator-web/salon?id=${SALON.id}`]);
  });

  it('starts a session with a reason and a duration, then offers the single-use link in a new tab', async () => {
    stubApi((url) => url.includes('principal') ? Response.json(PRINCIPAL) : Response.json(ENTRY, { status: 201 }));
    renderWithMessages(<SupportCard salon={SALON as never} />);
    fireEvent.click(screen.getByTestId('console-support-start'));
    const sheet = await screen.findByTestId('console-support-sheet');
    expect(within(sheet).getByLabelText('Reason')).toBeInTheDocument();
    fireEvent.change(within(sheet).getByLabelText('Reason'), { target: { value: 'Owner asked about staff access' } });
    await chooseOption(screen.getByTestId('console-support-duration'), '60 minutes');
    fireEvent.submit(screen.getByTestId('console-support-submit').closest('form')!);
    const link = await screen.findByTestId('console-support-started-open');
    expect(link).toHaveAttribute('href', ENTRY.entryUrl);
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    expect(calls.find((call) => call.url === '/operator-web/support-start')?.body).toEqual({ id: SALON.id, reason: 'Owner asked about staff access', durationMinutes: 60 });
  });

  it('shows a field error and the refusal for a short reason or a second open session', async () => {
    stubApi((url) => url.includes('principal') ? Response.json(PRINCIPAL) : Response.json({ code: 'support_session_active', errors: [] }, { status: 409 }));
    renderWithMessages(<SupportCard salon={SALON as never} />);
    fireEvent.click(screen.getByTestId('console-support-start'));
    await screen.findByTestId('console-support-sheet');
    fireEvent.submit(screen.getByTestId('console-support-submit').closest('form')!);
    expect(await screen.findByTestId('console-error')).toHaveTextContent('already have an open support session');
  });

  it('offers no start for a suspended salon', () => {
    renderWithMessages(<SupportCard salon={{ ...SALON, status: 'suspended' } as never} />);
    expect(screen.queryByTestId('console-support-start')).toBeNull();
    expect(screen.getByTestId('console-support-card')).toHaveTextContent('only for an active salon');
  });
});

describe('support sessions list', () => {
  it('lets Support make a new entry link and end its own session after confirming', async () => {
    let ended = false;
    stubApi((url) => url.includes('principal') ? Response.json(PRINCIPAL)
      : url.includes('support-entry-code') ? Response.json(ENTRY, { status: 201 })
        : url.includes('support-end') ? (ended = true, Response.json({ ok: true }))
          : Response.json({ items: ended ? [] : [SESSION], page: 0, size: 20, total: ended ? 0 : 1 }));
    renderWithMessages(<SupportSessionList admin={false} />);
    const row = await screen.findByTestId('console-support-row');
    expect(within(row).getByTestId('console-support-operator')).toHaveTextContent('Nimal Support');
    expect(calls[0]!.url).toBe('/operator-web/support-sessions?page=0&size=20&state=active');
    fireEvent.click(within(row).getByTestId('console-support-open'));
    expect(await screen.findByTestId('console-support-link-open')).toHaveAttribute('href', ENTRY.entryUrl);
    fireEvent.click(within(row).getByTestId('console-support-end'));
    fireEvent.click(await screen.findByTestId('console-support-confirm-end'));
    expect(await screen.findByTestId('console-support-notice')).toHaveTextContent('Support session ended');
    expect(calls.find((call) => call.url === '/operator-web/support-end')?.body).toEqual({ id: ENTRY.id });
    expect(await screen.findByTestId('console-support-empty')).toBeInTheDocument();
  });

  it('lets an Admin revoke any session, with no entry link to make', async () => {
    stubApi((url) => url.includes('principal') ? Response.json({ ...PRINCIPAL, role: 'OPERATOR_ADMIN' }) : url.includes('support-end') ? Response.json({ ok: true }) : Response.json({ items: [SESSION], page: 0, size: 20, total: 1 }));
    renderWithMessages(<SupportSessionList admin />);
    const row = await screen.findByTestId('console-support-row');
    expect(within(row).queryByTestId('console-support-open')).toBeNull();
    expect(within(row).getByTestId('console-support-end')).toHaveTextContent('Revoke');
    fireEvent.click(within(row).getByTestId('console-support-end'));
    fireEvent.click(await screen.findByTestId('console-support-confirm-end'));
    await waitFor(() => expect(calls.some((call) => call.url === '/operator-web/support-end')).toBe(true));
    expect(await screen.findByTestId('console-support-notice')).toHaveTextContent('revoked');
  });

  it('filters by state and shows the outage with a retry', async () => {
    stubApi(() => Response.json({ code: 'service_unavailable' }, { status: 503 }));
    renderWithMessages(<SupportSessionList admin />);
    expect(await screen.findByTestId('console-error')).toBeInTheDocument();
    await chooseOption(screen.getByTestId('console-support-state'), 'Ended');
    await waitFor(() => expect(calls.at(-1)?.url).toBe('/operator-web/support-sessions?page=0&size=20&state=ended'));
  });
});
