import type { Booking } from '../types';

/**
 * The salon's local date and time of an instant the API wrote in the salon's zone (`2086-06-15T10:30:00+05:30`): the
 * API gives every booking instant with the salon's own offset (F07-S01), so its date and clock are the salon's.
 */
export const localDate = (instant: string) => instant.slice(0, 10);
export const localClock = (instant: string) => instant.slice(11, 16);

/** A booking's day and its start and end on the salon's clock. */
export const whenOf = (booking: Pick<Booking, 'startsAt' | 'endsAt'>) => ({
  date: localDate(booking.startsAt),
  starts: localClock(booking.startsAt),
  ends: localClock(booking.endsAt),
});

/** Today's date in a time zone at an instant (the API's now, so the page follows its clock). */
export function dateInZone(timeZone: string, now: number): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(
    new Date(now),
  );
}

/** A date some days on (`2026-11-12` and 1 is `2026-11-13`). */
export function plusDays(date: string, days: number): string {
  const [year, month, day] = date.split('-').map(Number) as [number, number, number];
  return new Date(Date.UTC(year, month - 1, day + days)).toISOString().slice(0, 10);
}

/** The last day staff may book: a year from today (decision 108 (c)); 29 February a year on is 28 February. */
export function aYearOn(today: string): string {
  const [year, month, day] = today.split('-').map(Number) as [number, number, number];
  const date = new Date(Date.UTC(year + 1, month - 1, day));
  if (date.getUTCMonth() !== month - 1) date.setUTCDate(0);
  return date.toISOString().slice(0, 10);
}

/** A `YYYY-MM-DD` date as a local midnight, for the Calendar, and back. */
export const dateOfText = (value: string) => {
  const [year, month, day] = value.split('-').map(Number) as [number, number, number];
  return new Date(year, month - 1, day);
};
export const textOfDate = (date: Date) =>
  `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;

/** Whole seconds left until an instant, by a clock (never below 0). */
export const secondsLeft = (until: string, now: number) => Math.max(0, Math.ceil((Date.parse(until) - now) / 1000));

/** Seconds as the countdown shows them: `8:41`. */
export const countdownText = (seconds: number) =>
  `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;

/**
 * The whole minutes a countdown announces (AC-11): the count changes as each minute is reached (9 at 9:00, 1 at 1:00),
 * and the screen announces only a change, so a screen reader hears it at most once a minute, never every second.
 */
export const minutesLeft = (seconds: number) => Math.ceil(seconds / 60);
