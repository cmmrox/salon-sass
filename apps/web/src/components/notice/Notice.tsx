import type { ReactNode } from 'react';

import { AppShell } from '@/components/shell/AppShell';
import { Card, CardTitle } from '@/components/ui/card';

/**
 * A page that only says something: a title, a sentence, and what the person can do. In the
 * platform's neutral theme, never a salon's. Its test ids are `<testId>`, `<testId>-title` and
 * `<testId>-body`. The proxy's plain pages repeat this markup (lib/plain-page).
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
      <Card className="gap-3" data-testid={testId}>
        <CardTitle asChild className="wrap-anywhere">
          <h1 data-testid={`${testId}-title`}>{title}</h1>
        </CardTitle>
        <p data-testid={`${testId}-body`}>{body}</p>
        {children}
      </Card>
    </AppShell>
  );
}
