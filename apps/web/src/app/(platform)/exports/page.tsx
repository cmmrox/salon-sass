import type { Metadata } from 'next';
import { getTranslations } from 'next-intl/server';
import { AppShell } from '@/components/shell/AppShell';
import { BrandStyle } from '@/components/shell/BrandStyle';
import { ExportDownload } from '@/components/platform/ExportDownload';

/**
 * The Owner's export download page, on the bare platform host only (F03-S03): `/exports#<token>`. The
 * token is in the fragment, which never reaches a server; the page reads it, removes it from the
 * address and asks the platform host's BFF. It belongs to no salon, so it uses the neutral accent.
 */
export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations('exports');
  return { title: t('title'), referrer: 'no-referrer' };
}

export default function ExportsPage() {
  return (
    <>
      <BrandStyle neutral />
      <AppShell>
        <ExportDownload />
      </AppShell>
    </>
  );
}
