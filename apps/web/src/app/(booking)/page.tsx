import { Suspense } from 'react';

import { AppShell } from '@/components/shell/AppShell';
import { LiveStatus } from '@/components/status/LiveStatus';
import { StatusBody, StatusPanel } from '@/components/status/Status';

const HOME = '/';

/**
 * The status page, on the bare platform domain: it proves the path from the API's controller,
 * through the generated client, to a screen. A salon's hostname is rewritten by `proxy.ts` to the
 * salon's own pages under `salon-site/`.
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
