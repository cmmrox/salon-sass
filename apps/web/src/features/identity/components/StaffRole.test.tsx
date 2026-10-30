import { screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { messages, renderWithMessages } from '@/test/render';
import { chooseOption } from '@/test/select';
import { AccountScreen } from './AccountScreen';
vi.mock('next/navigation', () => ({ useRouter: () => ({ replace: vi.fn(), refresh: vi.fn() }) }));
afterEach(() => vi.unstubAllGlobals());
it('clears the Professional-only grant when the Owner selects another role', async () => {
  const principal = { id: 'owner', kind: 'staff', role: 'OWNER', permissions: ['staff.list', 'staff.manage', 'session.manage'], scope: 'salon', staffId: 'owner', mfaEnabled: true, expiresAt: null };
  let person = { id: 'professional', email: 'synthetic@alpha.test', role: 'PROFESSIONAL', professionalGrant: true, version: 0, active: true };
  const changes: Record<string, unknown>[] = [];
  vi.stubGlobal('navigator', { locks: { request: (_name: string, work: () => Promise<unknown>) => work() } });
  vi.stubGlobal('BroadcastChannel', undefined);
  vi.stubGlobal('fetch', async (url: string, options?: RequestInit) => {
    if (url.endsWith('/principal')) return Response.json(principal);
    if (url.includes('/staff-change')) { const body = JSON.parse(options!.body as string); changes.push(body); person = { ...person, ...body, version: 1 }; return Response.json(person); }
    return Response.json({ items: [person], page: 0, size: 10, total: 1 });
  });
  renderWithMessages(<AccountScreen kind="staff" initialPrincipal={principal} timeZone="Asia/Colombo" view="staff" />);
  await screen.findByTestId('account-staff-role');
  expect(screen.getByTestId('account-professional-grant')).toBeChecked();
  await chooseOption(screen.getByTestId('account-staff-role'), messages.account.RECEPTIONIST);
  await waitFor(() => expect(changes).toEqual([{ id: 'professional', version: 0, role: 'RECEPTIONIST', professionalGrant: false }]));
  await waitFor(() => expect(screen.queryByTestId('account-professional-grant')).not.toBeInTheDocument());
  expect(screen.getByTestId('account-staff-role')).toHaveTextContent(messages.account.RECEPTIONIST);
});
