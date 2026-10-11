import type messages from '../../messages/en.json';
import type { Locale } from './config';

// Makes a translation key that is not in messages/en.json a type error.
declare module 'next-intl' {
  interface AppConfig {
    Locale: Locale;
    Messages: typeof messages;
  }
}
