import type { Salon } from '@salon/api-contracts';
import { headers } from 'next/headers';

import { SALON_HEADER, decodeSalon } from '@/lib/tenancy/salon-header';

/**
 * The salon `proxy.ts` resolved from this request's hostname, or null. Read from the request on
 * every call; never cached.
 */
export async function requestSalon(): Promise<Salon | null> {
  return decodeSalon((await headers()).get(SALON_HEADER));
}
