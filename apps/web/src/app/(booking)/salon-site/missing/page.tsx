import type { Metadata } from 'next';
import { getTranslations } from 'next-intl/server';

import { SalonNotFound } from '@/components/salon/SalonNotFound';

/** "Salon not found", answered 404 by `proxy.ts` for a hostname that names no salon. */
export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations('salon.notFound');
  return { title: t('title') };
}

export default function SalonNotFoundPage() {
  return <SalonNotFound />;
}
