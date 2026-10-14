/** Launch-market formatting, centralized so screens share configuration. */
export function formatInstant(value: string, timeZone: string, locale = 'en-LK'): string {
  return new Intl.DateTimeFormat(locale, { timeZone, dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value));
}
export function formatPhone(value: string): string {
  return value.replace(/^\+94(7\d)(\d{3})(\d{4})$/, '+94 $1 $2 $3');
}
