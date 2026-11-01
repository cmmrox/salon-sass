import type { HolidayChoiceKind, HolidayDay, HolidayYear, HoursSettings, Weekday } from '../types';

/** The weekdays, Monday first, as the API names them. */
const WEEKDAYS = ['monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday', 'sunday'] as const;
export type WeekdayName = (typeof WEEKDAYS)[number];

/** Every quarter hour of the day, `00:00` to `23:45`: the times a Select offers. */
export const QUARTER_HOURS: string[] = Array.from({ length: 96 }, (_, index) => {
  const hours = String(Math.floor(index / 4)).padStart(2, '0');
  const minutes = String((index % 4) * 15).padStart(2, '0');
  return `${hours}:${minutes}`;
});

/** One weekday in the form: open or not, and its two times, kept while it is closed so reopening restores them. */
export type DayForm = { day: WeekdayName; open: boolean; opens: string; closes: string };

/**
 * The week the form suggests before a salon first saves (F04-S03 AC-1): Monday to Friday 09:00 to 18:00,
 * Saturday 09:00 to 19:00, Sunday closed. Shown only by the form; nothing is stored until the first save.
 */
export const SUGGESTED_WEEK: DayForm[] = WEEKDAYS.map((day) => ({
  day,
  open: day !== 'sunday',
  opens: '09:00',
  closes: day === 'saturday' ? '19:00' : '18:00',
}));

/** The form for the saved hours, or the suggested week when none are saved. */
export function weekFormOf(settings: HoursSettings): DayForm[] {
  if (!settings.weekly) return SUGGESTED_WEEK.map((day) => ({ ...day }));
  return WEEKDAYS.map((name) => {
    const saved = settings.weekly!.find((day: Weekday) => day.day === name);
    return {
      day: name,
      open: Boolean(saved?.open),
      opens: saved?.opens ?? '09:00',
      closes: saved?.closes ?? '18:00',
    };
  });
}

/** The weekdays whose closing time is not after their opening time; open days only. */
export function badDays(week: DayForm[]): WeekdayName[] {
  return week.filter((day) => day.open && day.closes <= day.opens).map((day) => day.day);
}

/** The save the form makes: each weekday, times only on an open day. */
export function weekSaveOf(week: DayForm[], version: number) {
  return {
    version,
    weekly: week.map((day) => ({
      day: day.day,
      open: day.open,
      opens: day.open ? day.opens : null,
      closes: day.open ? day.closes : null,
    })),
  };
}

/** Whether the form differs from what is saved (before the first save, from the suggested week). */
export function sameWeek(a: DayForm[], b: DayForm[]): boolean {
  return a.every((day, index) => {
    const other = b[index]!;
    return day.open === other.open && (!day.open || (day.opens === other.opens && day.closes === other.closes));
  });
}

/** The weekday an API field names (`weekly[2].closes`), or null. */
export function dayOfField(field: string): WeekdayName | null {
  const match = /^weekly\[(\d)\]/.exec(field);
  return match ? (WEEKDAYS[Number(match[1])] ?? null) : null;
}

/** One holiday's choice in the form. */
export type ChoiceForm = { choice: HolidayChoiceKind; opens: string; closes: string };

/** The default special hours: a short day. */
const SPECIAL_DEFAULT = { opens: '09:00', closes: '13:00' };

/**
 * The choice the form shows for a day: the salon's own, or Closed (the prototype's default) for a day it has
 * never decided.
 */
export function choiceFormOf(day: HolidayDay): ChoiceForm {
  return {
    choice: (day.choice as HolidayChoiceKind | null) ?? 'closed',
    opens: day.opens ?? SPECIAL_DEFAULT.opens,
    closes: day.closes ?? SPECIAL_DEFAULT.closes,
  };
}

/** The year's days still to come, which the card lists. */
export function comingDays(year: HolidayYear): HolidayDay[] {
  return year.days.filter((day) => !day.past);
}

/** A choice as the API takes it: times only for special hours. */
export function choiceSaveOf(date: string, form: ChoiceForm) {
  return {
    date,
    choice: form.choice,
    opens: form.choice === 'special' ? form.opens : null,
    closes: form.choice === 'special' ? form.closes : null,
  };
}

/** The year an acceptance should show: the current year when published, else the first published year. */
export function shownYear(years: HolidayYear[], today: string): HolidayYear | null {
  const current = Number(today.slice(0, 4));
  return years.find((year) => year.year === current) ?? years[0] ?? null;
}

/** The latest date a closure may have: a year from today (`today` is the salon's `YYYY-MM-DD`). */
export function lastClosableDate(today: string): string {
  const [year, month, day] = today.split('-').map(Number) as [number, number, number];
  const date = new Date(Date.UTC(year + 1, month - 1, day));
  // 29 February a year on is 1 March; the API counts it the same way (LocalDate.plusYears gives 28 February).
  if (date.getUTCMonth() !== month - 1) date.setUTCDate(0);
  return date.toISOString().slice(0, 10);
}
