import { fireEvent, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithMessages } from '@test/render';
import type { Principal } from '../types';
import { AccountScreen } from './AccountScreen';
import { messages } from '@test/render';
import { chooseOption } from '@test/select';
import { Check, ChoiceField, CodeField, PasswordField, TextField } from './Controls';

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
  ])('gives %s its declared handle, its label, its refusal and its value in a Field', (name, handle) => {
    renderWithMessages(<TextField name={name} label="Declared label" error defaultValue="retained" />);
    const input = screen.getByTestId(handle);
    expect(screen.getByLabelText('Declared label')).toBe(input);
    expect(screen.getByText('Declared label')).toHaveAttribute('for', name);
    expect(screen.getByText('Declared label')).toHaveAttribute('data-slot', 'field-label');
    expect(input.closest('[data-slot="field"]')).toHaveAttribute('data-invalid', 'true');
    expect(input).toHaveAttribute('id', name);
    expect(input).toHaveAttribute('name', name);
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(input).toHaveAttribute('aria-describedby', `${name}-error`);
    expect(screen.getByTestId(`${handle}-error`)).toHaveAttribute('id', `${name}-error`);
    expect(screen.getByTestId(`${handle}-error`)).toHaveAttribute('data-slot', 'field-error');
    // The page's Alert announces a refusal; the field's message is read with the field.
    expect(screen.getByTestId(`${handle}-error`)).not.toHaveAttribute('role');
    expect(input).toHaveValue('retained');
  });
  it('ties a field\'s help text to it', () => {
    renderWithMessages(<TextField name="identifier" label="Declared label" description="Declared help" />);
    expect(screen.getByTestId('account-identifier')).toHaveAccessibleDescription('Declared help');
    expect(screen.getByText('Declared help')).toHaveAttribute('data-slot', 'field-description');
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
    expect(checkbox.closest('[data-slot="field"]')).toHaveAttribute('data-orientation', 'horizontal');
    // The label is the 44px target: pressing it checks the box.
    fireEvent.click(screen.getByText('Declared checkbox'));
    expect(change).toHaveBeenCalledExactlyOnceWith(true);
  });
});

it('uses kebab-case handles in the real password form while sending camelCase API fields', async () => {
  const principal: Principal = { id: 'manager', staffId: 'manager', kind: 'staff', role: 'MANAGER', permissions: ['session.manage', 'password.change'], scope: 'salon', mfaEnabled: true, expiresAt: null };
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
  const principal: Principal = { id: 'owner', staffId: 'owner', kind: 'staff', role: 'OWNER', permissions: ['staff.list', 'staff.manage'], scope: 'salon', mfaEnabled: true, expiresAt: null };
  vi.stubGlobal('navigator', { locks: { request: (_name: string, work: () => Promise<unknown>) => work() } });
  vi.stubGlobal('BroadcastChannel', undefined);
  vi.stubGlobal('fetch', async (url: string) => Response.json(url.endsWith('/principal') ? principal : { items: [], total: 0 }));
  renderWithMessages(<AccountScreen kind="staff" initialPrincipal={principal} timeZone="Asia/Colombo" view="staff" />);
  for (const field of ['email', 'mobile', 'password'] as const) {
    const input = await screen.findByTestId(`account-staff-${field}`);
    expect(input).toHaveAttribute('name', `staff-${field}`);
    expect(screen.getByLabelText(messages.account[field === 'password' ? 'initialPassword' : field])).toBe(input);
  }
});

it('preserves the verification code handle and submitted field on the real MFA form', async () => {
  const principal: Principal = { id: 'staff', staffId: 'staff', kind: 'staff', role: 'RECEPTIONIST', permissions: ['session.manage', 'mfa.enroll'], scope: 'salon', mfaEnabled: false, expiresAt: null };
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
  expect(screen.getByLabelText(messages.account.code)).toBe(code);
  expect(code.closest('[data-input-otp-container]')!.querySelectorAll('[data-slot="input-otp-slot"]')).toHaveLength(6);
  fireEvent.change(code, { target: { value: '123456' } });
  fireEvent.submit(code.closest('form')!);
  await waitFor(() => expect(verified).toEqual([{ code: '123456' }]));
});

describe('the password field (Input Group)', () => {
  it('reveals and hides the password with its named, pressed toggle and keeps the value', () => {
    renderWithMessages(<PasswordField name="oldPassword" label="Declared password" defaultValue="Synthetic value" />);
    const input = screen.getByTestId('account-old-password');
    const toggle = screen.getByTestId('account-old-password-toggle');
    expect(input.closest('[data-slot="input-group"]')).toContainElement(toggle);
    expect(input).toHaveAttribute('type', 'password');
    expect(toggle).toHaveAccessibleName(messages.account.showPassword);
    expect(toggle).toHaveAttribute('aria-pressed', 'false');
    expect(toggle).toHaveAttribute('aria-controls', 'oldPassword');
    fireEvent.click(toggle);
    expect(input).toHaveAttribute('type', 'text');
    expect(toggle).toHaveAccessibleName(messages.account.hidePassword);
    expect(toggle).toHaveAttribute('aria-pressed', 'true');
    expect(input).toHaveValue('Synthetic value');
    fireEvent.click(toggle);
    expect(input).toHaveAttribute('type', 'password');
  });
});

describe('the authenticator code (Input OTP)', () => {
  it('is one labelled input of six digits drawn as six slots, refusing anything but digits', () => {
    const change = vi.fn();
    renderWithMessages(<CodeField label="Declared code" value="" onChange={change} description="Declared help" />);
    const input = screen.getByLabelText('Declared code');
    expect(input).toBe(screen.getByTestId('account-code'));
    expect(input).toHaveAttribute('maxlength', '6');
    expect(input).toHaveAttribute('inputmode', 'numeric');
    expect(input).toHaveAttribute('autocomplete', 'one-time-code');
    expect(input).toHaveAccessibleDescription('Declared help');
    expect(document.querySelectorAll('[data-slot="input-otp-slot"]')).toHaveLength(6);
    fireEvent.change(input, { target: { value: '12ab' } });
    expect(change).not.toHaveBeenCalled();
    fireEvent.change(input, { target: { value: '123456' } });
    expect(change).toHaveBeenCalledWith('123456');
  });
});

describe('a choice from a list (Select)', () => {
  it('is named by its label, opens from the keyboard, chooses with Enter and gives focus back to its trigger', async () => {
    const change = vi.fn();
    renderWithMessages(<ChoiceField id="role" label="Declared role" testId="account-staff-new-role" optionTestId="account-role-option" value="OWNER" options={[{ value: 'OWNER', label: 'Owner' }, { value: 'MANAGER', label: 'Manager' }]} onChange={change} />);
    const trigger = screen.getByTestId('account-staff-new-role');
    expect(trigger).toHaveAttribute('data-slot', 'select-trigger');
    expect(trigger).toHaveAttribute('role', 'combobox');
    expect(screen.getByLabelText('Declared role')).toBe(trigger);
    expect(trigger).toHaveTextContent('Owner');
    trigger.focus();
    await chooseOption(trigger, 'Manager');
    expect(change).toHaveBeenCalledExactlyOnceWith('MANAGER');
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
    await waitFor(() => expect(trigger).toHaveFocus());
  });
  it('identifies each option by its value', async () => {
    renderWithMessages(<ChoiceField id="role" label="Declared role" testId="account-staff-new-role" optionTestId="account-role-option" value="OWNER" options={[{ value: 'OWNER', label: 'Owner' }, { value: 'MANAGER', label: 'Manager' }]} onChange={() => {}} />);
    fireEvent.keyDown(screen.getByTestId('account-staff-new-role'), { key: 'ArrowDown' });
    expect(await screen.findByTestId('account-role-option-manager')).toHaveAttribute('role', 'option');
    expect(screen.getByTestId('account-role-option-owner')).toHaveAttribute('aria-selected', 'true');
  });
});
