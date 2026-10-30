import Link from 'next/link';
import { useTranslations } from 'next-intl';

import { Notice } from '@/components/shared/Notice';

export default function NotFound() {
  const t = useTranslations('notFound');
  return (
    <Notice testId="not-found" title={t('title')} body={t('body')}>
      <Link
        className="inline-flex min-h-touch items-center self-start font-semibold text-foreground"
        href="/"
        data-testid="not-found-home"
      >
        {t('home')}
      </Link>
    </Notice>
  );
}
