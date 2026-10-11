import { useTranslations } from 'next-intl';
import type { ReactNode } from 'react';

import styles from './AppShell.module.css';

/** The frame every page of this stage sits in: the product's name above the page's content. */
export function AppShell({ children }: { children: ReactNode }) {
  const t = useTranslations('app');
  return (
    <div className={styles.shell}>
      <header className={styles.header}>
        <p className={styles.name} data-testid="app-name">
          {t('name')}
        </p>
      </header>
      <main className={styles.main}>{children}</main>
    </div>
  );
}
