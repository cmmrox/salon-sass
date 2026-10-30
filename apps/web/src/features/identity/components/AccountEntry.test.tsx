import { screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { renderWithMessages } from '@test/render';
import { AccountScreen } from './AccountScreen';

const navigation = vi.hoisted(() => ({ replace: vi.fn(), refresh: vi.fn() }));
vi.mock('next/navigation', () => ({ useRouter: () => navigation }));
afterEach(() => { vi.unstubAllGlobals(); vi.clearAllMocks(); });
const principal = { id: 'owner', kind: 'staff', role: 'OWNER', scope: 'salon', mfaEnabled: true, permissions: ['session.manage', 'password.change'] } as never;

it('shows a server-confirmed member their account at once; only the device list is pending (CR-013)', async () => {
  let release: ((response: Response) => void) | undefined;
  vi.stubGlobal('navigator', { locks: { request: (_name: string, work: () => Promise<unknown>) => work() } });
  vi.stubGlobal('BroadcastChannel', undefined);
  vi.stubGlobal('fetch', async (url: string) => url.endsWith('/principal') ? Response.json(principal) : new Promise<Response>((resolve) => { release = resolve; }));
  renderWithMessages(<AccountScreen kind="staff" initialPrincipal={principal} timeZone="Asia/Colombo" />);

  expect(screen.getByTestId('account-role')).toBeInTheDocument();
  expect(screen.getByTestId('account-password-save')).toBeInTheDocument();
  expect(screen.getByTestId('account-list-loading')).toBeInTheDocument();
  expect(screen.queryByTestId('account-loading')).not.toBeInTheDocument();
  expect(screen.queryByText('Loading your account…')).not.toBeInTheDocument();
  expect(screen.queryByTestId('account-browser-support')).not.toBeInTheDocument();
  expect(screen.queryByTestId('account-devices-empty')).not.toBeInTheDocument();

  await waitFor(() => expect(release).toBeDefined());
  release!(Response.json({ items: [], page: 0, size: 10, total: 0 }));
  await waitFor(() => expect(screen.getByTestId('account-devices-empty')).toBeInTheDocument());
  expect(screen.queryByTestId('account-list-loading')).not.toBeInTheDocument();
});

it('still waits for the principal when the server could not confirm one', () => {
  vi.stubGlobal('navigator', { locks: { request: (_name: string, work: () => Promise<unknown>) => work() } });
  vi.stubGlobal('BroadcastChannel', undefined);
  vi.stubGlobal('fetch', () => new Promise<Response>(() => undefined));
  renderWithMessages(<AccountScreen kind="staff" initialPrincipal={null} timeZone="Asia/Colombo" />);
  expect(screen.getByTestId('account-loading')).toBeInTheDocument();
  expect(screen.queryByTestId('account-role')).not.toBeInTheDocument();
});
