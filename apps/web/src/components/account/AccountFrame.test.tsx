import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderWithMessages } from '@/test/render';
import { AccountFrame } from './AccountFrame';
const salon = { slug: 'beta', name: 'Beta Hair, Beauty & Wellness Lounge of Colombo Seven', brandColour: '#1F2937', timeZone: 'Asia/Colombo' };
describe('staff sign-in frame', () => {
  it('shows the resolved salon identity and leaves workspace navigation for authenticated screens', () => {
    renderWithMessages(<AccountFrame salon={salon} signIn><p>Form content</p></AccountFrame>);
    expect(screen.getByTestId('staff-sign-in-name')).toHaveTextContent(salon.name);
    expect(screen.getByTestId('staff-sign-in-initial')).toHaveTextContent('B');
    expect(screen.getByRole('main')).toContainElement(screen.getByText('Form content'));
    expect(screen.queryByTestId('workspace-header')).not.toBeInTheDocument();
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument();
  });
});
