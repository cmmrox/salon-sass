import { useSyncExternalStore } from 'react';
import type { Booking, BookingDetail, BookingPage } from '@/features/booking';

/**
 * Synthetic data for the Bookings page's tests (F07-S03): alpha on 15 June 2086 at 08:00 in Colombo by the API's clock,
 * two professionals, three services (one with variants) and a day of a confirmed booking, a hold and a cancellation.
 */
export const NOW = '2086-06-15T02:30:00Z';
export const TODAY = '2086-06-15';
export const SANDUNI = { id: '11111111-0000-4000-8000-000000000001', name: 'Sanduni Wickramasinghe' };
export const ISHARA = { id: '11111111-0000-4000-8000-000000000002', name: 'Ishara Fonseka' };
const CUT = '22222222-0000-4000-8000-000000000001';
const COLOUR = '22222222-0000-4000-8000-000000000002';
const HAIR = '33333333-0000-4000-8000-000000000001';

const terms = (
  priceMinMinor: number,
  priceMaxMinor: number,
  variants: { id: string; name: string; priceMinor: number }[] = [],
) => ({
  currency: 'LKR',
  priceMinMinor,
  priceMaxMinor,
  variantSetName: variants.length ? 'Hair length' : null,
  variants,
});
export const SERVICES = {
  items: [
    { id: CUT, name: 'Cut and blow-dry', categoryId: HAIR, terms: terms(450_000, 450_000) },
    {
      id: COLOUR,
      name: 'Hair colour',
      categoryId: '33333333-0000-4000-8000-000000000002',
      terms: terms(600_000, 900_000, [
        { id: '44444444-0000-4000-8000-000000000001', name: 'Short', priceMinor: 600_000 },
        { id: '44444444-0000-4000-8000-000000000002', name: 'Long', priceMinor: 900_000 },
      ]),
    },
  ],
};
export const TEAM = {
  items: [
    { ...SANDUNI, services: { categoryIds: [HAIR], serviceIds: [COLOUR] } },
    { ...ISHARA, services: { categoryIds: [], serviceIds: [CUT] } },
  ],
};

export function booking(overrides: Partial<Booking> & Pick<Booking, 'id' | 'reference'>): Booking {
  return {
    state: 'confirmed',
    startsAt: '2086-06-15T10:00:00+05:30',
    endsAt: '2086-06-15T11:00:00+05:30',
    holdExpiresAt: null,
    member: SANDUNI,
    service: { id: CUT, name: 'Cut and blow-dry', variantId: null, variantName: null },
    customer: { name: 'Amaya Rajapaksa', mobile: '071 765 4321' },
    channel: 'whatsapp',
    priceMinor: 450_000,
    currency: 'LKR',
    createdAt: '2086-06-15T07:50:00+05:30',
    ...overrides,
  };
}
export const AMAYA = booking({ id: '55555555-0000-4000-8000-000000000001', reference: '2ZE-GC7' });
export const KAVINDI = booking({
  id: '55555555-0000-4000-8000-000000000002',
  reference: 'W6S-2Z5',
  state: 'held',
  startsAt: '2086-06-15T11:00:00+05:30',
  endsAt: '2086-06-15T12:00:00+05:30',
  holdExpiresAt: '2086-06-15T08:10:00+05:30',
  member: ISHARA,
  customer: { name: 'Kavindi Herath', mobile: '077 222 3344' },
  channel: 'phone',
});
export const NETHMI = booking({
  id: '55555555-0000-4000-8000-000000000003',
  reference: 'Y3H-5TT',
  state: 'cancelled',
  startsAt: '2086-06-15T12:00:00+05:30',
  endsAt: '2086-06-15T13:00:00+05:30',
  customer: { name: 'Nethmi Silva', mobile: '076 555 1212' },
  channel: 'front_desk',
});
export const page = (items: Booking[], total = items.length): BookingPage => ({ items, page: 0, size: 50, total });

export const DETAIL: BookingDetail = {
  booking: AMAYA,
  history: [
    {
      at: '2086-06-15T07:50:00+05:30',
      from: null,
      to: 'held',
      by: { kind: 'staff', label: 'dilani.fernando@alphasalon.lk' },
      reason: null,
      note: null,
    },
    {
      at: '2086-06-15T07:51:00+05:30',
      from: 'held',
      to: 'confirmed',
      by: { kind: 'staff', label: null },
      reason: null,
      note: null,
    },
  ],
  allowedActions: ['arrive', 'cancel'],
};

/**
 * `next/navigation` for a screen kept in the address: the search parameters of the window's location, followed when
 * the screen pushes or replaces an entry and on back and forward, as Next.js follows the native History API.
 */
const listeners = new Set<() => void>();
let patched = false;
function subscribe(listener: () => void) {
  if (!patched) {
    patched = true;
    for (const name of ['pushState', 'replaceState'] as const) {
      const original = window.history[name].bind(window.history);
      window.history[name] = (...args: Parameters<History['pushState']>) => {
        original(...args);
        listeners.forEach((each) => each());
      };
    }
    window.addEventListener('popstate', () => listeners.forEach((each) => each()));
  }
  listeners.add(listener);
  return () => listeners.delete(listener);
}
export const router = { push: () => undefined, replace: () => undefined, refresh: () => undefined };
export const navigation = {
  useRouter: () => router,
  usePathname: () => window.location.pathname,
  useSearchParams: () =>
    new URLSearchParams(
      useSyncExternalStore(
        subscribe,
        () => window.location.search,
        () => '',
      ),
    ),
};
