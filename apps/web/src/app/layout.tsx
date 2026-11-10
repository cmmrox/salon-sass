import type { Metadata } from 'next';
import { NextIntlClientProvider } from 'next-intl';
import { connection } from 'next/server';
import { getLocale, getTranslations } from 'next-intl/server';
import type { ReactNode } from 'react';

import './globals.css';

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations('app');
  return { title: t('name') };
}

/**
 * Every page is rendered for its request, none prerendered at build: the content security policy allows a script only
 * with the nonce the proxy makes for each request, which Next.js can put only on what it renders then (web M7; the
 * installed guide, "How nonces work in Next.js"). The workspace and the console render per request already.
 */
export const instant = false;

export default async function RootLayout({ children }: { children: ReactNode }) {
  await connection();
  const locale = await getLocale();
  return (
    <html lang={locale}>
      <body>
        <NextIntlClientProvider>{children}</NextIntlClientProvider>
      </body>
    </html>
  );
}
