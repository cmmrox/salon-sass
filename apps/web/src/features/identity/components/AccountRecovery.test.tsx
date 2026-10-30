import { act, cleanup, fireEvent, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import type { Principal } from '@/features/identity/server/identity-web';
import { messages, renderWithMessages } from '@test/render';
import { chooseOption } from '@test/select';
import { AccountScreen } from './AccountScreen';

const navigation = vi.hoisted(() => ({ replace: vi.fn(), refresh: vi.fn() }));
vi.mock('next/navigation', () => ({ useRouter: () => navigation }));
const owner: Principal = { id: 'owner', kind: 'staff', role: 'OWNER', staffId: 'owner', scope: 'salon', mfaEnabled: true, expiresAt: null, permissions: ['session.manage', 'staff.list', 'staff.manage', 'audit.list'] };
const receptionist: Principal = { ...owner, role: 'RECEPTIONIST', permissions: ['session.manage'] };
const person = { id: 'owner', role: 'OWNER', email: 'synthetic@alpha.test', version: 0, active: true };

beforeEach(() => {
  vi.stubGlobal('navigator', { locks: { request: (_name: string, work: () => Promise<unknown>) => work() } });
  vi.stubGlobal('BroadcastChannel', undefined);
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: function (this: HTMLDialogElement) { this.open = true; } });
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: function (this: HTMLDialogElement) { this.open = false; } });
});
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.clearAllMocks(); Reflect.deleteProperty(HTMLDialogElement.prototype, 'showModal'); Reflect.deleteProperty(HTMLDialogElement.prototype, 'close'); });

it.each([
  ['staff', 'revoke-all', 'account', 503, 'service_unavailable'],
  ['staff', 'staff-remove', 'staff', 409, 'conflict'],
] as const)('exposes failed %s %s confirmation and reloads without replaying it', async (kind, action, view, status, code) => {
  let mutations = 0; let reads = 0;
  vi.stubGlobal('fetch', async (url: string) => {
    if (url.endsWith(`/${action}`)) { mutations++; return Response.json({ code }, { status }); }
    if (url.endsWith('/principal')) return Response.json(owner);
    reads++; return Response.json({ items: view === 'staff' ? [person] : [], total: view === 'staff' ? 1 : 0 });
  });
  renderWithMessages(<AccountScreen kind={kind} initialPrincipal={owner} timeZone="Asia/Colombo" view={view} />);
  const trigger = await screen.findByTestId(`account-${action}`); trigger.focus(); fireEvent.click(trigger);
  expect(screen.getByRole('dialog')).toBeInTheDocument();
  fireEvent.click(screen.getByTestId('account-confirm-action'));
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  expect(screen.getByRole('alert')).toHaveTextContent(messages.account[code]);
  expect(trigger).toHaveFocus();
  const beforeRetry = reads;
  fireEvent.click(screen.getByTestId('account-retry'));
  await waitFor(() => expect(reads).toBeGreaterThan(beforeRetry));
  expect(mutations).toBe(1);
  expect(navigation.replace).not.toHaveBeenCalled();
  await waitFor(() => expect(trigger).toBeEnabled());
  fireEvent.click(trigger); expect(screen.getByRole('dialog')).toBeInTheDocument();
  expect(mutations).toBe(1);
});

it.each([owner, null])('refreshes the server frame after discovering changed staff permissions from %s', async (initialPrincipal) => {
  vi.stubGlobal('fetch', async (url: string) => url.endsWith('/principal') ? Response.json(receptionist) : Response.json({ items: [], total: 0 }));
  renderWithMessages(<AccountScreen kind="staff" initialPrincipal={initialPrincipal} timeZone="Asia/Colombo" />);
  expect(await screen.findByTestId('account-role')).toHaveTextContent('Receptionist');
  expect(navigation.refresh).toHaveBeenCalledTimes(1);
});

it('refreshes navigation after self-demotion and denies the now-protected content', async () => {
  let live = owner;
  vi.stubGlobal('fetch', async (url: string) => {
    if (url.endsWith('/principal')) return Response.json(live);
    if (url.endsWith('/staff-change')) { live = receptionist; return Response.json({ ...person, role: 'RECEPTIONIST', version: 1 }); }
    return live === owner ? Response.json({ items: [person], total: 1 }) : Response.json({ code: 'forbidden' }, { status: 403 });
  });
  renderWithMessages(<AccountScreen kind="staff" initialPrincipal={owner} timeZone="Asia/Colombo" view="staff" />);
  await chooseOption(await screen.findByTestId('account-staff-role'), messages.account.RECEPTIONIST);
  expect(await screen.findByTestId('account-denied')).toHaveTextContent(messages.account.denied);
  expect(screen.queryByTestId('account-staff-create')).not.toBeInTheDocument();
  expect(navigation.refresh).toHaveBeenCalledTimes(1);
});

it('refreshes once for a broadcast permission change, ignoring unchanged order and other account kinds', async () => {
  const channels: { onmessage?: (event: { data: unknown }) => void }[] = [];
  class Channel { onmessage?: (event: { data: unknown }) => void; constructor() { channels.push(this); } close() {} }
  vi.stubGlobal('BroadcastChannel', Channel);
  let live = { ...owner, permissions: [...owner.permissions].reverse() }; let principalReads = 0;
  vi.stubGlobal('fetch', async (url: string) => {
    if (url.endsWith('/principal')) { principalReads++; return Response.json(live); }
    return Response.json({ items: [], total: 0 });
  });
  renderWithMessages(<AccountScreen kind="staff" initialPrincipal={owner} timeZone="Asia/Colombo" />);
  await screen.findByTestId('account-role'); expect(navigation.refresh).not.toHaveBeenCalled();
  const channel = channels.find((c) => c.onmessage)!;
  await act(async () => channel.onmessage!({ data: { kind: 'customer' } }));
  expect(principalReads).toBe(1);
  live = receptionist;
  await act(async () => channel.onmessage!({ data: { kind: 'staff' } }));
  expect(screen.getByTestId('account-role')).toHaveTextContent('Receptionist');
  expect(navigation.refresh).toHaveBeenCalledTimes(1);
  await act(async () => channel.onmessage!({ data: { kind: 'staff' } }));
  expect(navigation.refresh).toHaveBeenCalledTimes(1);
});
