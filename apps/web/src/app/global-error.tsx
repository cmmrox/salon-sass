'use client';
import { NextIntlClientProvider } from 'next-intl';

import { ErrorNotice } from '@/components/shared/ErrorNotice';

import messages from '../../messages/en.json';
import './globals.css';

/**
 * The root layout itself failed (web M3): this replaces it, so it renders its own document, imports the application's
 * one style sheet (as the root layout does) and carries only the messages it says. The locale is the application's
 * one locale (`src/i18n/request.ts`).
 */
export default function GlobalError({ retry }: { error: Error & { digest?: string }; retry: () => void }) {
  return (
    <html lang="en">
      <body>
        <NextIntlClientProvider locale="en" messages={{ app: messages.app, errorPage: messages.errorPage }}>
          <ErrorNotice retry={retry} />
        </NextIntlClientProvider>
      </body>
    </html>
  );
}
