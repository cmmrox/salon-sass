import type { CatalogueService, CategoryList } from '@/features/catalogue';

/** Catalogue answers for the Services & pricing page's tests: three categories and three services. */
export const COLOUR = '6b0a3c1e-9a4e-4b8e-9d1f-2a3b4c5d6e70';
export const NAILS = '6b0a3c1e-9a4e-4b8e-9d1f-2a3b4c5d6e71';
export const WAXING = '6b0a3c1e-9a4e-4b8e-9d1f-2a3b4c5d6e72';

export const CATEGORIES: CategoryList = {
  listVersion: 4,
  items: [
    { id: COLOUR, name: 'Colour', position: 1, serviceCount: 2, version: 0 },
    { id: NAILS, name: 'Nails', position: 2, serviceCount: 1, version: 0 },
    { id: WAXING, name: 'Waxing', position: 3, serviceCount: 0, version: 0 },
  ],
};

export function service(
  id: string,
  name: string,
  categoryId: string,
  options: Partial<{
    priceMinor: number;
    minutes: number;
    buffer: number;
    online: boolean;
    description: string | null;
  }> = {},
): CatalogueService {
  const minutes = options.minutes ?? 60;
  const buffer = options.buffer ?? 0;
  return {
    id,
    name,
    categoryId,
    description: options.description ?? null,
    bookableOnline: options.online ?? true,
    photoId: null,
    photoUrl: null,
    version: 0,
    terms: {
      versionId: `${id}-v1`,
      number: 1,
      priceMinor: options.priceMinor ?? 450_000,
      currency: 'LKR',
      segments: [{ kind: 'active', minutes }, ...(buffer > 0 ? [{ kind: 'buffer' as const, minutes: buffer }] : [])],
      durationMinutes: minutes,
      bufferMinutes: buffer,
    },
  };
}

export const SERVICES = {
  items: [
    service('11111111-0000-4000-8000-000000000001', 'zebra highlights', COLOUR, {
      priceMinor: 900_000,
      minutes: 120,
      buffer: 15,
      description: 'Fine foils',
    }),
    service('11111111-0000-4000-8000-000000000002', 'Balayage', COLOUR, { priceMinor: 1_200_000, minutes: 180 }),
    service('11111111-0000-4000-8000-000000000003', 'Gel polish', NAILS, {
      priceMinor: 300_000,
      minutes: 45,
      online: false,
    }),
  ],
};
