import { describe, expect, it } from 'vitest';
import type { HolidayDay, HolidayYear, HoursSettings } from '../types';
import {
  badDays,
  choiceFormOf,
  choiceSaveOf,
  dayOfField,
  lastClosableDate,
  QUARTER_HOURS,
  sameWeek,
  shownYears,
  SUGGESTED_WEEK,
  weekFormOf,
  weekSaveOf,
} from './hours-form';

const none: HoursSettings = { version: 0, weekly: null, outsideHours: [] };

describe('the Opening hours form (F04-S03 AC-1)', () => {
  it('suggests Monday to Friday 09:00 to 18:00, Saturday 09:00 to 19:00 and Sunday closed before the first save', () => {
    const week = weekFormOf(none);
    expect(week).toEqual(SUGGESTED_WEEK);
    expect(week.map((day) => [day.day, day.open, day.opens, day.closes])).toEqual([
      ['monday', true, '09:00', '18:00'],
      ['tuesday', true, '09:00', '18:00'],
      ['wednesday', true, '09:00', '18:00'],
      ['thursday', true, '09:00', '18:00'],
      ['friday', true, '09:00', '18:00'],
      ['saturday', true, '09:00', '19:00'],
      ['sunday', false, '09:00', '18:00'],
    ]);
  });

  it('offers every quarter hour of the day', () => {
    expect(QUARTER_HOURS).toHaveLength(96);
    expect(QUARTER_HOURS.slice(0, 3)).toEqual(['00:00', '00:15', '00:30']);
    expect(QUARTER_HOURS.at(-1)).toBe('23:45');
  });

  it('names an open day whose closing time is not after its opening time, and saves times only on open days', () => {
    const week = weekFormOf(none).map((day) =>
      day.day === 'tuesday' ? { ...day, closes: '09:00' } : day.day === 'wednesday' ? { ...day, closes: '08:45' } : day,
    );
    expect(badDays(week)).toEqual(['tuesday', 'wednesday']);
    expect(weekSaveOf(SUGGESTED_WEEK, 0).weekly.at(-1)).toEqual({
      day: 'sunday',
      open: false,
      opens: null,
      closes: null,
    });
    expect(weekSaveOf(SUGGESTED_WEEK, 3).version).toBe(3);
  });

  it('reads saved hours and tells when the form has changed', () => {
    const saved: HoursSettings = { version: 2, weekly: weekSaveOf(SUGGESTED_WEEK, 2).weekly, outsideHours: [] };
    const form = weekFormOf(saved);
    expect(sameWeek(form, SUGGESTED_WEEK)).toBe(true);
    expect(
      sameWeek(
        form.map((day) => (day.day === 'sunday' ? { ...day, opens: '07:00' } : day)),
        SUGGESTED_WEEK,
      ),
    ).toBe(true);
    expect(
      sameWeek(
        form.map((day) => (day.day === 'monday' ? { ...day, opens: '10:00' } : day)),
        SUGGESTED_WEEK,
      ),
    ).toBe(false);
    expect(dayOfField('weekly[2].closes')).toBe('wednesday');
    expect(dayOfField('version')).toBeNull();
  });
});

describe('holiday choices and closures (F04-S03)', () => {
  const day: HolidayDay = {
    date: '2026-12-25',
    name: 'Christmas Day',
    kind: 'public',
    choice: null,
    opens: null,
    closes: null,
    toReview: true,
    past: false,
  };

  it('shows Closed for a day never decided, and keeps a choice the salon made', () => {
    expect(choiceFormOf(day)).toEqual({ choice: 'closed', opens: '09:00', closes: '13:00' });
    expect(choiceFormOf({ ...day, choice: 'special', opens: '10:00', closes: '14:00' })).toEqual({
      choice: 'special',
      opens: '10:00',
      closes: '14:00',
    });
    expect(choiceSaveOf(day.date, { choice: 'open', opens: '10:00', closes: '14:00' })).toEqual({
      date: '2026-12-25',
      choice: 'open',
      opens: null,
      closes: null,
    });
  });

  it('allows a closure at most a year ahead', () => {
    expect(lastClosableDate('2026-11-01')).toBe('2027-11-01');
    expect(lastClosableDate('2028-02-29')).toBe('2029-02-28');
  });

  it('shows every published year with a day still to come, in year order, and the first year when none has one', () => {
    const yearOf = (year: number, past: boolean[]): HolidayYear => ({
      year,
      revision: 1,
      publishedAt: `${year - 1}-10-01T04:30:00Z`,
      reviewed: false,
      version: 0,
      days: past.map((isPast, index) => ({ ...day, date: `${year}-12-${10 + index}`, past: isPast })),
    });
    const passed = yearOf(2026, [true, true]);
    const current = yearOf(2026, [true, false]);
    const next = yearOf(2027, [false]);

    expect(
      shownYears([next, current]).map((year) => year.year),
      'both, oldest first',
    ).toEqual([2026, 2027]);
    expect(
      shownYears([passed, next]).map((year) => year.year),
      'a year whose days have all passed is left out',
    ).toEqual([2027]);
    expect(shownYears([passed]), 'a passed year alone still says its days have passed').toEqual([passed]);
    expect(shownYears([])).toEqual([]);
  });
});
