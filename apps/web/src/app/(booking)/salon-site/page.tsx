import type { Metadata } from 'next';
import { notFound } from 'next/navigation';
import { Suspense } from 'react';

import { SalonShell, SalonShellLoading } from '@/components/salon/SalonShell';

import { requestSalon } from './salon';

/**
 * An active salon's shell, at `/` on its hostname. `proxy.ts` resolves the salon and rewrites here;
 * the page reads what it resolved from this request alone. The loading skeleton is served first and
 * the salon streams in.
 */
export async function generateMetadata(): Promise<Metadata> {
  const salon = await requestSalon();
  return salon ? { title: salon.name } : {};
}

async function Shell() {
  const salon = await requestSalon();
  if (!salon) {
    // Reached without the proxy having resolved a salon: there is nothing to show.
    notFound();
  }
  return <SalonShell salon={salon} />;
}

export default function SalonPage() {
  return (
    <Suspense fallback={<SalonShellLoading />}>
      <Shell />
    </Suspense>
  );
}
