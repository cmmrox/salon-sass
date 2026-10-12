import { useTranslations } from 'next-intl';

import { Notice } from '@/components/notice/Notice';

/** What a suspended salon's hostname shows: nothing of the salon, in the customer's terms. */
export function SalonUnavailable() {
  const t = useTranslations('salon.unavailable');
  return <Notice testId="salon-unavailable" title={t('title')} body={t('body')} />;
}
