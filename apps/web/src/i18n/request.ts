import { getRequestConfig } from 'next-intl/server';

import { defaultLocale } from './config';

/**
 * What next-intl uses for a request: the language and its messages. There is one language so far,
 * so nothing is read from the request yet.
 */
export default getRequestConfig(async () => {
  const locale = defaultLocale;
  return {
    locale,
    messages: (await import(`../../messages/${locale}.json`)).default,
  };
});
