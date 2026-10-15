import { Suspense } from 'react';

import { AppShell } from '@/components/shell/AppShell';
import { LiveStatus } from '@/components/status/LiveStatus';
import { StatusBody, StatusPanel } from '@/components/status/Status';

const HOME = '/';

/**
 * The status page, on the bare platform domain: it proves the path from the API's controller,
 * through the generated client, to a screen. A salon's hostname enters its staff workspace through `proxy.ts`.
 */
export default function HomePage() {
  return (
    <AppShell>
      <StatusPanel>
        <Suspense fallback={<StatusBody state={{ kind: 'loading' }} retryHref={HOME} />}>
          <LiveStatus retryHref={HOME} />
        </Suspense>
      </StatusPanel>
    </AppShell>
  );
}
