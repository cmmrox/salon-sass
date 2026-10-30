import { screen } from '@testing-library/react';
import { expect, it } from 'vitest';
import { renderWithMessages } from '@test/render';
import { StaffSignInFrame } from './StaffSignInFrame';

// F02-S04 AC-12 and AC-14 (CR-016, CR-017): the staff sign-in frame uses the shadcn/ui component the
// component map of docs/engineering/web.md, "Styling", names for its job, read from its `data-slot`.
const slot = (element: Element | null) => element?.getAttribute('data-slot');

it('draws the sign-in salon initial as an Avatar', () => {
  renderWithMessages(<StaffSignInFrame salon={{ name: 'Alpha Salon' } as never}><p>child</p></StaffSignInFrame>);
  expect(slot(screen.getByTestId('staff-sign-in-initial'))).toBe('avatar');
  expect(screen.getByTestId('staff-sign-in-initial')).toHaveTextContent('A');
});
