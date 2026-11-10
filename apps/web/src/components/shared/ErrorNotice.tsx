'use client';
import Link from 'next/link';
import { useTranslations } from 'next-intl';

import { Button } from '@/components/ui/button';

import { Notice } from './Notice';

/**
 * What a page that failed while rendering shows instead of a blank screen or the framework's untranslated crash
 * (docs/engineering/web.md, "Structure"; web M3): it says so in plain words, keeps the person's saved work, and offers
 * to try again (the boundary's `retry`, which reads the page again) or to go back to the start. Never the error's own
 * message or a code. In the platform's neutral theme, like every Notice.
 */
export function ErrorNotice({ retry }: { retry: () => void }) {
  const t = useTranslations('errorPage');
  return (
    <Notice testId="error-page" title={t('title')} body={t('body')}>
      <div className="flex flex-wrap items-center gap-4">
        <Button variant="outline" onClick={() => retry()} data-testid="error-page-retry">
          {t('retry')}
        </Button>
        <Link
          className="inline-flex min-h-touch items-center font-semibold text-foreground"
          href="/"
          data-testid="error-page-home"
        >
          {t('home')}
        </Link>
      </div>
    </Notice>
  );
}
