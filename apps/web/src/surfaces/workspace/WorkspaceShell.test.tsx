import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import type { AnchorHTMLAttributes } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { messages, renderWithMessages } from '@test/render';
import { setViewportWidth } from '@test/viewport';
import { WorkspaceShell } from './WorkspaceShell';

// A route choice is a click on next/link; here it only runs the shell's own handler.
vi.mock('next/link', () => ({
  default: ({ onClick, ...props }: AnchorHTMLAttributes<HTMLAnchorElement>) => (
    <a
      {...props}
      onClick={(event) => {
        onClick?.(event);
        event.preventDefault();
      }}
    />
  ),
}));

const salon = { slug: 'alpha', name: 'Alpha Studio', brandColour: '#D6086A', timeZone: 'Asia/Colombo' };
const all = ['session.manage', 'staff.list', 'audit.list'];
const t = messages.account;

describe('workspace route navigation', () => {
  it('uses only permitted real routes, with one current destination and no customer brand', () => {
    const { container } = renderWithMessages(
      <WorkspaceShell salon={salon} permissions={all} view="staff">
        <div />
      </WorkspaceShell>,
    );
    expect(screen.getByTestId('workspace-staff')).toHaveAttribute('aria-current', 'page');
    expect(screen.getByTestId('workspace-account')).not.toHaveAttribute('aria-current');
    expect(screen.getByTestId('workspace-audit')).toHaveAttribute('href', '/workspace/audit');
    expect(screen.getByTestId('account-nav')).toBeInTheDocument();
    expect(screen.queryByTestId('account-other-kind')).not.toBeInTheDocument();
    expect(
      [...container.querySelectorAll('a')].every(
        (link) => link.getAttribute('href') === '/' || link.getAttribute('href')?.startsWith('/workspace'),
      ),
    ).toBe(true);
    expect(container.querySelectorAll('[aria-current]')).toHaveLength(1);
    expect(container.innerHTML).not.toContain(salon.brandColour);
  });
  it('does not show management links to sign-in or an own-account principal', () => {
    renderWithMessages(
      <WorkspaceShell salon={salon} permissions={['session.manage']}>
        <div />
      </WorkspaceShell>,
    );
    expect(screen.queryByTestId('workspace-staff')).not.toBeInTheDocument();
    expect(screen.queryByTestId('workspace-audit')).not.toBeInTheDocument();
    expect(screen.getByTestId('workspace-account')).toHaveAttribute('href', '/workspace/account');
  });
});

describe('the Sidebar from 900px (CR-017)', () => {
  it('is the rail: a labelled navigation landmark of Sidebar menu buttons, the current one active, beside the main area', () => {
    setViewportWidth(1280);
    renderWithMessages(
      <WorkspaceShell salon={salon} permissions={all} view="audit">
        <p>content</p>
      </WorkspaceShell>,
    );
    const navigation = screen.getByRole('navigation', { name: t.staffTitle });
    expect(navigation).toHaveAttribute('data-testid', 'workspace-navigation');
    expect(navigation.closest('[data-slot="sidebar-container"]')).not.toBeNull();
    expect(navigation.querySelector('[data-slot="sidebar-menu"]')).not.toBeNull();
    const buttons = within(navigation).getAllByRole('link');
    expect(buttons.map((link) => link.getAttribute('data-testid'))).toEqual([
      'workspace-account',
      'workspace-staff',
      'workspace-audit',
    ]);
    for (const link of buttons) expect(link).toHaveAttribute('data-slot', 'sidebar-menu-button');
    expect(screen.getByTestId('workspace-audit')).toHaveAttribute('data-active', 'true');
    expect(screen.getByTestId('workspace-account')).toHaveAttribute('data-active', 'false');
    expect(screen.getByTestId('workspace-shell')).toHaveAttribute('data-slot', 'sidebar-wrapper');
    expect(screen.getByTestId('workspace-main')).toHaveAttribute('data-slot', 'sidebar-inset');
    expect(screen.getByRole('main')).toHaveTextContent('content');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('does not collapse: the rail stays when its keyboard shortcut is pressed', () => {
    setViewportWidth(1280);
    renderWithMessages(
      <WorkspaceShell salon={salon} permissions={all}>
        <div />
      </WorkspaceShell>,
    );
    fireEvent.keyDown(window, { key: 'b', ctrlKey: true });
    expect(screen.getByTestId('workspace-navigation').closest('[data-slot="sidebar"]')).toHaveAttribute(
      'data-state',
      'expanded',
    );
  });

  it('writes no cookie for its state', () => {
    setViewportWidth(1280);
    renderWithMessages(
      <WorkspaceShell salon={salon} permissions={all}>
        <div />
      </WorkspaceShell>,
    );
    fireEvent.keyDown(window, { key: 'b', ctrlKey: true });
    expect(document.cookie).not.toContain('sidebar');
  });
});

describe('the Sidebar below 900px (CR-017)', () => {
  function openSheet() {
    const trigger = screen.getByTestId('workspace-menu');
    fireEvent.click(trigger);
    return { trigger, sheet: screen.getByRole('dialog', { name: t.staffTitle }) };
  }

  it("has no tab bar: the header's menu button opens the Sidebar's sheet with the same navigation", () => {
    setViewportWidth(390);
    renderWithMessages(
      <WorkspaceShell salon={salon} permissions={all} view="staff">
        <div />
      </WorkspaceShell>,
    );
    const trigger = screen.getByTestId('workspace-menu');
    expect(screen.getByTestId('workspace-header')).toContainElement(trigger);
    expect(trigger).toHaveAttribute('data-slot', 'sidebar-trigger');
    expect(trigger).toHaveAccessibleName(t.menu);
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByTestId('workspace-navigation')).not.toBeInTheDocument();

    const { sheet } = openSheet();
    expect(trigger).toHaveAttribute('aria-expanded', 'true');
    expect(sheet).toHaveAttribute('data-slot', 'sidebar');
    expect(sheet).toHaveAttribute('data-mobile', 'true');
    expect(sheet).toHaveAccessibleDescription(t.menuHelp);
    const navigation = within(sheet).getByRole('navigation', { name: t.staffTitle });
    expect(navigation).toHaveAttribute('data-testid', 'workspace-navigation');
    expect(
      within(navigation)
        .getAllByRole('link')
        .map((link) => link.getAttribute('data-testid')),
    ).toEqual(['workspace-account', 'workspace-staff', 'workspace-audit']);
    expect(within(navigation).getByTestId('workspace-staff')).toHaveAttribute('aria-current', 'page');
    expect(within(navigation).getByTestId('workspace-staff')).toHaveAttribute('data-active', 'true');
  });

  it('puts the focus on the current route when the sheet opens, not on the sheet itself', async () => {
    setViewportWidth(390);
    renderWithMessages(
      <WorkspaceShell salon={salon} permissions={all} view="staff">
        <div />
      </WorkspaceShell>,
    );
    const { sheet } = openSheet();
    await waitFor(() => expect(within(sheet).getByTestId('workspace-staff')).toHaveFocus());
  });

  it('closes on choosing a route and gives the focus back to the menu button', async () => {
    setViewportWidth(390);
    renderWithMessages(
      <WorkspaceShell salon={salon} permissions={all} view="staff">
        <div />
      </WorkspaceShell>,
    );
    const { trigger, sheet } = openSheet();
    fireEvent.click(within(sheet).getByTestId('workspace-audit'));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await waitFor(() => expect(trigger).toHaveFocus());
  });

  it('closes on Escape and gives the focus back to the menu button', async () => {
    setViewportWidth(390);
    renderWithMessages(
      <WorkspaceShell salon={salon} permissions={all}>
        <div />
      </WorkspaceShell>,
    );
    const { trigger, sheet } = openSheet();
    fireEvent.keyDown(sheet, { key: 'Escape' });
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await waitFor(() => expect(trigger).toHaveFocus());
  });

  it('closes on its backdrop and gives the focus back to the menu button', async () => {
    setViewportWidth(390);
    renderWithMessages(
      <WorkspaceShell salon={salon} permissions={all}>
        <div />
      </WorkspaceShell>,
    );
    const { trigger } = openSheet();
    const backdrop = document.querySelector('[data-slot="sheet-overlay"]')!;
    expect(backdrop).not.toBeNull();
    // Radix listens for the outside press once the sheet has opened.
    await act(() => new Promise((resolve) => setTimeout(resolve, 0)));
    // A press on the backdrop, as a browser sends it.
    fireEvent.pointerDown(backdrop);
    fireEvent.mouseDown(backdrop);
    fireEvent.pointerUp(backdrop);
    fireEvent.mouseUp(backdrop);
    fireEvent.click(backdrop);
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await waitFor(() => expect(trigger).toHaveFocus());
  });

  it('becomes the rail when the viewport widens to 900px, and the sheet again below it', () => {
    setViewportWidth(899);
    renderWithMessages(
      <WorkspaceShell salon={salon} permissions={all}>
        <div />
      </WorkspaceShell>,
    );
    expect(screen.queryByTestId('workspace-navigation')).not.toBeInTheDocument();
    act(() => setViewportWidth(900));
    expect(screen.getByTestId('workspace-navigation').closest('[data-slot="sidebar-container"]')).not.toBeNull();
    act(() => setViewportWidth(899));
    expect(screen.queryByTestId('workspace-navigation')).not.toBeInTheDocument();
  });
});
