import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderWithMessages } from '@test/render';
import { brandRootRule } from '@/styles/brand';
import { AccountFrame } from './AccountFrame';
const salon = {
  slug: 'beta',
  name: 'Beta Hair, Beauty & Wellness Lounge of Colombo Seven',
  brandColour: '#1F2937',
  timeZone: 'Asia/Colombo',
};
describe('staff sign-in frame', () => {
  it('shows the resolved salon identity and leaves workspace navigation for authenticated screens', () => {
    renderWithMessages(
      <AccountFrame salon={salon} signIn>
        <p>Form content</p>
      </AccountFrame>,
    );
    expect(screen.getByTestId('staff-sign-in-name')).toHaveTextContent(salon.name);
    expect(screen.getByTestId('staff-sign-in-initial')).toHaveTextContent('B');
    expect(screen.getByRole('main')).toContainElement(screen.getByText('Form content'));
    expect(screen.queryByTestId('workspace-header')).not.toBeInTheDocument();
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument();
  });
});
describe('salon brand on every salon screen (CR-013)', () => {
  const brand = () => document.querySelector('style[data-testid="salon-brand"]')?.textContent;
  it.each([true, false])("puts the salon's own colour on the root (sign-in: %s)", (signIn) => {
    renderWithMessages(
      <AccountFrame salon={salon} signIn={signIn}>
        <p>Content</p>
      </AccountFrame>,
    );
    expect(brand()).toBe(brandRootRule('#1F2937'));
  });
  it('gives the light salon its readable foreground and the default to a salon without a valid colour', () => {
    const { unmount } = renderWithMessages(
      <AccountFrame salon={{ ...salon, brandColour: '#FDE68A' }}>
        <p>Light</p>
      </AccountFrame>,
    );
    expect(brand()).toContain('--color-on-brand:#111827');
    unmount();
    renderWithMessages(
      <AccountFrame salon={{ ...salon, brandColour: 'not-a-colour' }} signIn>
        <p>Default</p>
      </AccountFrame>,
    );
    expect(brand()).toBe(brandRootRule('#D6086A'));
  });
  it("shows the workspace with the salon's resolved name, never the generic product name", () => {
    renderWithMessages(
      <AccountFrame salon={salon}>
        <p>Content</p>
      </AccountFrame>,
    );
    expect(screen.getByTestId('account-home')).toHaveTextContent(salon.name);
    expect(screen.queryByTestId('app-name')).not.toBeInTheDocument();
  });
});
