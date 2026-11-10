import { parsePhoneNumberFromString } from 'libphonenumber-js/min';

/**
 * Launch-market formatting, centralized so screens share configuration.
 *
 * No date or time formatter throws on a value it cannot read (web M3): a value that is not a date or time is shown as
 * it came, and a missing one (null where a string was promised) as nothing, so that one malformed value from the API
 * costs its own cell, never the page.
 */
function readable(value: unknown, date: () => Date, format: (date: Date) => string): string {
  if (typeof value !== 'string') return '';
  const read = date();
  return Number.isNaN(read.getTime()) ? value : format(read);
}
export function formatInstant(value: string, timeZone: string, locale = 'en-LK'): string {
  return readable(
    value,
    () => new Date(value),
    (date) => new Intl.DateTimeFormat(locale, { timeZone, dateStyle: 'medium', timeStyle: 'short' }).format(date),
  );
}
/** A calendar date in the zone (an invoice's period or due day). */
export function formatDate(value: string, timeZone: string, locale = 'en-LK'): string {
  return readable(
    value,
    () => new Date(value),
    (date) => new Intl.DateTimeFormat(locale, { timeZone, dateStyle: 'medium' }).format(date),
  );
}
/** A time of day in the zone, on the 24-hour clock (`14:05`): a support session's end. */
export function formatTime(value: string, timeZone: string, locale = 'en-LK'): string {
  return readable(
    value,
    () => new Date(value),
    (date) =>
      new Intl.DateTimeFormat(locale, { timeZone, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(date),
  );
}
/**
 * A salon's local time of day, as the API writes it (`09:30`), on the configured clock: a weekday's opening
 * time (F04-S03). No time zone applies: it is already the salon's own.
 */
export function formatClock(value: string, locale = 'en-LK'): string {
  return readable(
    value,
    () => {
      const [hours, minutes] = value.split(':').map(Number);
      return new Date(Date.UTC(2000, 0, 1, hours, minutes));
    },
    (date) => clockFormat(locale).format(date),
  );
}
/**
 * One clock formatter per locale, made once: building an `Intl.DateTimeFormat` costs far more than using one,
 * and a time Select formats each of its 96 quarter hours.
 */
const clockFormats = new Map<string, Intl.DateTimeFormat>();
function clockFormat(locale: string): Intl.DateTimeFormat {
  let format = clockFormats.get(locale);
  if (!format) {
    format = new Intl.DateTimeFormat(locale, { timeZone: 'UTC', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });
    clockFormats.set(locale, format);
  }
  return format;
}
/**
 * A salon's local date, as the API writes it (`2026-10-25`), with its weekday: a holiday or a closure
 * (F04-S03). No time zone applies: it is already the salon's own date.
 */
export function formatDay(value: string, locale = 'en-LK'): string {
  return readable(
    value,
    () => new Date(`${value}T00:00:00Z`),
    (date) =>
      new Intl.DateTimeFormat(locale, {
        timeZone: 'UTC',
        weekday: 'short',
        day: 'numeric',
        month: 'short',
        year: 'numeric',
      }).format(date),
  );
}
/** One short-day formatter per locale and form, made once: a list formats every row's dates. */
const shortDayFormats = new Map<string, Intl.DateTimeFormat>();
function shortDayFormat(locale: string, withYear: boolean): Intl.DateTimeFormat {
  const key = `${locale}|${withYear}`;
  let format = shortDayFormats.get(key);
  if (!format) {
    format = new Intl.DateTimeFormat(locale, {
      timeZone: 'UTC',
      weekday: 'short',
      day: 'numeric',
      month: 'short',
      ...(withYear ? { year: 'numeric' } : {}),
    });
    shortDayFormats.set(key, format);
  }
  return format;
}
const yearOf = (value: string) => (typeof value === 'string' ? value.slice(0, 4) : '');
const shortDay = (value: string, withYear: boolean, locale: string) =>
  readable(
    value,
    () => new Date(`${value}T00:00:00Z`),
    (date) => shortDayFormat(locale, withYear).format(date),
  );
/**
 * A salon's local date in the Team page's short form (decision 107): without the year when it falls in the year of the
 * salon's `today` (`Thu, Nov 12`), and otherwise with it, as `formatDay` writes it (`Tue, Jan 5, 2027`). Both are the
 * salon's own dates (`2026-11-12`), so no time zone applies.
 */
export function formatShortDay(value: string, today: string, locale = 'en-LK'): string {
  return shortDay(value, yearOf(value) !== yearOf(today), locale);
}
/**
 * A period's first and last day in the short form of decision 107: both without the year when both fall in the year of
 * the salon's `today`, and both with it when either end is in another year.
 */
export function formatShortPeriod(
  first: string,
  last: string,
  today: string,
  locale = 'en-LK',
): { first: string; last: string } {
  const withYear = yearOf(first) !== yearOf(today) || yearOf(last) !== yearOf(today);
  return { first: shortDay(first, withYear, locale), last: shortDay(last, withYear, locale) };
}
/**
 * A phone number the API stored in E.164, in its own country's national format (decision 084):
 * `+94771234567` is `077 123 4567`, `+94112504417` is `0112 504 417`. The country comes from the
 * number itself, so no region is configured here. Anything that is not a valid E.164 number is
 * returned unchanged.
 */
export function formatPhoneNational(e164: string): string {
  const number = parsePhoneNumberFromString(e164);
  return number && number.isValid() ? number.formatNational() : e164;
}
export function formatPhone(value: string): string {
  return value.replace(/^\+94(7\d)(\d{3})(\d{4})$/, '+94 $1 $2 $3');
}
/** A whole count, such as a plan's staff seats or message allowance, grouped for the locale: `1,000`. */
export function formatCount(value: number, locale = 'en-LK'): string {
  return value.toLocaleString(locale);
}
/** Today's calendar date in the zone, as a date field's value (`2026-10-30`). */
export function todayInZone(timeZone: string, now = new Date()): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(now);
}
/** The viewer's own time zone, for screens that belong to no salon (the operator console). */
export function viewerTimeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
}

/**
 * Whether an amount shows its cents only when they are not zero (`Rs. 2,500`, the default), or always (`Rs.
 * 381.00`), as the parts a tax-inclusive price includes are shown (F05-S03).
 */
export type MoneyCents = 'when-not-zero' | 'always';

/**
 * An amount the API gave in minor units, in its currency: `Rs. 2,500` for whole rupees and the cents
 * only when they are not zero (docs/design/ui-ux-standards.md), other currencies by the locale's own
 * currency format with the same rule; with `cents: 'always'`, the cents even when they are zero. Digits are
 * split from the integer, never through floating point.
 */
export function formatMoney(
  minor: number,
  currency: string,
  locale = 'en-LK',
  { cents: shown = 'when-not-zero' }: { cents?: MoneyCents } = {},
): string {
  const negative = minor < 0;
  const units = Math.abs(Math.trunc(minor));
  const whole = Math.floor(units / 100);
  const cents = units % 100;
  const withCents = cents !== 0 || shown === 'always';
  if (currency === 'LKR') {
    const grouped = new Intl.NumberFormat(locale, { maximumFractionDigits: 0 }).format(whole);
    return `${negative ? '-' : ''}Rs. ${grouped}${withCents ? `.${String(cents).padStart(2, '0')}` : ''}`;
  }
  const digits = withCents ? 2 : 0;
  return new Intl.NumberFormat(locale, {
    style: 'currency',
    currency,
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  }).format(Number(`${negative ? '-' : ''}${whole}.${String(cents).padStart(2, '0')}`));
}

/**
 * What an operator typed as an amount in major units (`2500`, `2,500.50`), as whole minor units for
 * the API, by moving the decimal point in the text. Anything else is returned unchanged, so the API
 * answers it with its own field error.
 */
export function parseMinorUnits(text: string): number | string {
  const value = text.trim().replace(/,/g, '');
  const match = /^(\d{1,9})(?:\.(\d{1,2}))?$/.exec(value);
  if (!match) return text;
  return Number(`${match[1]}${(match[2] ?? '').padEnd(2, '0')}`);
}

/** Minor units as editable major-unit text (`250050` becomes `2500.50`, `250000` becomes `2500`). */
export function minorUnitsText(minor: number): string {
  const whole = Math.floor(minor / 100);
  const cents = minor % 100;
  return cents ? `${whole}.${String(cents).padStart(2, '0')}` : String(whole);
}

/**
 * How an amount field marks its currency beside the typed whole units (F04-S04): `Rs.` for rupees, as
 * `formatMoney` writes them, and the ISO code for any other currency.
 */
export function currencyMark(currency: string): string {
  return currency === 'LKR' ? 'Rs.' : currency;
}

/** A file size the API gave in bytes, in the locale's units (B, kB, MB), for an export archive. */
export function formatBytes(bytes: number, locale = 'en-LK'): string {
  const units = ['byte', 'kilobyte', 'megabyte', 'gigabyte'] as const;
  let value = bytes;
  let unit = 0;
  while (value >= 1000 && unit < units.length - 1) {
    value /= 1000;
    unit++;
  }
  return new Intl.NumberFormat(locale, {
    style: 'unit',
    unit: units[unit],
    unitDisplay: 'short',
    maximumFractionDigits: unit === 0 ? 0 : 1,
  }).format(value);
}
