import { fireEvent, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithMessages } from '@/test/render';
import type { Principal } from '@/lib/identity/server';
import { AccountScreen } from './AccountScreen';
import { Check, Field } from './Controls';

vi.mock('next/navigation', () => ({ useRouter: () => ({ replace: vi.fn(), refresh: vi.fn() }) }));
afterEach(() => vi.unstubAllGlobals());
describe('declared account control handles', () => {
  it.each([
    ['oldPassword', 'account-old-password'],
    ['newPassword', 'account-new-password'],
    ['preferredLanguage', 'account-preferred-language'],
    ['identifier', 'account-identifier'],
    ['password', 'account-password'],
    ['code', 'account-code'],
    ['staff-email', 'account-staff-email'],
    ['staff-mobile', 'account-staff-mobile'],
    ['staff-password', 'account-staff-password'],
  ])('gives %s its declared handle without changing native field/label/error identity', (name, handle) => {
    renderWithMessages(<Field name={name} label="Declared label" error defaultValue="retained" />);
    const input = screen.getByTestId(handle);
    expect(screen.getByLabelText(/^Declared label/)).toBe(input);
    expect(input.closest('label')).toHaveAttribute('for', name);
    expect(input).toHaveAttribute('id', name);
    expect(input).toHaveAttribute('name', name);
    expect(input).toHaveAttribute('aria-describedby', `${name}-error`);
    expect(screen.getByTestId(`${handle}-error`)).toHaveAttribute('id', `${name}-error`);
    expect(input).toHaveValue('retained');
  });
  it.each([
    ['marketingConsent', 'account-marketing-consent'],
    ['notifications-sms', 'account-notifications-sms'],
    ['professional-grant', 'account-professional-grant'],
  ])('gives %s its declared checkbox handle and preserves its checked callback', (name, handle) => {
    const change = vi.fn();
    renderWithMessages(<Check name={name} label="Declared checkbox" checked={false} onChange={change} />);
    const checkbox = screen.getByTestId(handle);
    expect(screen.getByLabelText('Declared checkbox')).toBe(checkbox);
    fireEvent.click(checkbox);
    expect(change).toHaveBeenCalledExactlyOnceWith(true);
  });
});

it('uses kebab-case handles in the real password form while sending camelCase API fields', async () => {
  const principal: Principal = { id: 'manager', staffId: 'manager', kind: 'staff', role: 'MANAGER', permissions: ['session.manage', 'password.change'], scope: 'salon', mfaEnabled: true };
  const bodies: unknown[] = [];
  vi.stubGlobal('navigator', { locks: { request: (_name: string, work: () => Promise<unknown>) => work() } });
  vi.stubGlobal('BroadcastChannel', undefined);
  vi.stubGlobal('fetch', async (url: string, options?: RequestInit) => {
    if (url.endsWith('/principal')) return Response.json(principal);
    if (url.endsWith('/password')) { bodies.push(JSON.parse(options!.body as string)); return Response.json({ ok: true }); }
    return Response.json({ items: [], total: 0 });
  });
  renderWithMessages(<AccountScreen kind="staff" initialPrincipal={principal} timeZone="Asia/Colombo" />);
  const oldPassword = await screen.findByTestId('account-old-password');
  fireEvent.change(oldPassword, { target: { value: 'Old synthetic password' } });
  fireEvent.change(screen.getByTestId('account-new-password'), { target: { value: 'New synthetic password' } });
  fireEvent.submit(oldPassword.closest('form')!);
  await waitFor(() => expect(bodies).toEqual([{ oldPassword: 'Old synthetic password', newPassword: 'New synthetic password' }]));
  await waitFor(() => expect(oldPassword).toHaveValue(''));
  expect(screen.getByTestId('account-new-password')).toHaveValue('');
  expect(screen.getByTestId('account-mfa-enabled')).toBeVisible();
});

it('preserves staff creation handles on the actual Owner form', async () => {
  const principal: Principal = { id: 'owner', staffId: 'owner', kind: 'staff', role: 'OWNER', permissions: ['staff.list', 'staff.manage'], scope: 'salon', mfaEnabled: true };
  vi.stubGlobal('navigator', { locks: { request: (_name: string, work: () => Promise<unknown>) => work() } });
  vi.stubGlobal('BroadcastChannel', undefined);
  vi.stubGlobal('fetch', async (url: string) => Response.json(url.endsWith('/principal') ? principal : { items: [], total: 0 }));
  renderWithMessages(<AccountScreen kind="staff" initialPrincipal={principal} timeZone="Asia/Colombo" view="staff" />);
  for (const field of ['email', 'mobile', 'password']) {
    const input = await screen.findByTestId(`account-staff-${field}`);
    expect(input).toHaveAttribute('name', `staff-${field}`);
    expect(input.closest('label')).toHaveAttribute('for', `staff-${field}`);
  }
});

it('preserves the verification code handle and submitted field on the real MFA form', async () => {
  const principal: Principal = { id: 'staff', staffId: 'staff', kind: 'staff', role: 'RECEPTIONIST', permissions: ['session.manage', 'mfa.enroll'], scope: 'salon', mfaEnabled: false };
  const verified: unknown[] = [];
  vi.stubGlobal('navigator', { locks: { request: (_name: string, work: () => Promise<unknown>) => work() } });
  vi.stubGlobal('BroadcastChannel', undefined);
  vi.stubGlobal('fetch', async (url: string, options?: RequestInit) => {
    if (url.endsWith('/principal')) return Response.json(principal);
    if (url.endsWith('/mfa-enroll')) return Response.json({ secret: 'synthetic setup instructions', otpauthUri: 'synthetic enrollment instructions' });
    if (url.endsWith('/mfa-verify')) { verified.push(JSON.parse(options!.body as string)); return Response.json({ ok: true }); }
    return Response.json({ items: [], total: 0 });
  });
  renderWithMessages(<AccountScreen kind="staff" initialPrincipal={principal} timeZone="Asia/Colombo" />);
  fireEvent.click(await screen.findByTestId('account-mfa-start'));
  const code = await screen.findByTestId('account-code');
  expect(code).toHaveAttribute('name', 'code');
  expect(code.closest('label')).toHaveAttribute('for', 'code');
  fireEvent.change(code, { target: { value: '123456' } });
  fireEvent.submit(code.closest('form')!);
  await waitFor(() => expect(verified).toEqual([{ code: '123456' }]));
});
