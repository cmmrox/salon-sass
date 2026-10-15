import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderWithMessages } from '@/test/render';
import { WorkspaceShell } from './WorkspaceShell';
const salon = { slug: 'alpha', name: 'Alpha Studio', brandColour: '#D6086A', timeZone: 'Asia/Colombo' };
describe('workspace route navigation', () => {
 it('uses only permitted real routes, with one current destination and no customer brand', () => {
  const { container } = renderWithMessages(<WorkspaceShell salon={salon} permissions={['session.manage', 'staff.list', 'audit.list']} view="staff"><div /></WorkspaceShell>);
  expect(screen.getByTestId('workspace-staff')).toHaveAttribute('aria-current', 'page');
  expect(screen.getByTestId('workspace-account')).not.toHaveAttribute('aria-current');
  expect(screen.getByTestId('workspace-audit')).toHaveAttribute('href', '/workspace/audit');
  expect(screen.getByTestId('account-nav')).toBeInTheDocument();
  expect(screen.queryByTestId('account-other-kind')).not.toBeInTheDocument();
  expect([...container.querySelectorAll('a')].every((link) => link.getAttribute('href') === '/' || link.getAttribute('href')?.startsWith('/workspace'))).toBe(true);
  expect(container.querySelectorAll('[aria-current]')).toHaveLength(1);
  expect(container.innerHTML).not.toContain(salon.brandColour);
 });
 it('does not show management links to sign-in or an own-account principal', () => {
  renderWithMessages(<WorkspaceShell salon={salon} permissions={['session.manage']}><div /></WorkspaceShell>);
  expect(screen.queryByTestId('workspace-staff')).not.toBeInTheDocument();
  expect(screen.queryByTestId('workspace-audit')).not.toBeInTheDocument();
  expect(screen.getByTestId('workspace-account')).toHaveAttribute('href', '/workspace/account');
 });
});
