import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { screen } from '@testing-library/react';
import { expect, it } from 'vitest';
import { StatusBody } from '@/components/status/Status';
import { renderWithMessages } from '@/test/render';
import { themeValue, utilityColour } from '@/test/theme';
import { contrastRatio } from './brand';

// BUG-F01-003: the reference's positive text is not AA on its surface, so normal-size success text
// is ink on the green surface. The prototype's positive palette itself is kept.
it('the status summary pairs normal-size positive feedback with AA readable text', () => {
  renderWithMessages(<StatusBody state={{ kind: 'loaded', service: 'salon-api', version: '1.2.3' }} retryHref="/" />);
  const classes = [...screen.getByTestId('status-summary').classList];
  const background = classes.find((name) => name.startsWith('bg-'));
  const text = classes.find((name) => name.startsWith('text-') && !/^text-(small|body|large|title|label|input|nav|rail)/.test(name));

  expect(background).toBe('bg-positive-surface');
  expect(contrastRatio(utilityColour(text!, 'text'), utilityColour(background!, 'bg'))).toBeGreaterThanOrEqual(4.5);
});

// F02-S04 (CR-016) moves the account screens off their CSS Module, and this check with them.
it('components/account/Account.module.css pairs normal-size positive feedback with AA readable text', () => {
  const sheet = readFileSync(join(__dirname, '..', 'components/account/Account.module.css'), 'utf8');
  const rule = /\.success\s*\{([^}]+)\}/.exec(sheet)![1]!;
  const text = /(?:^|;)\s*color:\s*var\((--[\w-]+)\)/.exec(rule)![1]!;
  const background = /background:\s*var\((--[\w-]+)\)/.exec(rule)![1]!;

  expect(contrastRatio(themeValue(text), themeValue(background))).toBeGreaterThanOrEqual(4.5);
});

it('keeps the reference positive palette', () => {
  expect(themeValue('--positive')).toBe('#0F7B55');
  expect(themeValue('--positive-surface')).toBe('#DDF1E6');
});
