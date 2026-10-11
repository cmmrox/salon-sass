import Link from 'next/link';
import { useTranslations } from 'next-intl';

import { AppShell } from '@/components/shell/AppShell';

import styles from './not-found.module.css';

export default function NotFound() {
  const t = useTranslations('notFound');
  return (
    <AppShell>
      <div className={styles.notFound} data-testid="not-found">
        <h1 className={styles.title} data-testid="not-found-title">
          {t('title')}
        </h1>
        <p>{t('body')}</p>
        <Link className={styles.home} href="/" data-testid="not-found-home">
          {t('home')}
        </Link>
      </div>
    </AppShell>
  );
}
