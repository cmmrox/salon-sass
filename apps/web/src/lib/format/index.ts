/** Launch-market formatting, centralized so screens share configuration. */
export function formatInstant(value: string, timeZone: string, locale = 'en-LK'): string {
  return new Intl.DateTimeFormat(locale, { timeZone, dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value));
}
/** A calendar date in the zone (an invoice's period or due day). */
export function formatDate(value: string, timeZone: string, locale = 'en-LK'): string {
  return new Intl.DateTimeFormat(locale, { timeZone, dateStyle: 'medium' }).format(new Date(value));
}
export function formatPhone(value: string): string {
  return value.replace(/^\+94(7\d)(\d{3})(\d{4})$/, '+94 $1 $2 $3');
}
/** The viewer's own time zone, for screens that belong to no salon (the operator console). */
export function viewerTimeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
}

/**
 * An amount the API gave in minor units, in its currency: `Rs. 2,500` for whole rupees and the cents
 * only when they are not zero (docs/design/ui-ux-standards.md), other currencies by the locale's own
 * currency format with the same rule. Digits are split from the integer, never through floating point.
 */
export function formatMoney(minor: number, currency: string, locale = 'en-LK'): string {
  const negative = minor < 0;
  const units = Math.abs(Math.trunc(minor));
  const whole = Math.floor(units / 100);
  const cents = units % 100;
  if (currency === 'LKR') {
    const grouped = new Intl.NumberFormat(locale, { maximumFractionDigits: 0 }).format(whole);
    return `${negative ? '-' : ''}Rs. ${grouped}${cents ? `.${String(cents).padStart(2, '0')}` : ''}`;
  }
  const digits = cents ? 2 : 0;
  return new Intl.NumberFormat(locale, { style: 'currency', currency, minimumFractionDigits: digits, maximumFractionDigits: digits })
    .format(Number(`${negative ? '-' : ''}${whole}.${String(cents).padStart(2, '0')}`));
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
