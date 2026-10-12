import { useTranslations } from 'next-intl';

import { Notice } from '@/components/notice/Notice';

/**
 * What a hostname that names no salon shows. The same for every such hostname, and nothing on it
 * says whether a salon exists anywhere.
 */
export function SalonNotFound() {
  const t = useTranslations('salon.notFound');
  return <Notice testId="salon-not-found" title={t('title')} body={t('body')} />;
}
