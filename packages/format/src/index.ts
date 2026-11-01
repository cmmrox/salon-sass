import { parsePhoneNumberFromString } from 'libphonenumber-js/min';

/** Launch-market formatting, centralized so screens share configuration. */
export function formatInstant(value: string, timeZone: string, locale = 'en-LK'): string {
  return new Intl.DateTimeFormat(locale, { timeZone, dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value));
}
/** A calendar date in the zone (an invoice's period or due day). */
export function formatDate(value: string, timeZone: string, locale = 'en-LK'): string {
  return new Intl.DateTimeFormat(locale, { timeZone, dateStyle: 'medium' }).format(new Date(value));
}
/** A time of day in the zone, on the 24-hour clock (`14:05`): a support session's end. */
export function formatTime(value: string, timeZone: string, locale = 'en-LK'): string {
  return new Intl.DateTimeFormat(locale, { timeZone, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(
    new Date(value),
  );
}
/**
 * A salon's local time of day, as the API writes it (`09:30`), on the configured clock: a weekday's opening
 * time (F04-S03). No time zone applies: it is already the salon's own.
 */
export function formatClock(value: string, locale = 'en-LK'): string {
  const [hours, minutes] = value.split(':').map(Number);
  return new Intl.DateTimeFormat(locale, {
    timeZone: 'UTC',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).format(new Date(Date.UTC(2000, 0, 1, hours, minutes)));
}
/**
 * A salon's local date, as the API writes it (`2026-10-25`), with its weekday: a holiday or a closure
 * (F04-S03). No time zone applies: it is already the salon's own date.
 */
export function formatDay(value: string, locale = 'en-LK'): string {
  return new Intl.DateTimeFormat(locale, {
    timeZone: 'UTC',
    weekday: 'short',
    day: 'numeric',
    month: 'short',
    year: 'numeric',
  }).format(new Date(`${value}T00:00:00Z`));
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
