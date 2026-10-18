import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import type { AnchorHTMLAttributes } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithMessages } from '@/test/render';
import { setViewportWidth } from '@/test/viewport';
import { ConsoleShell } from './ConsoleShell';
import { ConsoleSignIn } from './ConsoleSignIn';
import { PlanDirectory } from './PlanScreens';
import { SalonDirectory } from './SalonScreens';

vi.mock('next/navigation', () => ({ useRouter: () => ({ replace: vi.fn(), refresh: vi.fn() }) }));
vi.mock('next/link', () => ({ default: (props: AnchorHTMLAttributes<HTMLAnchorElement>) => <a {...props} /> }));

/**
 * docs/engineering/web.md, "Styling", rule 1 (CR-016, CR-017) for the operator console: every job
 * is the shadcn/ui component the component map names, shown by its `data-slot`.
 */
const slot = (testId: string) => screen.getByTestId(testId).getAttribute('data-slot');
const PLAN = { id: 'p1', key: 'fixture-basic', name: 'Synthetic Basic', staffSeats: 5, branches: 1, messageAllowance: 0, customDomain: false, dedicatedApp: true, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' };

beforeEach(() => {
  vi.stubGlobal('navigator', { locks: { request: (_name: string, work: () => Promise<unknown>) => work() } });
  vi.stubGlobal('BroadcastChannel', undefined);
  // jsdom has no ResizeObserver; Radix Checkbox measures itself with one.
  vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} });
});
afterEach(() => vi.unstubAllGlobals());

describe('the console screens render the shadcn/ui components of the component map', () => {
  it('frame: Sidebar rail from 900px with its menu buttons and inset, and its Sheet below 900px', () => {
    setViewportWidth(1280);
    const { unmount } = renderWithMessages(<ConsoleShell permissions={['operator.salon.read', 'operator.plan.read']} view="plans"><p /></ConsoleShell>);
    expect(slot('console-shell')).toBe('sidebar-wrapper');
    expect(slot('console-main')).toBe('sidebar-inset');
    expect(slot('console-nav-salons')).toBe('sidebar-menu-button');
    expect(slot('console-nav-plans')).toBe('sidebar-menu-button');
    expect(slot('console-sign-out')).toBe('button');
    unmount();
    setViewportWidth(360);
    renderWithMessages(<ConsoleShell permissions={['operator.salon.read', 'operator.plan.read']} view="plans"><p /></ConsoleShell>);
    fireEvent.click(screen.getByTestId('workspace-menu'));
    expect(screen.getByRole('dialog')).toHaveAttribute('data-slot', 'sidebar');
  });

  it('sign-in: Card and CardTitle, Field with Input and Input Group, Input OTP for the code', async () => {
    vi.stubGlobal('fetch', async () => Response.json({ state: 'verify_mfa', expiresAt: '2026-01-01T00:05:00Z' }));
    renderWithMessages(<ConsoleSignIn />);
    expect(slot('console-sign-in-card')).toBe('card');
    expect(slot('console-sign-in-title')).toBe('card-title');
    expect(slot('console-email')).toBe('input');
    expect(slot('console-password')).toBe('input-group-control');
    await waitFor(() => expect(screen.getByTestId('console-sign-in-submit')).toBeEnabled());
    fireEvent.submit(screen.getByTestId('console-sign-in-submit').closest('form')!);
    expect(await screen.findByTestId('console-code')).toHaveAttribute('data-slot', 'input-otp');
  });

  it('directories: Item rows with Badges, Pagination, Empty, Skeleton, and the create Sheet with Field, Select and Checkbox', async () => {
    vi.stubGlobal('fetch', async (url: string) => Response.json(url.startsWith('/operator-web/plans') ? { items: [PLAN], page: 0, size: 20, total: 1 } : { items: [], page: 0, size: 20, total: 0 }));
    const { unmount } = renderWithMessages(<PlanDirectory />);
    expect(screen.getByTestId('console-loading').querySelectorAll('[data-slot="skeleton"]')).toHaveLength(3);
    expect(await screen.findByTestId('console-plan-row')).toHaveAttribute('data-slot', 'item');
    expect(slot('console-plans-list')).toBe('item-group');
    expect(screen.getByTestId('console-plan-row').querySelectorAll('[data-slot="badge"]')).toHaveLength(3);
    expect(slot('console-plans-pagination')).toBe('pagination');
    fireEvent.click(screen.getByTestId('console-create-plan'));
    const planSheet = await screen.findByTestId('console-create-plan-sheet');
    expect(planSheet).toHaveAttribute('data-slot', 'sheet-content');
    expect(within(planSheet).getByTestId('console-custom-domain')).toHaveAttribute('data-slot', 'checkbox');
    unmount();

    renderWithMessages(<SalonDirectory platformDomain="localhost" />);
    expect(await screen.findByTestId('console-salons-empty')).toHaveAttribute('data-slot', 'empty');
    expect(slot('console-salons-search')).toBe('input-group-control');
    fireEvent.click(screen.getByTestId('console-create-salon'));
    const salonSheet = await screen.findByTestId('console-create-salon-sheet');
    expect(within(salonSheet).getByTestId('console-plan-id')).toHaveAttribute('data-slot', 'select-trigger');
    expect(within(salonSheet).getByTestId('console-default-language')).toHaveAttribute('data-slot', 'select-trigger');
    expect(within(salonSheet).getByTestId('console-address')).toHaveAttribute('data-slot', 'textarea');
    // Profile, contact, subscription (F03-S02) and first Owner.
    expect(salonSheet.querySelectorAll('[data-slot="field-set"]')).toHaveLength(4);
    expect(within(salonSheet).getByTestId('console-payment-method')).toHaveAttribute('data-slot', 'select-trigger');
    expect(within(salonSheet).getByTestId('console-monthly-amount')).toHaveAttribute('data-slot', 'input');
  });
});
