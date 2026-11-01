import { screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithMessages } from '@test/render';
import { AccountScreen } from './AccountScreen';

vi.mock('next/navigation', () => ({ useRouter: () => ({ replace: vi.fn(), refresh: vi.fn() }) }));

/** F02's staff screen names each account's team profile (F06-S01 AC-11), and works without one. */
const principal = (permissions: string[]) => ({
  id: 'owner',
  kind: 'staff',
  role: 'OWNER',
  permissions,
  scope: 'salon',
  staffId: 'owner',
  mfaEnabled: true,
  expiresAt: null,
});
const person = (id: string, email: string) => ({
  id,
  email,
  role: 'RECEPTIONIST',
  professionalGrant: false,
  version: 0,
  active: true,
});

let teamAnswer: () => Response;
let asked: string[];
let current: ReturnType<typeof principal>;
beforeEach(() => {
  asked = [];
  teamAnswer = () =>
    Response.json({
      items: [
        { name: 'Nimali Perera', signInAccountId: 'linked' },
        { name: 'No Sign In', signInAccountId: null },
      ],
    });
  vi.stubGlobal('navigator', { locks: { request: (_name: string, work: () => Promise<unknown>) => work() } });
  vi.stubGlobal('BroadcastChannel', undefined);
  vi.stubGlobal('fetch', async (url: string) => {
    const action = url.split('/').pop()!.split('?')[0]!;
    asked.push(action);
    if (action === 'principal') return Response.json(current);
    if (action === 'team-members') return teamAnswer();
    return Response.json({
      items: [person('linked', 'linked@example.test'), person('free', 'free@example.test')],
      page: 0,
      size: 10,
      total: 2,
    });
  });
});
afterEach(() => vi.unstubAllGlobals());

const render = (permissions: string[]) => {
  current = principal(permissions);
  return renderWithMessages(<AccountScreen kind="staff" initialPrincipal={current} timeZone="Asia/Colombo" view="staff" />);
};
const row = (id: string) => screen.getAllByTestId('account-staff-row').find((each) => each.dataset.id === id)!;

describe('the Sign-in accounts tab', () => {
  it('names the linked team member, and says No team profile for an account with none', async () => {
    render(['staff.list', 'team.read']);
    await screen.findAllByTestId('account-staff-row');

    await waitFor(() =>
      expect(within(row('linked')).getByTestId('account-staff-team-profile')).toHaveTextContent('Nimali Perera'),
    );
    expect(within(row('free')).getByTestId('account-staff-team-profile')).toHaveTextContent('No team profile');
  });

  it('keeps F02’s screen when the team cannot be read, saying nothing about profiles', async () => {
    teamAnswer = () => Response.json({ code: 'service_unavailable' }, { status: 503 });
    render(['staff.list', 'team.read']);

    await screen.findAllByTestId('account-staff-row');

    expect(screen.queryByTestId('account-staff-team-profile')).toBeNull();
    expect(row('linked')).toHaveTextContent('linked@example.test');
  });

  it('does not ask for the team without team.read', async () => {
    render(['staff.list']);

    await screen.findAllByTestId('account-staff-row');

    expect(asked).not.toContain('team-members');
    expect(screen.queryByTestId('account-staff-team-profile')).toBeNull();
  });
});
