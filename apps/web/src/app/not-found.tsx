import Link from 'next/link';
import { useTranslations } from 'next-intl';

import { Notice } from '@/components/notice/Notice';

import styles from './not-found.module.css';

export default function NotFound() {
  const t = useTranslations('notFound');
  return (
    <Notice testId="not-found" title={t('title')} body={t('body')}>
      <Link className={styles.home} href="/" data-testid="not-found-home">
        {t('home')}
      </Link>
    </Notice>
  );
}
