import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { renderWithMessages } from '@/test/render';
import type { Principal } from '@/lib/identity/server';
import { AccountScreen } from './AccountScreen';
import { Confirmation } from './Confirmation';
import { SignIn } from './SignIn';
import { StaffSignInFrame } from './StaffSignInFrame';

// F02-S04 AC-12 (CR-016): the staff screens are built from shadcn/ui components, never a hand-built
// look-alike. Each check reads the `data-slot` a shadcn/ui component renders on its element.
vi.mock('next/navigation', () => ({ useRouter: () => ({ replace: vi.fn(), refresh: vi.fn() }) }));
afterEach(() => vi.unstubAllGlobals());
const locks = () => vi.stubGlobal('navigator', { locks: { request: (_name: string, work: () => Promise<unknown>) => work() } });
const slot = (element: Element | null) => element?.closest('[data-slot]')?.getAttribute('data-slot');

it('writes no raw control, table or dialog element in an account screen; each comes from components/ui', () => {
  const files = readdirSync(__dirname).filter((name) => name.endsWith('.tsx') && !name.includes('.test.'));
  const raw = files.flatMap((name) => [...readFileSync(join(__dirname, name), 'utf8').matchAll(/<(button|input|select|textarea|table|dialog)\b/g)].map((match) => `${name}: <${match[1]}>`));
  expect(files.length).toBeGreaterThan(5);
  expect(raw).toEqual([]);
});

it('builds staff sign-in from Card, Label, Input, Button and Alert', async () => {
  locks();
  vi.stubGlobal('fetch', async () => Response.json({ code: 'invalid_credentials', errors: [] }, { status: 401 }));
  renderWithMessages(<SignIn kind="staff" timeZone="Asia/Colombo" />);
  expect(screen.getByTestId('staff-sign-in-card')).toHaveAttribute('data-slot', 'card');
  for (const id of ['account-identifier', 'account-password']) {
    expect(screen.getByTestId(id)).toHaveAttribute('data-slot', 'input');
    expect(screen.getByTestId(id).closest('label')).toHaveAttribute('data-slot', 'label');
  }
  for (const id of ['account-password-toggle', 'account-forgot-password', 'account-sign-in-submit']) expect(screen.getByTestId(id)).toHaveAttribute('data-slot', 'button');
  fireEvent.click(screen.getByTestId('account-forgot-password'));
  expect(screen.getByTestId('account-password-help')).toHaveAttribute('data-slot', 'alert');
  await waitFor(() => expect(screen.getByTestId('account-sign-in-submit')).toBeEnabled());
  fireEvent.change(screen.getByTestId('account-identifier'), { target: { value: 'owner@alpha.test' } });
  fireEvent.change(screen.getByTestId('account-password'), { target: { value: 'synthetic' } });
  fireEvent.submit(screen.getByTestId('account-sign-in-submit').closest('form')!);
  expect(await screen.findByTestId('account-error')).toHaveAttribute('data-slot', 'alert');
});

it('draws the sign-in salon initial as an Avatar', () => {
  renderWithMessages(<StaffSignInFrame salon={{ name: 'Alpha Salon' } as never}><p>child</p></StaffSignInFrame>);
  expect(screen.getByTestId('staff-sign-in-initial')).toHaveAttribute('data-slot', 'avatar');
  expect(screen.getByTestId('staff-sign-in-initial')).toHaveTextContent('A');
});

it('opens the confirmation as a Sheet', () => {
  renderWithMessages(<Confirmation title="Confirm" onCancel={() => {}}><p>body</p></Confirmation>);
  expect(screen.getByTestId('account-confirmation')).toHaveAttribute('data-slot', 'sheet-content');
});

it('builds the account, devices and MFA enrolment from Card, Input, Button and Skeleton', async () => {
  const principal: Principal = { id: 'r', staffId: 'r', kind: 'staff', role: 'RECEPTIONIST', permissions: ['session.manage', 'mfa.enroll'], scope: 'salon', mfaEnabled: false };
  locks(); vi.stubGlobal('BroadcastChannel', undefined);
  let release: () => void = () => {};
  const pending = new Promise<void>((resolve) => { release = resolve; });
  vi.stubGlobal('fetch', async (url: string) => {
    if (url.endsWith('/principal')) return Response.json(principal);
    if (url.endsWith('/mfa-enroll')) return Response.json({ secret: 'synthetic key', otpauthUri: 'synthetic uri' });
    if (url.includes('sessions')) { await pending; return Response.json({ items: [{ id: 'd1', deviceLabel: 'This browser', current: true }], total: 1 }); }
    return Response.json({ ok: true });
  });
  renderWithMessages(<AccountScreen kind="staff" initialPrincipal={principal} timeZone="Asia/Colombo" />);
  const loading = await screen.findByTestId('account-list-loading');
  expect(loading.querySelectorAll('[data-slot="skeleton"]')).toHaveLength(3);
  expect(slot(screen.getByTestId('account-old-password'))).toBe('input');
  expect(screen.getByTestId('account-old-password').closest('section')).toHaveAttribute('data-slot', 'card');
  release();
  expect(await screen.findByTestId('account-device')).toHaveAttribute('data-slot', 'card');
  for (const id of ['account-device-revoke', 'account-revoke-all', 'account-page-next', 'account-logout', 'account-password-save', 'account-mfa-start']) expect(screen.getByTestId(id)).toHaveAttribute('data-slot', 'button');
  fireEvent.click(screen.getByTestId('account-mfa-start'));
  expect(slot(await screen.findByTestId('account-code'))).toBe('input');
});

it('builds the staff list from Card, NativeSelect, Checkbox and Button', async () => {
  const principal: Principal = { id: 'o', staffId: 'o', kind: 'staff', role: 'OWNER', permissions: ['staff.list', 'staff.manage'], scope: 'salon', mfaEnabled: true };
  locks(); vi.stubGlobal('BroadcastChannel', undefined);
  vi.stubGlobal('fetch', async (url: string) => Response.json(url.endsWith('/principal') ? principal : { items: [{ id: 's1', email: 'pro@alpha.test', role: 'PROFESSIONAL', active: true, version: 1, professionalGrant: false }], total: 1 }));
  renderWithMessages(<AccountScreen kind="staff" initialPrincipal={principal} timeZone="Asia/Colombo" view="staff" />);
  expect(await screen.findByTestId('account-staff-row')).toHaveAttribute('data-slot', 'card');
  expect(screen.getByTestId('account-staff-role')).toHaveAttribute('data-slot', 'native-select');
  expect(screen.getByTestId('account-staff-new-role')).toHaveAttribute('data-slot', 'native-select');
  expect(screen.getByTestId('account-professional-grant')).toHaveAttribute('data-slot', 'checkbox');
  for (const id of ['account-staff-remove', 'account-staff-create']) expect(screen.getByTestId(id)).toHaveAttribute('data-slot', 'button');
});
