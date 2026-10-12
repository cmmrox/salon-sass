import type { Metadata } from 'next';
import { getTranslations } from 'next-intl/server';

import { SalonUnavailable } from '@/components/salon/SalonUnavailable';

/** "Temporarily unavailable", answered 503 by `proxy.ts` for a suspended salon's hostname. */
export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations('salon.unavailable');
  return { title: t('title') };
}

export default function SalonSuspendedPage() {
  return <SalonUnavailable />;
}
