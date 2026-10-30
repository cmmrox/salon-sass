import type { Metadata } from 'next';
import { getTranslations } from 'next-intl/server';
import type { ReactNode } from 'react';
import { BrandStyle } from '@/components/shared/BrandStyle';

/**
 * The operator console, at its reserved hostname that never resolves to a salon
 * (docs/architecture/operator-console.md). Its pages decide entry on the server before anything is
 * sent. It belongs to no salon, so it sets the platform's neutral accent (decision 047) and never a
 * salon's brand.
 */
export const instant = false;

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations('console');
  return { title: t('title') };
}

export default function ConsoleLayout({ children }: { children: ReactNode }) {
  return <><BrandStyle neutral />{children}</>;
}
