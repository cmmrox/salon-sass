import { screen } from '@testing-library/react';
import { expect, it } from 'vitest';
import { StatusBody } from './Status';
import { Alert } from '@/components/ui/alert';
import { renderWithMessages } from '@test/render';
import { themeValue, utilityColour } from '@test/theme';
import { contrastRatio } from '@/styles/brand';

// BUG-F01-003: the reference's positive text is not AA on its surface, so normal-size success text
// is ink on the green surface. The prototype's positive palette itself is kept.
it('the status summary pairs normal-size positive feedback with AA readable text', () => {
  renderWithMessages(<StatusBody state={{ kind: 'loaded', service: 'salon-api', version: '1.2.3' }} retryHref="/" />);
  const classes = [...screen.getByTestId('status-summary').classList];
  const background = classes.find((name) => name.startsWith('bg-'));
  const text = classes.find((name) => name.startsWith('text-') && !/^text-(small|body|large|title|label|field|nav|rail|login-)/.test(name));

  expect(background).toBe('bg-positive-surface');
  expect(contrastRatio(utilityColour(text!, 'text'), utilityColour(background!, 'bg'))).toBeGreaterThanOrEqual(4.5);
});

it('the account success notice pairs normal-size positive feedback with AA readable text', () => {
  renderWithMessages(<Alert variant="success" data-testid="success">done</Alert>);
  const classes = [...screen.getByTestId('success').classList];
  const background = classes.find((name) => name.startsWith('bg-'));
  const text = classes.find((name) => name.startsWith('text-') && !/^text-(small|body|large|title|label|field|nav|rail|login-)/.test(name));

  expect(background).toBe('bg-positive-surface');
  expect(contrastRatio(utilityColour(text!, 'text'), utilityColour(background!, 'bg'))).toBeGreaterThanOrEqual(4.5);
});

it('keeps the reference positive palette', () => {
  expect(themeValue('--positive')).toBe('#0F7B55');
  expect(themeValue('--positive-surface')).toBe('#DDF1E6');
});
