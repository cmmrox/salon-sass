import { fireEvent, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithMessages } from '@test/render';
import { SignIn } from './SignIn';
const replace = vi.fn();
vi.mock('next/navigation', () => ({ useRouter: () => ({ replace, refresh: vi.fn() }) }));
afterEach(() => { vi.unstubAllGlobals(); replace.mockReset(); });
describe('account sign-in', () => {
  it('blocks credential changes with a clear explanation when safe tab locks are absent', async () => {
    vi.stubGlobal('navigator', {});
    renderWithMessages(<SignIn kind="staff" timeZone="Asia/Colombo" />);
    // Controls are disabled from the first render; the explanation follows once the browser is inspected.
    expect(screen.getByTestId('account-sign-in-submit')).toBeDisabled();
    expect(await screen.findByTestId('account-browser-support')).toHaveTextContent('browser');
    expect(screen.getByTestId('account-sign-in-submit')).toBeDisabled();
  });
  it('flashes no unsupported-browser warning before a supporting browser is inspected (CR-013)', async () => {
    vi.stubGlobal('navigator', { locks: { request: (_name: string, work: () => Promise<unknown>) => work() } });
    renderWithMessages(<SignIn kind="staff" timeZone="Asia/Colombo" />);
    expect(screen.queryByTestId('account-browser-support')).not.toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId('account-sign-in-submit')).toBeEnabled());
    expect(screen.queryByTestId('account-browser-support')).not.toBeInTheDocument();
  });
  it('shows field feedback for wrong credentials and clears the password', async () => {
    vi.stubGlobal('navigator', { locks: { request: (_name: string, work: () => Promise<unknown>) => work() } });
    vi.stubGlobal('fetch', async () => Response.json({ code: 'invalid_credentials', errors: [{ field: 'identifier' }] }, { status: 401 }));
    renderWithMessages(<SignIn kind="staff" timeZone="Asia/Colombo" />);
    await waitFor(() => expect(screen.getByTestId('account-sign-in-submit')).toBeEnabled());
    fireEvent.change(screen.getByTestId('account-identifier'), { target: { value: 'owner@alpha.test' } });
    fireEvent.change(screen.getByTestId('account-password'), { target: { value: 'private password value' } });
    fireEvent.submit(screen.getByTestId('account-sign-in-submit').closest('form')!);
    await waitFor(() => expect(screen.getByTestId('account-error')).toHaveTextContent('did not match'));
    expect(screen.getByTestId('account-password')).toHaveValue('');
    expect(screen.getByTestId('account-identifier')).toHaveValue('owner@alpha.test');
    expect(screen.getByTestId('account-identifier')).toHaveAttribute('aria-invalid', 'true');
    expect(replace).not.toHaveBeenCalled();
  });
});

it('offers staff password and authenticator steps without customer OTP controls', async () => {
  vi.stubGlobal('navigator', { locks: { request: (_name: string, work: () => Promise<unknown>) => work() } });
  vi.stubGlobal('BroadcastChannel', undefined);
  const calls: string[] = [];
  vi.stubGlobal('fetch', async (url: string) => {
    calls.push(url);
    return Response.json(url.endsWith('/sign-in') ? { state: 'verify_mfa', expiresAt: '2026-01-01T00:05:00Z' } : { ok: true });
  });
  renderWithMessages(<SignIn kind="staff" timeZone="Asia/Colombo" />);
  await waitFor(() => expect(screen.getByTestId('account-sign-in-submit')).toBeEnabled());
  expect(screen.queryByTestId('account-mobile')).not.toBeInTheDocument();
  expect(screen.queryByTestId('account-channel')).not.toBeInTheDocument();
  fireEvent.change(screen.getByTestId('account-identifier'), { target: { value: 'owner@alpha.test' } });
  fireEvent.change(screen.getByTestId('account-password'), { target: { value: 'private test password' } });
  fireEvent.submit(screen.getByTestId('account-sign-in-submit').closest('form')!);
  await screen.findByTestId('account-code');
  expect(screen.queryByTestId('account-resend')).not.toBeInTheDocument();
  expect(replace).not.toHaveBeenCalled();
  await waitFor(() => expect(screen.getByTestId('account-sign-in-submit')).toBeEnabled());
  fireEvent.change(screen.getByTestId('account-code'), { target: { value: '123456' } });
  fireEvent.submit(screen.getByTestId('account-sign-in-submit').closest('form')!);
  await waitFor(() => expect(replace).toHaveBeenCalledWith('/workspace/account'));
  expect(calls).toEqual(['/identity-web/staff/sign-in', '/identity-web/staff/mfa-verify']);
  expect(screen.getByTestId('account-identifier')).toBeInTheDocument();
  expect(screen.queryByTestId('account-code')).not.toBeInTheDocument();
});

it('reveals the password only on request and offers administrator guidance without a request', async () => {
  vi.stubGlobal('navigator', { locks: { request: (_name: string, work: () => Promise<unknown>) => work() } });
  const fetch = vi.fn(); vi.stubGlobal('fetch', fetch);
  renderWithMessages(<SignIn kind="staff" timeZone="Asia/Colombo" />);
  const password = screen.getByTestId('account-password');
  expect(password).toHaveAttribute('type', 'password');
  fireEvent.change(password, { target: { value: 'Synthetic local password' } });
  fireEvent.click(screen.getByRole('button', { name: 'Show password' }));
  expect(password).toHaveAttribute('type', 'text');
  expect(password).toHaveValue('Synthetic local password');
  fireEvent.click(screen.getByRole('button', { name: 'Hide password' }));
  expect(password).toHaveAttribute('type', 'password');
  const recovery = screen.getByRole('button', { name: 'Forgot password?' });
  expect(recovery).toHaveAttribute('aria-expanded', 'false');
  fireEvent.click(recovery);
  expect(recovery).toHaveAttribute('aria-expanded', 'true');
  expect(screen.getByTestId('account-password-help')).toHaveTextContent('contact your salon administrator');
  expect(fetch).not.toHaveBeenCalled();
  fireEvent.click(recovery);
  expect(screen.queryByTestId('account-password-help')).not.toBeInTheDocument();
});
