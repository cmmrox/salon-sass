import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import type { AnchorHTMLAttributes } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithMessages } from '@/test/render';
import { chooseOption } from '@/test/select';
import { ConsoleShell } from './ConsoleShell';
import { ConsoleSignIn } from './ConsoleSignIn';
import { PlanDetail, PlanDirectory } from './PlanScreens';
import { SalonDetail, SalonDirectory } from './SalonScreens';

const replace = vi.fn();
vi.mock('next/navigation', () => ({ useRouter: () => ({ replace, refresh: vi.fn() }) }));
vi.mock('next/link', () => ({ default: (props: AnchorHTMLAttributes<HTMLAnchorElement>) => <a {...props} /> }));

const PLAN = { id: '11111111-0000-4000-8000-000000000001', key: 'fixture-basic', name: 'Synthetic Basic', staffSeats: 5, branches: 1, messageAllowance: 0, customDomain: false, dedicatedApp: true, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' };
const ADMIN_PERMISSIONS = ['operator.billing.manage', 'operator.billing.read', 'operator.export.read', 'operator.plan.create', 'operator.plan.read', 'operator.salon.close', 'operator.salon.create', 'operator.salon.read', 'operator.salon.suspend', 'operator.session.manage', 'operator.support.read', 'operator.support.revoke'];
type Call = { url: string; body: Record<string, unknown> | null };
let calls: Call[];

function stubApi(answer: (url: string, body: Record<string, unknown> | null) => Response | Promise<Response>) {
  vi.stubGlobal('fetch', async (url: string, init?: RequestInit) => {
    const body = init?.body ? JSON.parse(String(init.body)) : null;
    calls.push({ url, body });
    return answer(url, body);
  });
}

beforeEach(() => {
  calls = [];
  vi.stubGlobal('navigator', { locks: { request: (_name: string, work: () => Promise<unknown>) => work() }, clipboard: { writeText: async () => undefined } });
  vi.stubGlobal('BroadcastChannel', undefined);
  vi.stubGlobal('crypto', { randomUUID: (() => { let n = 0; return () => `00000000-0000-4000-8000-${String(++n).padStart(12, '0')}`; })() });
});
afterEach(() => { vi.unstubAllGlobals(); replace.mockReset(); });

describe('operator sign-in', () => {
  it('enrolls an authenticator after the password and never shows a session credential', async () => {
    stubApi((url) => Response.json(url.endsWith('/sign-in') ? { state: 'enroll_mfa', expiresAt: '2026-01-01T00:05:00Z' } : url.endsWith('/mfa-enroll') ? { secret: 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567', otpauthUri: 'otpauth://totp/x', expiresAt: '2026-01-01T00:05:00Z' } : { ok: true }));
    renderWithMessages(<ConsoleSignIn />);
    await waitFor(() => expect(screen.getByTestId('console-sign-in-submit')).toBeEnabled());
    fireEvent.change(screen.getByTestId('console-email'), { target: { value: 'op@example.test' } });
    fireEvent.change(screen.getByTestId('console-password'), { target: { value: 'private password value' } });
    fireEvent.submit(screen.getByTestId('console-sign-in-submit').closest('form')!);
    expect(await screen.findByTestId('console-setup-key')).toHaveTextContent('ABCDEFGHIJKLMNOPQRSTUVWXYZ234567');
    expect(screen.getByTestId('console-mfa-notice')).toBeInTheDocument();
    expect(screen.queryByTestId('console-password')).not.toBeInTheDocument();
    expect(calls[0]!.body).toMatchObject({ email: 'op@example.test', password: 'private password value' });
    fireEvent.change(screen.getByTestId('console-code'), { target: { value: '123456' } });
    fireEvent.submit(screen.getByTestId('console-sign-in-submit').closest('form')!);
    await waitFor(() => expect(replace).toHaveBeenCalledWith('/console/salons'));
    expect(calls.map((call) => call.url)).toEqual(['/operator-web/sign-in', '/operator-web/mfa-enroll', '/operator-web/mfa-verify']);
  });

  it('announces a refusal with its wait, clears the password and keeps the email', async () => {
    stubApi(() => Response.json({ code: 'rate_limited', errors: [], retryAfter: 60 }, { status: 429 }));
    renderWithMessages(<ConsoleSignIn />);
    await waitFor(() => expect(screen.getByTestId('console-sign-in-submit')).toBeEnabled());
    fireEvent.change(screen.getByTestId('console-email'), { target: { value: 'op@example.test' } });
    fireEvent.change(screen.getByTestId('console-password'), { target: { value: 'private password value' } });
    fireEvent.submit(screen.getByTestId('console-sign-in-submit').closest('form')!);
    expect(await screen.findByTestId('console-error')).toHaveAttribute('role', 'alert');
    expect(screen.getByTestId('console-error')).toHaveAttribute('data-code', 'rate_limited');
    expect(screen.getByTestId('console-password')).toHaveValue('');
    expect(screen.getByTestId('console-email')).toHaveValue('op@example.test');
    expect(screen.getByTestId('console-sign-in-submit')).toBeDisabled();
  });

  it('blocks sign-in with an explanation in a browser without Web Locks', async () => {
    vi.stubGlobal('navigator', {});
    renderWithMessages(<ConsoleSignIn />);
    expect(await screen.findByTestId('console-browser-support')).toBeInTheDocument();
    expect(screen.getByTestId('console-sign-in-submit')).toBeDisabled();
  });
});

describe('the console frame', () => {
  it('shows an Admin exactly Salons, Plans and Sign out', () => {
    renderWithMessages(<ConsoleShell permissions={['operator.session.manage', 'operator.salon.read', 'operator.plan.read']} view="salons"><p /></ConsoleShell>);
    expect(screen.getByTestId('console-nav-salons')).toHaveAttribute('aria-current', 'page');
    expect(screen.getByTestId('console-nav-plans')).toBeInTheDocument();
    expect(screen.getByTestId('console-sign-out')).toBeInTheDocument();
    expect(within(screen.getByTestId('console-shell')).queryAllByRole('link').map((link) => link.getAttribute('href'))).toEqual(['/console/salons', '/console/plans']);
  });

  it('shows Support no management link, only Sign out', () => {
    renderWithMessages(<ConsoleShell permissions={['operator.session.manage']}><p /></ConsoleShell>);
    expect(screen.queryByTestId('console-nav-salons')).not.toBeInTheDocument();
    expect(screen.queryByTestId('console-nav-plans')).not.toBeInTheDocument();
    expect(screen.queryByTestId('console-menu')).not.toBeInTheDocument();
    expect(screen.getByTestId('console-sign-out')).toBeInTheDocument();
  });
});

describe('the salon directory', () => {
  it('shows loading, then rows with status, plan and hostname, and searches literally', async () => {
    let release!: () => void;
    const gate = new Promise<void>((resolve) => { release = resolve; });
    stubApi(async (url) => {
      if (url.startsWith('/operator-web/salons')) await gate;
      return Response.json({ items: [{ id: 's1', name: null, slug: 'legacy', hostname: 'legacy.localhost', status: 'active', plan: null, createdAt: '2026-01-01T00:00:00Z' }, { id: 's2', name: 'A very long salon name that keeps going and going without any break at all', slug: 'long', hostname: 'long.localhost', status: 'suspended', plan: { id: 'p', key: 'k', name: 'Basic' }, createdAt: '2026-01-01T00:00:00Z' }], page: 0, size: 20, total: 2 });
    });
    renderWithMessages(<SalonDirectory platformDomain="localhost" />);
    expect(await screen.findByTestId('console-loading')).toHaveAttribute('role', 'status');
    release();
    const rows = await screen.findAllByTestId('console-salon-row');
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent('Not configured');
    expect(rows[0]).toHaveTextContent('No plan assigned');
    expect(rows[1]).toHaveTextContent('Suspended');
    expect(rows[1]).toHaveAttribute('href', '/console/salons/s2');
    fireEvent.change(screen.getByTestId('console-salons-search'), { target: { value: ' 100%_ ' } });
    fireEvent.submit(screen.getByTestId('console-salons-search-submit').closest('form')!);
    await waitFor(() => expect(calls.at(-1)!.url).toBe('/operator-web/salons?page=0&size=20&q=100%25_'));
  });

  it('says what belongs there when empty, and offers a retry on an outage', async () => {
    let fail = true;
    stubApi(() => (fail ? Response.json({ code: 'service_unavailable' }, { status: 503 }) : Response.json({ items: [], page: 0, size: 20, total: 0 })));
    renderWithMessages(<SalonDirectory platformDomain="localhost" />);
    expect(await screen.findByTestId('console-error')).toHaveTextContent('cannot reach');
    fail = false;
    fireEvent.click(screen.getByTestId('console-retry'));
    expect(await screen.findByTestId('console-salons-empty')).toHaveTextContent('No salons yet');
  });

  it('keeps one key for an uncertain retry, clears the password after a definite refusal, and shows the result', async () => {
    let outcome: 'uncertain' | 'taken' | 'created' = 'uncertain';
    stubApi((url) => {
      if (url.startsWith('/operator-web/plans')) return Response.json({ items: [PLAN], page: 0, size: 100, total: 1 });
      if (url === '/operator-web/principal') return Response.json({ id: 'op', kind: 'operator', role: 'OPERATOR_ADMIN', permissions: [], mfaEnabled: true });
      if (url === '/operator-web/salon-create') {
        if (outcome === 'uncertain') return Response.json({ code: 'service_unavailable' }, { status: 503 });
        if (outcome === 'taken') return Response.json({ code: 'slug_taken', errors: [] }, { status: 409 });
        return Response.json({ id: 'new', slug: 'fresh', hostname: 'fresh.localhost', planId: PLAN.id, createdAt: '2026-01-01T00:00:00Z' }, { status: 201 });
      }
      return Response.json({ items: [], page: 0, size: 20, total: 0 });
    });
    renderWithMessages(<SalonDirectory platformDomain="localhost" />);
    fireEvent.click(await screen.findByTestId('console-create-salon'));
    const sheet = await screen.findByTestId('console-create-salon-sheet');
    expect(within(sheet).getByTestId('console-country')).toHaveValue('LK');
    expect(within(sheet).getByTestId('console-currency')).toHaveValue('LKR');
    await waitFor(() => expect(within(sheet).getByTestId('console-plan-id')).toBeEnabled());
    await chooseOption(within(sheet).getByTestId('console-plan-id'), 'Synthetic Basic (fixture-basic)');
    fireEvent.change(within(sheet).getByTestId('console-salon-name'), { target: { value: 'Fresh Salon' } });
    fireEvent.change(within(sheet).getByTestId('console-slug'), { target: { value: 'fresh' } });
    fireEvent.change(within(sheet).getByTestId('console-owner-name'), { target: { value: 'Owner' } });
    fireEvent.change(within(sheet).getByTestId('console-owner-email'), { target: { value: 'owner@example.test' } });
    fireEvent.change(within(sheet).getByTestId('console-owner-password'), { target: { value: 'private owner password' } });
    const submit = () => fireEvent.submit(within(sheet).getByTestId('console-create-salon-submit').closest('form')!);
    submit();
    expect(await within(sheet).findByTestId('console-uncertain')).toBeInTheDocument();
    expect(within(sheet).getByTestId('console-owner-password')).toHaveValue('private owner password');
    outcome = 'taken';
    submit();
    await waitFor(() => expect(within(sheet).getByTestId('console-error')).toHaveAttribute('data-code', 'slug_taken'));
    const creates = calls.filter((call) => call.url === '/operator-web/salon-create');
    expect(creates[1]!.body!.idempotencyKey).toBe(creates[0]!.body!.idempotencyKey);
    expect(within(sheet).getByTestId('console-owner-password')).toHaveValue('');
    fireEvent.change(within(sheet).getByTestId('console-slug'), { target: { value: 'fresh-two' } });
    fireEvent.change(within(sheet).getByTestId('console-owner-password'), { target: { value: 'private owner password' } });
    outcome = 'created';
    submit();
    expect(await screen.findByTestId('console-salon-created')).toHaveTextContent('fresh.localhost');
    expect(screen.getByTestId('console-salon-created-next')).toHaveTextContent('/workspace/sign-in');
    const last = calls.filter((call) => call.url === '/operator-web/salon-create').at(-1)!;
    expect(last.body!.idempotencyKey).not.toBe(creates[0]!.body!.idempotencyKey);
    expect(document.body.textContent).not.toContain('private owner password');
  });
});

describe('detail pages', () => {
  it('shows a legacy salon\'s unconfigured values as not configured and its hostname to copy', async () => {
    stubApi((url) => url.startsWith('/operator-web/subscription') ? Response.json({ code: 'not_found' }, { status: 404 })
      : url.startsWith('/operator-web/lifecycle') ? Response.json({ items: [], page: 0, size: 10, total: 0 })
        : Response.json({ id: 's', version: 0, name: null, slug: 'legacy', hostname: 'legacy.localhost', status: 'active', country: null, currency: null, timeZone: 'Asia/Colombo', defaultLanguage: null, contactEmail: null, contactPhone: null, address: null, plan: null, ownerEmail: null, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z', closedAt: null, deleteAt: null, deletedAt: null }));
    renderWithMessages(<SalonDetail id="s" permissions={ADMIN_PERMISSIONS} />);
    expect(await screen.findByTestId('console-salon-name')).toHaveTextContent('Not configured');
    expect(screen.getByTestId('console-salon-country')).toHaveTextContent('Not configured');
    expect(screen.getByTestId('console-salon-plan')).toHaveTextContent('No plan assigned');
    fireEvent.click(screen.getByTestId('console-copy-hostname'));
    expect(await screen.findByTestId('console-hostname-copied')).toBeInTheDocument();
    // F03-S02: an unbilled legacy salon has no subscription and no billing contact.
    expect(await screen.findByTestId('console-subscription-none')).toHaveTextContent('No subscription');
    expect(screen.getByTestId('console-no-billing-contact')).toBeInTheDocument();
    expect(screen.getByTestId('console-start-subscription')).toBeInTheDocument();
  });

  it('shows a plan\'s exact limits and explicit features, and not found without a retry', async () => {
    stubApi(() => Response.json(PLAN));
    renderWithMessages(<PlanDetail id={PLAN.id} />);
    expect(await screen.findByTestId('console-plan-staff-seats')).toHaveTextContent('5');
    expect(screen.getByTestId('console-plan-message-allowance')).toHaveTextContent('0');
    expect(screen.getByTestId('console-plan-custom-domain')).toHaveTextContent('Not included');
    expect(screen.getByTestId('console-plan-dedicated-app')).toHaveTextContent('Included');
  });

  it('answers an unknown plan as not found, with no retry to offer', async () => {
    stubApi(() => Response.json({ code: 'not_found' }, { status: 404 }));
    renderWithMessages(<PlanDetail id="missing" />);
    expect(await screen.findByTestId('console-error')).toHaveAttribute('data-code', 'not_found');
    expect(screen.queryByTestId('console-retry')).not.toBeInTheDocument();
  });

  it('lists plans and offers Create plan', async () => {
    stubApi(() => Response.json({ items: [PLAN], page: 0, size: 20, total: 1 }));
    renderWithMessages(<PlanDirectory />);
    expect(await screen.findByTestId('console-plan-row')).toHaveTextContent('fixture-basic');
    expect(screen.getByTestId('console-create-plan')).toBeInTheDocument();
  });
});
