import { parsePhoneNumberFromString } from 'libphonenumber-js/mobile';

/**
 * Whether what a person typed is a mobile number (decision 084): valid for the configured region (`LK` by default),
 * in any common form (`077 123 4567`, `0771234567`, `+94 77 123 4567`), and a mobile, not a landline. The API checks
 * it again and stores it in E.164; this is the screen's convenience check before anything is sent. Its own entry, so
 * that only the screens that check a mobile load the mobile numbering plans.
 */
export function isMobileNumber(value: string, region = 'LK'): boolean {
  const typed = value.trim();
  if (!/^\+?[\d\s()-]{6,20}$/.test(typed)) return false;
  const number = parsePhoneNumberFromString(
    typed,
    region.toUpperCase() as Parameters<typeof parsePhoneNumberFromString>[1],
  );
  return Boolean(number && number.isValid());
}
