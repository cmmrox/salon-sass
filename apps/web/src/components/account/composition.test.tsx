import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { renderWithMessages } from '@/test/render';
import type { Principal } from '@/lib/identity/server';
import { AccountScreen } from './AccountScreen';
import { Confirmation } from './Confirmation';
import { Denied } from './Controls';
import { SignIn } from './SignIn';
import { StaffSignInFrame } from './StaffSignInFrame';

// F02-S04 AC-12 and AC-14 (CR-016, CR-017): the staff screens use the shadcn/ui component the
// component map of docs/engineering/web.md, "Styling", names for each job. Each check reads the
// `data-slot` the shadcn/ui component renders. The source rule (no raw control, no Native Select)
// is components/composition.test.tsx, over every product component.
vi.mock('next/navigation', () => ({ useRouter: () => ({ replace: vi.fn(), refresh: vi.fn() }) }));
afterEach(() => vi.unstubAllGlobals());
const locks = () => vi.stubGlobal('navigator', { locks: { request: (_name: string, work: () => Promise<unknown>) => work() } });
const slot = (element: Element | null) => element?.getAttribute('data-slot');
const field = (element: Element | null) => element?.closest('[data-slot="field"]');

it('builds staff sign-in from Card, Field, Input, Input Group, Button and Alert', async () => {
  locks();
  vi.stubGlobal('fetch', async () => Response.json({ code: 'invalid_credentials', errors: [{ field: 'identifier' }] }, { status: 401 }));
  renderWithMessages(<SignIn kind="staff" timeZone="Asia/Colombo" />);
  expect(slot(screen.getByTestId('staff-sign-in-card'))).toBe('card');
  expect(slot(screen.getByTestId('account-identifier'))).toBe('input');
  expect(field(screen.getByTestId('account-identifier'))).not.toBeNull();
  expect(field(screen.getByTestId('account-identifier'))!.querySelector('[data-slot="field-label"]')).toHaveAttribute('for', 'identifier');
  expect(slot(screen.getByTestId('account-password'))).toBe('input-group-control');
  expect(screen.getByTestId('account-password').closest('[data-slot="input-group"]')).toContainElement(screen.getByTestId('account-password-toggle'));
  expect(screen.getByTestId('account-password-toggle').closest('[data-slot="input-group-addon"]')).not.toBeNull();
  expect(screen.getByTestId('account-password').closest('[data-slot="field-group"]')).not.toBeNull();
  for (const id of ['account-password-toggle', 'account-forgot-password', 'account-sign-in-submit']) expect(slot(screen.getByTestId(id))).toBe('button');
  fireEvent.click(screen.getByTestId('account-forgot-password'));
  expect(slot(screen.getByTestId('account-password-help'))).toBe('alert');
  await waitFor(() => expect(screen.getByTestId('account-sign-in-submit')).toBeEnabled());
  fireEvent.change(screen.getByTestId('account-identifier'), { target: { value: 'owner@alpha.test' } });
  fireEvent.change(screen.getByTestId('account-password'), { target: { value: 'synthetic' } });
  fireEvent.submit(screen.getByTestId('account-sign-in-submit').closest('form')!);
  expect(slot(await screen.findByTestId('account-error'))).toBe('alert');
  expect(slot(screen.getByTestId('account-identifier-error'))).toBe('field-error');
});

it('shows a Spinner in the busy sign-in action and an Alert for the retry wait', async () => {
  locks();
  let answer: (response: Response) => void = () => {};
  vi.stubGlobal('fetch', () => new Promise<Response>((resolve) => { answer = resolve; }));
  renderWithMessages(<SignIn kind="staff" timeZone="Asia/Colombo" />);
  await waitFor(() => expect(screen.getByTestId('account-sign-in-submit')).toBeEnabled());
  fireEvent.change(screen.getByTestId('account-identifier'), { target: { value: 'owner@alpha.test' } });
  fireEvent.change(screen.getByTestId('account-password'), { target: { value: 'synthetic' } });
  fireEvent.submit(screen.getByTestId('account-sign-in-submit').closest('form')!);
  await waitFor(() => expect(screen.getByTestId('account-sign-in-submit').querySelector('[data-slot="spinner"]')).not.toBeNull());
  expect(screen.getByTestId('account-sign-in-submit')).toHaveAttribute('aria-busy', 'true');
  answer(Response.json({ code: 'rate_limited', errors: [], retryAfter: 30 }, { status: 429 }));
  expect(slot(await screen.findByTestId('account-retry-wait'))).toBe('alert');
  expect(screen.getByTestId('account-sign-in-submit').querySelector('[data-slot="spinner"]')).toBeNull();
});

it('takes the sign-in authenticator code in an Input OTP of six slots, with the setup in Fields', async () => {
  locks(); vi.stubGlobal('BroadcastChannel', undefined);
  vi.stubGlobal('fetch', async (url: string) => Response.json(url.endsWith('/sign-in') ? { state: 'enroll_mfa', expiresAt: '2026-01-01T00:05:00Z' } : { secret: 'SYNTHETICKEY', otpauthUri: 'synthetic uri' }));
  renderWithMessages(<SignIn kind="staff" timeZone="Asia/Colombo" />);
  await waitFor(() => expect(screen.getByTestId('account-sign-in-submit')).toBeEnabled());
  fireEvent.change(screen.getByTestId('account-identifier'), { target: { value: 'owner@alpha.test' } });
  fireEvent.change(screen.getByTestId('account-password'), { target: { value: 'synthetic' } });
  fireEvent.submit(screen.getByTestId('account-sign-in-submit').closest('form')!);
  const code = await screen.findByTestId('account-code');
  expect(slot(code)).toBe('input-otp');
  expect(code.closest('[data-input-otp-container]')!.querySelectorAll('[data-slot="input-otp-slot"]')).toHaveLength(6);
  expect(await screen.findByTestId('account-setup-key')).toHaveTextContent('SYNTHETICKEY');
  expect(field(screen.getByTestId('account-setup-key'))).not.toBeNull();
  expect(slot(screen.getByTestId('account-mfa-notice'))).toBe('alert');
});

it('draws the sign-in salon initial as an Avatar', () => {
  renderWithMessages(<StaffSignInFrame salon={{ name: 'Alpha Salon' } as never}><p>child</p></StaffSignInFrame>);
  expect(slot(screen.getByTestId('staff-sign-in-initial'))).toBe('avatar');
  expect(screen.getByTestId('staff-sign-in-initial')).toHaveTextContent('A');
});

it('opens the confirmation as a Sheet', () => {
  renderWithMessages(<Confirmation title="Confirm" onCancel={() => {}}><p>body</p></Confirmation>);
  expect(slot(screen.getByTestId('account-confirmation'))).toBe('sheet-content');
});

it('builds the account from Card, Badge, Input Group, Input OTP, Item, Pagination and Skeleton', async () => {
  const principal: Principal = { id: 'r', staffId: 'r', kind: 'staff', role: 'RECEPTIONIST', permissions: ['session.manage', 'mfa.enroll'], scope: 'salon', mfaEnabled: false };
  locks(); vi.stubGlobal('BroadcastChannel', undefined);
  let release: () => void = () => {};
  const pending = new Promise<void>((resolve) => { release = resolve; });
  vi.stubGlobal('fetch', async (url: string) => {
    if (url.endsWith('/principal')) return Response.json(principal);
    if (url.endsWith('/mfa-enroll')) return Response.json({ secret: 'synthetic key', otpauthUri: 'synthetic uri' });
    if (url.includes('sessions')) { await pending; return Response.json({ items: [{ id: 'd1', deviceLabel: 'This browser', current: true, createdAt: '2026-01-01T00:00:00Z' }], total: 1 }); }
    return Response.json({ ok: true });
  });
  renderWithMessages(<AccountScreen kind="staff" initialPrincipal={principal} timeZone="Asia/Colombo" />);
  const loading = await screen.findByTestId('account-list-loading');
  expect(loading.querySelectorAll('[data-slot="skeleton"]')).toHaveLength(3);
  expect(slot(screen.getByTestId('account-role'))).toBe('badge');
  for (const id of ['account-old-password', 'account-new-password']) {
    expect(slot(screen.getByTestId(id))).toBe('input-group-control');
    expect(slot(screen.getByTestId(`${id}-toggle`))).toBe('button');
  }
  expect(screen.getByTestId('account-old-password').closest('section')).toHaveAttribute('data-slot', 'card');
  release();
  const device = await screen.findByTestId('account-device');
  expect(slot(device)).toBe('item');
  expect(device).toHaveAttribute('role', 'listitem');
  expect(slot(device.closest('[role="list"]'))).toBe('item-group');
  expect(within(device).getByText('This browser').closest('[data-slot="item-title"]')).not.toBeNull();
  expect(slot(screen.getByTestId('account-device-current'))).toBe('badge');
  expect(screen.getByTestId('account-device-revoke').closest('[data-slot="item-actions"]')).not.toBeNull();
  expect(slot(screen.getByTestId('account-pagination'))).toBe('pagination');
  expect(slot(screen.getByTestId('account-page'))).toBe('pagination-current');
  expect(screen.getByTestId('account-page')).toHaveAttribute('aria-current', 'page');
  expect(slot(screen.getByTestId('account-page-previous'))).toBe('pagination-previous');
  expect(slot(screen.getByTestId('account-page-next'))).toBe('pagination-next');
  for (const id of ['account-device-revoke', 'account-revoke-all', 'account-logout', 'account-password-save', 'account-mfa-start']) expect(slot(screen.getByTestId(id))).toBe('button');
  fireEvent.click(screen.getByTestId('account-mfa-start'));
  expect(slot(await screen.findByTestId('account-code'))).toBe('input-otp');
  expect(slot(screen.getByTestId('account-mfa-notice'))).toBe('alert');
});

it('draws the server\'s denied page as an Alert too', () => {
  renderWithMessages(<Denied />);
  expect(slot(screen.getByTestId('account-denied'))).toBe('alert');
});

it('shows an empty list as Empty, and the denied page as an Alert', async () => {
  const principal: Principal = { id: 'm', staffId: 'm', kind: 'staff', role: 'MANAGER', permissions: ['session.manage', 'audit.list'], scope: 'salon', mfaEnabled: true };
  locks(); vi.stubGlobal('BroadcastChannel', undefined);
  vi.stubGlobal('fetch', async (url: string) => Response.json(url.endsWith('/principal') ? principal : { items: [], total: 0 }));
  const { unmount } = renderWithMessages(<AccountScreen kind="staff" initialPrincipal={principal} timeZone="Asia/Colombo" view="audit" />);
  expect(slot(await screen.findByTestId('account-audit-empty'))).toBe('empty');
  unmount();
  renderWithMessages(<AccountScreen kind="staff" initialPrincipal={principal} timeZone="Asia/Colombo" view="staff" />);
  const denied = screen.getByTestId('account-denied');
  expect(slot(denied)).toBe('alert');
  expect(denied).toHaveAttribute('role', 'alert');
});

it('builds the staff list from Item, Badge, Select, a horizontal Field with Checkbox, Separator and Button', async () => {
  const principal: Principal = { id: 'o', staffId: 'o', kind: 'staff', role: 'OWNER', permissions: ['staff.list', 'staff.manage'], scope: 'salon', mfaEnabled: true };
  locks(); vi.stubGlobal('BroadcastChannel', undefined);
  vi.stubGlobal('fetch', async (url: string) => Response.json(url.endsWith('/principal') ? principal : { items: [{ id: 's1', email: 'pro@alpha.test', role: 'PROFESSIONAL', active: true, version: 1, professionalGrant: false }], total: 1 }));
  renderWithMessages(<AccountScreen kind="staff" initialPrincipal={principal} timeZone="Asia/Colombo" view="staff" />);
  const row = await screen.findByTestId('account-staff-row');
  expect(slot(row)).toBe('item');
  expect(slot(screen.getByTestId('account-staff-role-label'))).toBe('badge');
  expect(slot(screen.getByTestId('account-staff-state'))).toBe('badge');
  for (const id of ['account-staff-role', 'account-staff-new-role']) {
    expect(slot(screen.getByTestId(id))).toBe('select-trigger');
    expect(field(screen.getByTestId(id))).not.toBeNull();
  }
  expect(slot(screen.getByTestId('account-professional-grant'))).toBe('checkbox');
  expect(field(screen.getByTestId('account-professional-grant'))).toHaveAttribute('data-orientation', 'horizontal');
  expect(slot(screen.getByTestId('account-staff-separator'))).toBe('separator');
  for (const id of ['account-staff-email', 'account-staff-mobile', 'account-staff-password']) expect(field(screen.getByTestId(id))).not.toBeNull();
  for (const id of ['account-staff-remove', 'account-staff-create']) expect(slot(screen.getByTestId(id))).toBe('button');
});
