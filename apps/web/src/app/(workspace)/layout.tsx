import type { Metadata } from 'next';
import { headers } from 'next/headers';
import type { ReactNode } from 'react';
import { decodeSalon, SALON_HEADER } from '@/features/branding/server';

/**
 * Salon staff pages block on the server (CR-013): entry is decided from the request's session
 * before the response starts, so no static shell or loading frame is painted before a redirect
 * or before the salon's own workspace.
 */
export const instant = false;

export async function generateMetadata(): Promise<Metadata> {
  const salon = decodeSalon((await headers()).get(SALON_HEADER));
  return salon ? { title: salon.name } : {};
}
/** Salon staff pages share the salon-branded responsive workspace frame. */
export default function WorkspaceLayout({ children }: { children: ReactNode }) {
  return children;
}
