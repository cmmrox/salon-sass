/**
 * The languages the web app has translation files for. English first; Sinhala and Tamil follow
 * (docs/design/ui-ux-standards.md). A language is added by adding `messages/<locale>.json` and
 * listing it here.
 */
export const locales = ['en'] as const;

export type Locale = (typeof locales)[number];

export const defaultLocale: Locale = 'en';
