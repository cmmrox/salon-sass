import type { ReactNode } from 'react';

import { AppShell } from '@/components/shell/AppShell';

import styles from './Notice.module.css';

/**
 * A page that only says something: a title, a sentence, and what the person can do. In the
 * platform's neutral theme, never a salon's. Its test ids are `<testId>`, `<testId>-title` and
 * `<testId>-body`.
 */
export function Notice({
  testId,
  title,
  body,
  children,
}: {
  testId: string;
  title: string;
  body: string;
  children?: ReactNode;
}) {
  return (
    <AppShell>
      <div className={styles.notice} data-testid={testId}>
        <h1 className={styles.title} data-testid={`${testId}-title`}>
          {title}
        </h1>
        <p data-testid={`${testId}-body`}>{body}</p>
        {children}
      </div>
    </AppShell>
  );
}
