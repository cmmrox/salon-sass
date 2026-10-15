import type { Metadata } from 'next';
import { headers } from 'next/headers';
import type { ReactNode } from 'react';
import { decodeSalon, SALON_HEADER } from '@/lib/tenancy/salon-header';

export async function generateMetadata(): Promise<Metadata> {
  const salon = decodeSalon((await headers()).get(SALON_HEADER));
  return salon ? { title: salon.name } : {};
}
/** Salon staff pages share the neutral responsive workspace frame. */
export default function WorkspaceLayout({ children }: { children: ReactNode }) { return children; }
