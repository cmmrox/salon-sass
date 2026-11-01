import { describe, expect, it } from 'vitest';
import { dayButtonLabel } from './calendar-labels';

describe('a Calendar day button’s name (F04-S03)', () => {
  it('holds the plain day number it shows, and says today and selected', () => {
    const date = new Date(2026, 10, 2);
    expect(dayButtonLabel(date, {})).toMatch(/\b2\b/);
    expect(dayButtonLabel(date, { today: true })).toMatch(/^Today, /);
    expect(dayButtonLabel(date, { selected: true })).toMatch(/, selected$/);
  });
});
