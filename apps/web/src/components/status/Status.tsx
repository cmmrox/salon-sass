import { useTranslations } from 'next-intl';
import type { ReactNode } from 'react';

import { Alert, AlertTitle } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Card, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';

/** What the status page knows about the API at this moment. */
export type StatusState =
  | { kind: 'loading' }
  | { kind: 'loaded'; service: string; version: string }
  | { kind: 'unavailable' };

/** The frame of the status page: its heading, and whichever state is inside. */
export function StatusPanel({ children }: { children: ReactNode }) {
  const t = useTranslations('status');
  return (
    <Card asChild>
      <section aria-labelledby="status-heading" data-testid="status-panel">
        <CardTitle asChild>
          <h1 id="status-heading" data-testid="status-heading">
            {t('heading')}
          </h1>
        </CardTitle>
        {children}
      </section>
    </Card>
  );
}

/** One of the three states: loading, loaded or unavailable. */
export function StatusBody({ state, retryHref }: { state: StatusState; retryHref: string }) {
  const t = useTranslations('status');

  if (state.kind === 'loading') {
    return (
      <div className="flex flex-col gap-3" role="status" data-testid="status-loading">
        <span className="sr-only">{t('loading')}</span>
        <Skeleton aria-hidden="true" />
        <Skeleton aria-hidden="true" />
        <Skeleton aria-hidden="true" />
      </div>
    );
  }

  if (state.kind === 'unavailable') {
    return (
      <Alert variant="caution" data-testid="status-unavailable">
        <AlertTitle asChild>
          <h2 data-testid="status-unavailable-title">{t('unavailable.title')}</h2>
        </AlertTitle>
        <p data-testid="status-unavailable-body">{t('unavailable.body')}</p>
        {/* A plain link, not a client-side one: a full reload makes the page ask the API again. */}
        <Button asChild variant="outline" size="sm" className="self-start bg-card font-semibold">
          <a href={retryHref} data-testid="status-retry">
            {t('unavailable.retry')}
          </a>
        </Button>
      </Alert>
    );
  }

  return (
    <div className="flex flex-col gap-3" data-testid="status-loaded">
      {/* The positive Alert: ink on the positive surface, since the positive text colour is not AA
          there (BUG-F01-003). A status, not an alert: it says nothing went wrong. */}
      <Alert variant="positive" role="status" data-testid="status-summary">
        <AlertTitle asChild>
          <p>{t('loaded.summary')}</p>
        </AlertTitle>
      </Alert>
      <dl className="flex flex-col gap-2">
        <div className="flex flex-wrap justify-between gap-x-4 gap-y-1">
          <dt className="text-muted-foreground">{t('loaded.service')}</dt>
          <dd className="font-semibold wrap-anywhere" data-testid="status-api-service">
            {state.service}
          </dd>
        </div>
        <div className="flex flex-wrap justify-between gap-x-4 gap-y-1">
          <dt className="text-muted-foreground">{t('loaded.version')}</dt>
          <dd className="font-semibold wrap-anywhere" data-testid="status-api-version">
            {state.version}
          </dd>
        </div>
      </dl>
    </div>
  );
}
