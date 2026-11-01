import { fireEvent, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithMessages } from '@test/render';
import { TeamFrame } from './TeamFrame';

const push = vi.fn();
vi.mock('next/navigation', () => ({ useRouter: () => ({ push, replace: vi.fn(), refresh: vi.fn() }) }));

/** The Team page's two tabs (F06-S01 AC-11): each its own address, shown only to those with `staff.list`. */
describe('the Team page’s frame', () => {
  beforeEach(() => push.mockReset());

  it('shows both tabs with staff.list, and goes to F02’s screen for Sign-in accounts', () => {
    renderWithMessages(
      <TeamFrame tab="team" showTabs summary="2 people · 1 shown for online booking">
        <p>the cards</p>
      </TeamFrame>,
    );

    expect(screen.getByTestId('team-tab-team')).toHaveAttribute('data-state', 'active');
    expect(screen.getByTestId('team-summary')).toHaveTextContent('2 people · 1 shown for online booking');
    expect(screen.queryByTestId('team-accounts-banner')).toBeNull();

    const accounts = screen.getByTestId('team-tab-accounts');
    fireEvent.mouseDown(accounts, { button: 0 });

    expect(push).toHaveBeenCalledWith('/workspace/staff');
  });

  it('goes back to the Team tab from the accounts, which say who they are for', () => {
    renderWithMessages(
      <TeamFrame tab="accounts" showTabs>
        <p>the staff screen</p>
      </TeamFrame>,
    );

    expect(screen.getByTestId('team-tab-accounts')).toHaveAttribute('data-state', 'active');
    expect(screen.getByTestId('team-summary')).toHaveTextContent('Who can sign in to this workspace');
    expect(screen.getByTestId('team-accounts-banner')).toHaveTextContent('Someone can take bookings without one.');
    expect(screen.getByTestId('team-panel-accounts')).toHaveTextContent('the staff screen');

    fireEvent.mouseDown(screen.getByTestId('team-tab-team'), { button: 0 });

    expect(push).toHaveBeenCalledWith('/workspace/team');
  });

  it('shows no tab bar without staff.list, only the Team tab’s content', () => {
    renderWithMessages(
      <TeamFrame tab="team" showTabs={false} summary="1 person · 1 shown for online booking">
        <p>the cards</p>
      </TeamFrame>,
    );

    expect(screen.queryByTestId('team-tabs')).toBeNull();
    expect(screen.queryByTestId('team-tab-accounts')).toBeNull();
    expect(screen.getByTestId('team-panel-team')).toHaveTextContent('the cards');
  });

  it('does not move when the tab already open is chosen', () => {
    renderWithMessages(
      <TeamFrame tab="team" showTabs>
        <p>the cards</p>
      </TeamFrame>,
    );

    fireEvent.mouseDown(screen.getByTestId('team-tab-team'), { button: 0 });

    expect(push).not.toHaveBeenCalled();
  });
});
