import type { Salon } from '@/lib/api/salon';
import { useTranslations } from 'next-intl';

import { brandRootRule } from '@/styles/brand';

import styles from './SalonShell.module.css';

/**
 * The public shell of an active salon: its name, as the page's main heading, on its brand colour.
 * The brand reaches the page as two custom properties on the root (`--color-brand` and
 * `--color-on-brand`, styles/brand.ts); nothing here names a colour. Services, booking and
 * navigation arrive in later stages.
 */
export function SalonShell({ salon }: { salon: Salon }) {
  return (
    <>
      <style data-testid="salon-brand">{brandRootRule(salon.brandColour)}</style>
      <div className={styles.shell} data-testid="salon-shell">
        <header className={styles.banner} data-testid="salon-header">
          <div className={styles.inner}>
            <h1 className={styles.name} data-testid="salon-name">
              {salon.name}
            </h1>
          </div>
        </header>
        <main className={styles.main} data-testid="salon-main" />
      </div>
    </>
  );
}

/** The shell while the salon is being resolved: the header's shape, and never any salon's brand. */
export function SalonShellLoading() {
  const t = useTranslations('salon');
  return (
    <div className={styles.shell} role="status" data-testid="salon-loading">
      <span className={styles.visuallyHidden}>{t('loading')}</span>
      <div className={styles.bannerLoading} aria-hidden="true">
        <div className={styles.inner}>
          <span className={styles.nameLoading} />
        </div>
      </div>
    </div>
  );
}
