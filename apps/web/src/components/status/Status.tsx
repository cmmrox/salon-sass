import { useTranslations } from 'next-intl';
import type { ReactNode } from 'react';

import styles from './Status.module.css';

/** What the status page knows about the API at this moment. */
export type StatusState =
  | { kind: 'loading' }
  | { kind: 'loaded'; service: string; version: string }
  | { kind: 'unavailable' };

/** The frame of the status page: its heading, and whichever state is inside. */
export function StatusPanel({ children }: { children: ReactNode }) {
  const t = useTranslations('status');
  return (
    <section className={styles.panel} aria-labelledby="status-heading" data-testid="status-panel">
      <h1 id="status-heading" className={styles.heading} data-testid="status-heading">
        {t('heading')}
      </h1>
      {children}
    </section>
  );
}

/** One of the three states: loading, loaded or unavailable. */
export function StatusBody({ state, retryHref }: { state: StatusState; retryHref: string }) {
  const t = useTranslations('status');

  if (state.kind === 'loading') {
    return (
      <div className={styles.loading} role="status" data-testid="status-loading">
        <span className={styles.visuallyHidden}>{t('loading')}</span>
        <span className={styles.skeletonLine} aria-hidden="true" />
        <span className={styles.skeletonLine} aria-hidden="true" />
        <span className={styles.skeletonLine} aria-hidden="true" />
      </div>
    );
  }

  if (state.kind === 'unavailable') {
    return (
      <div className={styles.unavailable} role="alert" data-testid="status-unavailable">
        <h2 className={styles.stateTitle} data-testid="status-unavailable-title">
          {t('unavailable.title')}
        </h2>
        <p>{t('unavailable.body')}</p>
        {/* A plain link, not a client-side one: a full reload makes the page ask the API again. */}
        <a className={styles.action} href={retryHref} data-testid="status-retry">
          {t('unavailable.retry')}
        </a>
      </div>
    );
  }

  return (
    <div className={styles.loaded} data-testid="status-loaded">
      <p className={styles.summary} data-testid="status-summary">
        {t('loaded.summary')}
      </p>
      <dl className={styles.facts}>
        <div className={styles.fact}>
          <dt className={styles.factName}>{t('loaded.service')}</dt>
          <dd className={styles.factValue} data-testid="status-api-service">
            {state.service}
          </dd>
        </div>
        <div className={styles.fact}>
          <dt className={styles.factName}>{t('loaded.version')}</dt>
          <dd className={styles.factValue} data-testid="status-api-version">
            {state.version}
          </dd>
        </div>
      </dl>
    </div>
  );
}
