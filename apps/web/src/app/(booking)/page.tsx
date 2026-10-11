import { Suspense } from 'react';

import { AppShell } from '@/components/shell/AppShell';
import { LiveStatus } from '@/components/status/LiveStatus';
import { StatusBody, StatusPanel } from '@/components/status/Status';

const HOME = '/';

/**
 * The status page: scaffolding that proves the path from the API's controller, through the
 * generated client, to a screen. The branded shell replaces it.
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
