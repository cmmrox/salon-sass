import { cleanup, screen, waitFor, within } from '@testing-library/react';
import type { AnchorHTMLAttributes } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Principal } from '@/features/identity/server/identity-web';
import { renderWithMessages } from '@test/render';
import { formatDate } from '@/lib/format';
import { WorkspaceShell } from './WorkspaceShell';
import { AccountScreen } from '@/features/identity/components/AccountScreen';
import { BillingBanner, type Entitlements } from '@/features/platform/components/BillingBanner';
import { BillingScreen } from './BillingScreen';

const navigation = vi.hoisted(() => ({ replace: vi.fn(), refresh: vi.fn() }));
vi.mock('next/navigation', () => ({ useRouter: () => navigation }));
vi.mock('next/link', () => ({ default: (props: AnchorHTMLAttributes<HTMLAnchorElement>) => <a {...props} /> }));

const salon = { slug: 'alpha', name: 'Alpha Studio', brandColour: '#D6086A', timeZone: 'Asia/Colombo' };
const owner: Principal = { id: 'owner', kind: 'staff', role: 'OWNER', staffId: 'owner', scope: 'salon', mfaEnabled: true, expiresAt: null, permissions: ['session.manage', 'staff.list', 'staff.manage', 'audit.list', 'entitlement.read', 'invoice.read'] };
const entitlements = (subscription: Entitlements['subscription']): Entitlements => ({
  plan: { key: 'basic', name: 'Basic' },
  limits: { staffSeats: 5, branches: 1, messageAllowance: 100 },
  features: { customDomain: false, dedicatedApp: true },
  usage: { staffSeats: 3 },
  subscription,
});

beforeEach(() => {
  vi.stubGlobal('navigator', { locks: { request: (_name: string, work: () => Promise<unknown>) => work() } });
  vi.stubGlobal('BroadcastChannel', undefined);
});
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.clearAllMocks(); });

describe('the billing banner', () => {
  it('shows nothing without a notice, and each notice the API chose with its dates', () => {
    const { container } = renderWithMessages(<BillingBanner entitlements={entitlements({ state: 'active', trialEndsAt: '2026-01-15T00:00:00Z', readOnlyAt: null, pastDueSince: null, notice: null })} timeZone="Asia/Colombo" />);
    expect(container).toBeEmptyDOMElement();
    cleanup();
    renderWithMessages(<BillingBanner entitlements={entitlements({ state: 'trialing', trialEndsAt: '2026-01-15T00:00:00Z', readOnlyAt: null, pastDueSince: null, notice: 'trial_ending' })} timeZone="Asia/Colombo" />);
    expect(screen.getByTestId('workspace-billing-banner')).toHaveAttribute('data-notice', 'trial_ending');
    expect(screen.getByTestId('workspace-billing-banner')).toHaveTextContent(formatDate('2026-01-15T00:00:00Z', 'Asia/Colombo'));
    cleanup();
    renderWithMessages(<BillingBanner entitlements={entitlements({ state: 'past_due', trialEndsAt: '2026-01-15T00:00:00Z', readOnlyAt: '2026-02-05T00:00:00Z', pastDueSince: '2026-01-22T00:00:00Z', notice: 'past_due' })} timeZone="Asia/Colombo" />);
    expect(screen.getByTestId('workspace-billing-banner')).toHaveTextContent(formatDate('2026-01-22T00:00:00Z', 'Asia/Colombo'));
    expect(screen.getByTestId('workspace-billing-banner')).toHaveTextContent(formatDate('2026-02-05T00:00:00Z', 'Asia/Colombo'));
    cleanup();
    renderWithMessages(<BillingBanner entitlements={entitlements({ state: 'read_only', trialEndsAt: '2026-01-15T00:00:00Z', readOnlyAt: '2026-02-05T00:00:00Z', pastDueSince: '2026-01-22T00:00:00Z', notice: 'read_only' })} timeZone="Asia/Colombo" />);
    expect(screen.getByTestId('workspace-billing-banner')).toHaveAttribute('role', 'alert');
    expect(screen.getByTestId('workspace-billing-banner')).toHaveTextContent('bank transfer');
  });

  it('is shown above every workspace page and the Owner alone gets the Plan and billing route', () => {
    const banner = <BillingBanner entitlements={entitlements({ state: 'read_only', trialEndsAt: '2026-01-15T00:00:00Z', readOnlyAt: '2026-02-05T00:00:00Z', pastDueSince: '2026-01-22T00:00:00Z', notice: 'read_only' })} timeZone="Asia/Colombo" />;
    renderWithMessages(<WorkspaceShell salon={salon} permissions={owner.permissions} view="billing" banner={banner}><div /></WorkspaceShell>);
    expect(within(screen.getByTestId('workspace-main')).getByTestId('workspace-billing-banner')).toBeInTheDocument();
    expect(screen.getByTestId('workspace-billing')).toHaveAttribute('aria-current', 'page');
    cleanup();
    renderWithMessages(<WorkspaceShell salon={salon} permissions={['session.manage', 'entitlement.read']} banner={banner}><div /></WorkspaceShell>);
    expect(screen.queryByTestId('workspace-billing')).not.toBeInTheDocument();
    expect(screen.getByTestId('workspace-billing-banner')).toBeInTheDocument();
  });
});

describe('the Plan and billing page', () => {
  it('shows the plan with limits and usage, the state and the invoices, with no payment entry', async () => {
    vi.stubGlobal('fetch', async (url: string) => {
      if (url.endsWith('/principal')) return Response.json(owner);
      if (url.endsWith('/entitlements')) return Response.json(entitlements({ state: 'past_due', trialEndsAt: '2026-01-15T00:00:00Z', readOnlyAt: '2026-02-05T00:00:00Z', pastDueSince: '2026-01-22T00:00:00Z', notice: 'past_due' }));
      return Response.json({ items: [{ id: 'i1', number: 'INV-000007', periodStart: '2026-01-15T00:00:00Z', periodEnd: '2026-02-15T00:00:00Z', amount: 250000, currency: 'LKR', status: 'open', issuedAt: '2026-01-15T00:00:00Z', dueAt: '2026-01-22T00:00:00Z', paidAt: null }], page: 0, size: 10, total: 1 });
    });
    renderWithMessages(<BillingScreen kind="staff" timeZone="Asia/Colombo" />);
    expect(await screen.findByTestId('billing-plan-name')).toHaveTextContent('Basic');
    expect(screen.getByTestId('billing-staff-seats')).toHaveTextContent('3 of 5 used');
    expect(screen.getByTestId('billing-state')).toHaveTextContent('Past due');
    const row = screen.getByTestId('billing-invoice-row');
    expect(row).toHaveAttribute('data-slot', 'item');
    expect(within(row).getByTestId('billing-invoice-amount')).toHaveTextContent('Rs. 2,500');
    expect(within(row).getByTestId('billing-invoice-status')).toHaveTextContent('Open');
    expect(screen.getByTestId('billing-pagination')).toHaveAttribute('data-slot', 'pagination');
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
  });

  it('reports an outage with a retry', async () => {
    vi.stubGlobal('fetch', async (url: string) => (url.endsWith('/principal') ? Response.json(owner) : Response.json({ code: 'service_unavailable' }, { status: 503 })));
    renderWithMessages(<BillingScreen kind="staff" timeZone="Asia/Colombo" />);
    expect(await screen.findByTestId('billing-error')).toHaveAttribute('data-code', 'service_unavailable');
    expect(screen.getByTestId('billing-retry')).toBeInTheDocument();
  });
});

describe('a read-only salon', () => {
  it('disables staff changes on the staff screen and says why', async () => {
    vi.stubGlobal('fetch', async (url: string) => (url.endsWith('/principal') ? Response.json(owner)
      : Response.json({ items: [{ id: 'm', role: 'MANAGER', email: 'manager@alpha.test', version: 0, active: true }], total: 1 })));
    renderWithMessages(<AccountScreen kind="staff" initialPrincipal={owner} timeZone="Asia/Colombo" view="staff" readOnly />);
    expect(await screen.findByTestId('account-read-only')).toHaveTextContent('read-only');
    await waitFor(() => expect(screen.getByTestId('account-staff-remove')).toBeDisabled());
    expect(screen.getByTestId('account-staff-create')).toBeDisabled();
    expect(screen.getByTestId('account-staff-role')).toBeDisabled();
  });
});
