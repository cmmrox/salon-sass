import { describe, expect, it } from 'vitest';
import type { TeamWorkday, Weekday } from '../types';
import {
  changeIsEdited,
  changeSaveOf,
  copyMonday,
  emptyHours,
  hoursProblems,
  hoursSummaryOf,
  newBreak,
  newChange,
  outsideOpening,
  problemsOfWeekFields,
  salonHoursOn,
  sameWeek,
  weekFormOf,
  weekProblems,
  weekSaveOf,
  type DayHours,
} from './schedule-form';

const pause = (starts: string, finishes: string) => ({ starts, finishes });
const hours = (starts: string, finishes: string, ...breaks: ReturnType<typeof pause>[]): DayHours => ({
  working: true,
  starts,
  finishes,
  breaks,
});
const workday = (
  day: TeamWorkday['day'],
  starts: string,
  finishes: string,
  ...breaks: ReturnType<typeof pause>[]
): TeamWorkday => ({ day, working: true, starts, finishes, breaks });
const open = (day: Weekday['day'], opens: string, closes: string): Weekday => ({ day, open: true, opens, closes });
const closed = (day: Weekday['day']): Weekday => ({ day, open: false, opens: null, closes: null });

describe('the rules of a day (F06-S02 AC-2, AC-3)', () => {
  it('a finish at or before the start is refused', () => {
    expect(hoursProblems(hours('09:00', '09:00'))).toEqual(['finishAfterStart']);
    expect(hoursProblems(hours('18:00', '09:00'))).toEqual(['finishAfterStart']);
    expect(hoursProblems(hours('09:00', '09:15'))).toEqual([]);
  });

  it('a day not worked breaks no rule, whatever it holds', () => {
    expect(hoursProblems({ ...hours('18:00', '09:00', pause('10:00', '09:00')), working: false })).toEqual([]);
  });

  it('a break ends after it starts', () => {
    expect(hoursProblems(hours('09:00', '18:00', pause('13:00', '13:00')))).toEqual(['breakEndsAfterStart']);
    expect(hoursProblems(hours('09:00', '18:00', pause('14:00', '13:00')))).toEqual(['breakEndsAfterStart']);
  });

  it('a break is inside the hours, and may touch the start or the finish', () => {
    expect(hoursProblems(hours('09:00', '18:00', pause('08:45', '10:00')))).toEqual(['breakInside']);
    expect(hoursProblems(hours('09:00', '18:00', pause('17:00', '18:15')))).toEqual(['breakInside']);
    expect(hoursProblems(hours('09:00', '18:00', pause('09:00', '10:00')))).toEqual([]);
    expect(hoursProblems(hours('09:00', '18:00', pause('17:00', '18:00')))).toEqual([]);
  });

  it('breaks may touch and not overlap, in whatever order they were added', () => {
    expect(hoursProblems(hours('09:00', '18:00', pause('13:00', '14:00'), pause('12:00', '13:00')))).toEqual([]);
    expect(hoursProblems(hours('09:00', '18:00', pause('13:00', '14:00'), pause('12:00', '13:15')))).toEqual([
      'breaksOverlap',
    ]);
  });

  it('breaks that leave none of the day are refused, one or several, and a quarter of an hour left is enough', () => {
    expect(hoursProblems(hours('09:00', '18:00', pause('09:00', '18:00')))).toEqual(['breaksFillDay']);
    expect(hoursProblems(hours('09:00', '18:00', pause('09:00', '13:00'), pause('13:00', '18:00')))).toEqual([
      'breaksFillDay',
    ]);
    expect(hoursProblems(hours('09:00', '18:00', pause('09:15', '18:00')))).toEqual([]);
    expect(hoursProblems(hours('09:00', '18:00', pause('09:00', '17:45')))).toEqual([]);
  });

  it('a sixth break is one too many', () => {
    const six = ['08', '09', '10', '11', '12', '13'].map((at) => pause(`${at}:00`, `${at}:15`));
    expect(hoursProblems(hours('08:00', '18:00', ...six.slice(0, 5)))).toEqual([]);
    expect(hoursProblems(hours('08:00', '18:00', ...six))).toEqual(['tooManyBreaks']);
  });

  it('names the weekday of each rule broken', () => {
    const form = weekFormOf([workday('tuesday', '18:00', '09:00')]);
    expect(weekProblems(form)).toEqual([{ day: 'tuesday', problem: 'finishAfterStart' }]);
  });
});

describe('what the API named (AC-2, AC-3)', () => {
  it('turns each field into the rule it names, on its weekday', () => {
    const form = weekFormOf([]);
    expect(
      problemsOfWeekFields(
        [
          'weekly[0].finishes',
          'weekly[1].breaks[0].finishes',
          'weekly[2].breaks[1]',
          'weekly[3].breaks[1].starts',
          'weekly[4].breaks',
          'weekly[5].day',
          'version',
        ],
        form,
      ),
    ).toEqual([
      { day: 'monday', problem: 'finishAfterStart' },
      { day: 'tuesday', problem: 'breakEndsAfterStart' },
      { day: 'wednesday', problem: 'breakInside' },
      { day: 'thursday', problem: 'breaksOverlap' },
      { day: 'friday', problem: 'breaksFillDay' },
    ]);
  });
});

describe('a new break (the prototype’s + Add break)', () => {
  it('is an hour to 2 PM, or the day’s last hour when it finishes earlier', () => {
    expect(newBreak(hours('09:00', '18:00'))).toEqual(pause('13:00', '14:00'));
    expect(newBreak(hours('08:00', '12:00'))).toEqual(pause('11:00', '12:00'));
    expect(newBreak(hours('00:00', '00:30'))).toEqual(pause('00:00', '00:30'));
  });
});

describe('the week the sheet sends and compares', () => {
  it('reads Monday first, keeps a default while a day is not worked, and sends a day not worked with no times or breaks', () => {
    const form = weekFormOf([workday('monday', '10:00', '16:00', pause('12:00', '12:30'))]);
    expect(form[0]).toMatchObject({ day: 'monday', working: true, starts: '10:00' });
    expect(form[6]).toMatchObject({ day: 'sunday', working: false, starts: '09:00', finishes: '18:00' });

    const off = form.map((day, index) => (index === 0 ? { ...day, working: false } : day));
    expect(weekSaveOf(off)[0]).toEqual({ day: 'monday', working: false, starts: null, finishes: null, breaks: [] });
    expect(weekSaveOf(form)[0]).toEqual({
      day: 'monday',
      working: true,
      starts: '10:00',
      finishes: '16:00',
      breaks: [pause('12:00', '12:30')],
    });
  });

  it('ignores what a day not worked holds and the order of breaks, and sees any other difference', () => {
    const form = weekFormOf([workday('monday', '10:00', '16:00', pause('11:00', '11:15'), pause('12:00', '12:30'))]);
    const reordered = form.map((day) => ({ ...day, breaks: [...day.breaks].reverse() }));
    expect(sameWeek(form, reordered)).toBe(true);

    const heldWhileOff = form.map((day, index) => (index === 6 ? { ...day, starts: '07:00' } : day));
    expect(sameWeek(form, heldWhileOff)).toBe(true);

    const moved = form.map((day, index) => (index === 0 ? { ...day, starts: '10:15' } : day));
    expect(sameWeek(form, moved)).toBe(false);
    const fewer = form.map((day, index) => (index === 0 ? { ...day, breaks: day.breaks.slice(1) } : day));
    expect(sameWeek(form, fewer)).toBe(false);
  });
});

describe('the shortcuts (AC-4)', () => {
  it('Copy Monday sets the other working days’ times and breaks, and leaves days not worked', () => {
    const form = weekFormOf([
      workday('monday', '08:00', '12:00', pause('10:00', '10:15')),
      workday('tuesday', '09:00', '18:00'),
    ]);

    const copied = copyMonday(form);

    expect(copied[1]).toMatchObject({
      working: true,
      starts: '08:00',
      finishes: '12:00',
      breaks: [pause('10:00', '10:15')],
    });
    expect(copied[2]).toMatchObject({ working: false });
    // A copy, so a later edit of Tuesday's breaks cannot reach Monday's.
    expect(copied[1]!.breaks[0]).not.toBe(copied[0]!.breaks[0]);
  });

  it('Use the salon’s hours sets every weekday and leaves the breaks of days that stay working', () => {
    const form = weekFormOf([
      workday('monday', '08:00', '12:00', pause('10:00', '10:15')),
      workday('sunday', '10:00', '12:00'),
    ]);

    const set = salonHoursOn(form, [
      open('monday', '09:00', '18:00'),
      open('tuesday', '09:00', '17:00'),
      closed('sunday'),
    ]);

    expect(set[0]).toMatchObject({
      working: true,
      starts: '09:00',
      finishes: '18:00',
      breaks: [pause('10:00', '10:15')],
    });
    expect(set[1]).toMatchObject({ working: true, starts: '09:00', finishes: '17:00' });
    expect(set[6]).toMatchObject({ working: false });
  });
});

describe('the warning about the salon’s hours (AC-5)', () => {
  const salon = [open('monday', '09:00', '18:00'), closed('sunday')];

  it('names a working day the salon is closed on, or whose times reach outside its own', () => {
    const form = weekFormOf([
      workday('monday', '08:45', '18:00'),
      workday('tuesday', '10:00', '12:00'),
      workday('sunday', '10:00', '12:00'),
    ]);
    expect(outsideOpening(form, salon)).toEqual([
      { day: 'monday', opens: '09:00', closes: '18:00' },
      { day: 'tuesday', opens: null, closes: null },
      { day: 'sunday', opens: null, closes: null },
    ]);
  });

  it('says nothing for days inside the hours or not worked, and treats a salon with no hours as closed', () => {
    const inside = weekFormOf([workday('monday', '09:00', '18:00')]);
    expect(outsideOpening(inside, salon)).toEqual([]);
    expect(outsideOpening(weekFormOf([]), salon)).toEqual([]);
    expect(outsideOpening(inside, null)).toEqual([{ day: 'monday', opens: null, closes: null }]);
  });
});

describe('the card’s Hours row (AC-12)', () => {
  const lunch = pause('13:00', '14:00');
  const days: TeamWorkday['day'][] = ['monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday'];

  it('is a run of days with the same times and one break', () => {
    expect(hoursSummaryOf(days.map((day) => workday(day, '09:00', '18:00', lunch)))).toEqual({
      kind: 'days',
      first: 'monday',
      last: 'saturday',
      starts: '09:00',
      finishes: '18:00',
      breaks: { count: 1, at: '13:00' },
    });
  });

  it('counts more than one break and says nothing of none', () => {
    const two = hoursSummaryOf([workday('monday', '09:00', '18:00', lunch, pause('15:00', '15:15'))]);
    expect(two).toMatchObject({ breaks: { count: 2 } });
    expect(hoursSummaryOf([workday('monday', '09:00', '18:00')])).toMatchObject({
      first: 'monday',
      last: 'monday',
      breaks: { count: 0 },
    });
  });

  it('varies when the days are not one unbroken stretch or differ in times or breaks', () => {
    const gap = [workday('monday', '09:00', '18:00'), workday('wednesday', '09:00', '18:00')];
    expect(hoursSummaryOf(gap)).toEqual({ kind: 'varies' });
    expect(hoursSummaryOf([workday('monday', '09:00', '18:00'), workday('tuesday', '09:00', '17:00')])).toEqual({
      kind: 'varies',
    });
    expect(hoursSummaryOf([workday('monday', '09:00', '18:00', lunch), workday('tuesday', '09:00', '18:00')])).toEqual({
      kind: 'varies',
    });
  });

  it('does not run Sunday into Monday', () => {
    expect(hoursSummaryOf([workday('sunday', '09:00', '18:00'), workday('monday', '09:00', '18:00')])).toEqual({
      kind: 'varies',
    });
  });

  it('says no working days', () => {
    expect(hoursSummaryOf([])).toEqual({ kind: 'none' });
  });
});

describe('a date change’s form (AC-7)', () => {
  it('starts as nothing to lose, and is edited by any field', () => {
    expect(changeIsEdited(newChange())).toBe(false);
    expect(changeIsEdited({ ...newChange(), date: '2026-11-05' })).toBe(true);
    expect(changeIsEdited({ ...newChange(), note: 'x' })).toBe(true);
    expect(changeIsEdited({ ...newChange(), working: false })).toBe(true);
    expect(changeIsEdited({ ...newChange(), breaks: [pause('10:00', '10:15')] })).toBe(true);
    expect(emptyHours().working).toBe(true);
  });

  it('sends no times or breaks when not working, and a blank note as none', () => {
    expect(changeSaveOf({ ...newChange(), date: '2026-11-05', working: false, note: '  ' })).toEqual({
      date: '2026-11-05',
      working: false,
      starts: null,
      finishes: null,
      breaks: [],
      note: null,
    });
    expect(
      changeSaveOf({ ...newChange(), date: '2026-11-05', breaks: [pause('10:00', '10:15')], note: ' Half day ' }),
    ).toEqual({
      date: '2026-11-05',
      working: true,
      starts: '09:00',
      finishes: '18:00',
      breaks: [pause('10:00', '10:15')],
      note: 'Half day',
    });
  });
});
