import type { Metadata } from 'next';
import { getTranslations } from 'next-intl/server';

import { AppShell } from '@/components/shell/AppShell';
import { StatusBody, StatusPanel } from '@/components/status/Status';

/**
 * The "service unavailable" state of the status page, answered 503 by `proxy.ts` when a salon's
 * hostname cannot be resolved because the API cannot be reached. Nothing of any salon.
 */
export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations('status.unavailable');
  return { title: t('title') };
}

export default function ServiceUnavailablePage() {
  return (
    <AppShell>
      <StatusPanel>
        {/* Try again asks for the same address, which resolves the salon again. */}
        <StatusBody state={{ kind: 'unavailable' }} retryHref="/" />
      </StatusPanel>
    </AppShell>
  );
}
