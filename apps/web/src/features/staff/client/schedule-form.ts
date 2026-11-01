import type { MemberDateChange, TeamWorkday, Weekday } from '../types';
import type { WeekdayName } from './hours-form';

/** A break as the sheet holds it. */
export type BreakForm = { starts: string; finishes: string };

/** A day's hours as the sheet holds them; its times and breaks are kept while it is not working, so turning it back on restores them. */
export type DayHours = { working: boolean; starts: string; finishes: string; breaks: BreakForm[] };

/** One weekday in the sheet. */
export type WorkdayForm = DayHours & { day: WeekdayName };

/** The rules a day's hours can break (F06-S02 AC-2, AC-3). */
export type HoursProblem =
  'finishAfterStart' | 'breakEndsAfterStart' | 'breakInside' | 'breaksOverlap' | 'breaksFillDay' | 'tooManyBreaks';

/** A rule a weekday breaks. */
export type WeekProblem = { day: WeekdayName; problem: HoursProblem };

/** A working day has up to five breaks (AC-3). */
export const MAX_BREAKS = 5;

const WEEKDAYS: WeekdayName[] = ['monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday', 'sunday'];

const minutes = (time: string) => {
  const [hours, mins] = time.split(':').map(Number) as [number, number];
  return hours * 60 + mins;
};
const clock = (total: number) =>
  `${String(Math.floor(total / 60)).padStart(2, '0')}:${String(total % 60).padStart(2, '0')}`;

/** What an empty form shows, and a day not working holds until it is turned on. */
const DEFAULT_STARTS = '09:00';
const DEFAULT_FINISHES = '18:00';

export const emptyHours = (): DayHours => ({
  working: true,
  starts: DEFAULT_STARTS,
  finishes: DEFAULT_FINISHES,
  breaks: [],
});

/** The week as the sheet holds it, Monday first. */
export function weekFormOf(weekly: TeamWorkday[]): WorkdayForm[] {
  return WEEKDAYS.map((day) => {
    const saved = weekly.find((each) => each.day === day);
    return {
      day,
      working: Boolean(saved?.working),
      starts: saved?.starts ?? DEFAULT_STARTS,
      finishes: saved?.finishes ?? DEFAULT_FINISHES,
      breaks: (saved?.breaks ?? []).map(({ starts, finishes }) => ({ starts, finishes })),
    };
  });
}

/** The rules these hours break, in the order the prototype's `schErr` says them. A day not worked breaks none. */
export function hoursProblems(hours: DayHours): HoursProblem[] {
  if (!hours.working) return [];
  const problems: HoursProblem[] = [];
  const finishFine = minutes(hours.finishes) > minutes(hours.starts);
  if (!finishFine) problems.push('finishAfterStart');
  if (hours.breaks.length > MAX_BREAKS) problems.push('tooManyBreaks');
  const wellFormed = hours.breaks.filter((each) => minutes(each.finishes) > minutes(each.starts));
  if (wellFormed.length < hours.breaks.length) problems.push('breakEndsAfterStart');
  const inside = wellFormed.filter(
    (each) => minutes(each.starts) >= minutes(hours.starts) && minutes(each.finishes) <= minutes(hours.finishes),
  );
  if (finishFine && inside.length < wellFormed.length) problems.push('breakInside');
  const ordered = [...inside].sort((a, b) => minutes(a.starts) - minutes(b.starts));
  if (ordered.some((each, index) => index > 0 && minutes(each.starts) < minutes(ordered[index - 1]!.finishes)))
    problems.push('breaksOverlap');
  else if (finishFine && problems.length === 0 && fillsTheDay(hours, ordered)) problems.push('breaksFillDay');
  return problems;
}

/** Whether breaks already inside the hours, in time order and not overlapping, leave none of the day. */
function fillsTheDay(hours: DayHours, ordered: BreakForm[]) {
  const taken = ordered.reduce((total, each) => total + minutes(each.finishes) - minutes(each.starts), 0);
  return ordered.length > 0 && taken >= minutes(hours.finishes) - minutes(hours.starts);
}

/** The rules the week breaks, day by day. */
export function weekProblems(week: WorkdayForm[]): WeekProblem[] {
  return week.flatMap((day) => hoursProblems(day).map((problem) => ({ day: day.day, problem })));
}

/** The break a new row starts with, as the prototype's `+ Add break` does: an hour up to 2 PM, or the day's last hour. */
export function newBreak(hours: DayHours): BreakForm {
  const finish = minutes(hours.finishes);
  return { starts: clock(Math.max(Math.min(finish - 60, 780), 0)), finishes: clock(Math.min(finish, 840)) };
}

const breaksOf = (hours: DayHours) =>
  hours.working ? hours.breaks.map(({ starts, finishes }) => ({ starts, finishes })) : [];

/** The save the sheet makes: a day not worked sends no times and keeps no breaks (AC-3). */
export function weekSaveOf(week: WorkdayForm[]) {
  return week.map((day) => ({
    day: day.day,
    working: day.working,
    starts: day.working ? day.starts : null,
    finishes: day.working ? day.finishes : null,
    breaks: breaksOf(day),
  }));
}

/** The same breaks, whichever order they were entered in. */
const breakKey = (hours: DayHours) =>
  breaksOf(hours)
    .map((each) => `${each.starts}-${each.finishes}`)
    .sort()
    .join();

/** Whether two weeks are the same, what is held for a day not worked aside. */
export function sameWeek(a: WorkdayForm[], b: WorkdayForm[]): boolean {
  return a.every((day, index) => {
    const other = b[index]!;
    return (
      day.working === other.working &&
      (!day.working ||
        (day.starts === other.starts && day.finishes === other.finishes && breakKey(day) === breakKey(other)))
    );
  });
}

/** **Copy Monday to working days** (AC-4): Monday's times and breaks on every other working day. */
export function copyMonday(week: WorkdayForm[]): WorkdayForm[] {
  const monday = week[0]!;
  return week.map((day, index) =>
    index === 0 || !day.working
      ? day
      : {
          ...day,
          starts: monday.starts,
          finishes: monday.finishes,
          breaks: monday.breaks.map((each) => ({ ...each })),
        },
  );
}

/** **Use the salon's hours** (AC-4): each weekday from the salon's, the breaks of days that stay working left alone. */
export function salonHoursOn(week: WorkdayForm[], salon: Weekday[]): WorkdayForm[] {
  return week.map((day) => {
    const open = salon.find((each) => each.day === day.day);
    if (!open?.open || !open.opens || !open.closes) return { ...day, working: false };
    return { ...day, working: true, starts: open.opens, finishes: open.closes };
  });
}

/** Why a working day is outside the salon's hours: it is closed, or open between these times. */
export type OutsideOpening = { day: WeekdayName; opens: string | null; closes: string | null };

/**
 * The working days outside the salon's opening hours (AC-5), Monday first, which the sheet warns about without refusing.
 * A salon that has never saved hours has one warning of its own instead, so none here.
 */
export function outsideOpening(week: WorkdayForm[], salon: Weekday[] | null): OutsideOpening[] {
  if (!salon) return [];
  return week
    .filter((day) => day.working)
    .flatMap((day): OutsideOpening[] => {
      const open = salon.find((each) => each.day === day.day);
      if (!open?.open || !open.opens || !open.closes) return [{ day: day.day, opens: null, closes: null }];
      return minutes(day.starts) < minutes(open.opens) || minutes(day.finishes) > minutes(open.closes)
        ? [{ day: day.day, opens: open.opens, closes: open.closes }]
        : [];
    });
}

/** The rule an API field names, and the weekday when it is on one (`weekly[0].breaks[1].finishes`). */
export function problemsOfWeekFields(fields: string[], week: WorkdayForm[]): WeekProblem[] {
  return fields.flatMap((field) => {
    const match = /^weekly\[(\d)\]\.(.+)$/.exec(field);
    const day = match ? WEEKDAYS[Number(match[1])] : undefined;
    if (!match || !day) return [];
    const problem = problemOfField(match[2]!, week[Number(match[1])]?.breaks.length ?? 0);
    return problem ? [{ day, problem }] : [];
  });
}

/** The rule a field of a day names. `breaks` alone is either too many or none of the day left, told by the count. */
export function problemOfField(field: string, breaks: number): HoursProblem | null {
  if (field === 'starts' || field === 'finishes') return 'finishAfterStart';
  if (field === 'breaks') return breaks > MAX_BREAKS ? 'tooManyBreaks' : 'breaksFillDay';
  if (/^breaks\[\d+\]\.finishes$/.test(field)) return 'breakEndsAfterStart';
  if (/^breaks\[\d+\]\.starts$/.test(field)) return 'breaksOverlap';
  if (/^breaks\[\d+\]$/.test(field)) return 'breakInside';
  return null;
}

/** What a day's breaks come to in words, the same on a card and on a date. */
export type BreakSummary = { count: 0 } | { count: 1; at: string } | { count: number };

export const breakSummaryOf = (breaks: { starts: string }[]): BreakSummary =>
  breaks.length === 1 ? { count: 1, at: breaks[0]!.starts } : { count: breaks.length };

/**
 * The card's Hours row (AC-12): a run of working days in one unbroken stretch with the same times and breaks, a single
 * day, "Varies by day" or no working days. Monday is the week's first day, so Sunday and Monday do not run together.
 */
export type HoursSummary =
  | { kind: 'none' }
  | { kind: 'varies' }
  | {
      kind: 'days';
      first: WeekdayName;
      last: WeekdayName;
      starts: string;
      finishes: string;
      breaks: BreakSummary;
    };

export function hoursSummaryOf(weekly: TeamWorkday[]): HoursSummary {
  const working = WEEKDAYS.flatMap((day, index) => {
    const found = weekly.find((each) => each.day === day);
    return found?.working && found.starts && found.finishes ? [{ index, found }] : [];
  });
  const first = working[0];
  if (!first) return { kind: 'none' };
  const last = working[working.length - 1]!;
  const sameHours = working.every(
    ({ found }) =>
      found.starts === first.found.starts &&
      found.finishes === first.found.finishes &&
      breakText(found) === breakText(first.found),
  );
  const unbroken = last.index - first.index === working.length - 1;
  if (!sameHours || !unbroken) return { kind: 'varies' };
  return {
    kind: 'days',
    first: first.found.day as WeekdayName,
    last: last.found.day as WeekdayName,
    starts: first.found.starts!,
    finishes: first.found.finishes!,
    breaks: breakSummaryOf(first.found.breaks),
  };
}

const breakText = (day: TeamWorkday) => day.breaks.map((each) => `${each.starts}-${each.finishes}`).join();

/** A date change as the sheet's form holds it. */
export type ChangeForm = DayHours & { date: string | null; note: string };

export const newChange = (): ChangeForm => ({ ...emptyHours(), date: null, note: '' });

/** Whether the form holds anything to lose. */
export function changeIsEdited(form: ChangeForm): boolean {
  const empty = newChange();
  return (
    form.date !== null ||
    form.note !== '' ||
    !form.working ||
    form.starts !== empty.starts ||
    form.finishes !== empty.finishes ||
    form.breaks.length > 0
  );
}

/** The change as the API takes it. */
export function changeSaveOf(form: ChangeForm) {
  return {
    date: form.date,
    working: form.working,
    starts: form.working ? form.starts : null,
    finishes: form.working ? form.finishes : null,
    breaks: breaksOf(form),
    note: form.note.trim() === '' ? null : form.note.trim(),
  };
}

/** The list, in date order, after a change is added or removed: the API's own order is by date too. */
export function withChange(list: MemberDateChange[], change: MemberDateChange): MemberDateChange[] {
  return [...list, change].sort((a, b) => a.date.localeCompare(b.date));
}
