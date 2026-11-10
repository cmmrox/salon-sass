/** The team a Team page test reads (F06-S01): two people, one linked, in a salon with two categories of services. */
export const COLOUR = '6b0a3c1e-9a4e-4b8e-9d1f-2a3b4c5d6e70';
const NAILS = '6b0a3c1e-9a4e-4b8e-9d1f-2a3b4c5d6e71';
export const TINT = '11111111-0000-4000-8000-000000000001';
const GLOSS = '11111111-0000-4000-8000-000000000002';
const ROOTS = '11111111-0000-4000-8000-000000000003';
const GEL = '11111111-0000-4000-8000-000000000004';
const ACCOUNT = '22222222-0000-4000-8000-000000000001';
export const FREE_ACCOUNT = '22222222-0000-4000-8000-000000000002';
export const NIMALI = '33333333-0000-4000-8000-000000000001';
export const KASUN = '33333333-0000-4000-8000-000000000002';

export const CATEGORIES = {
  listVersion: 0,
  items: [
    { id: COLOUR, name: 'Colour', position: 1, serviceCount: 3, version: 0 },
    { id: NAILS, name: 'Nails', position: 2, serviceCount: 1, version: 0 },
    { id: 'empty', name: 'Waxing', position: 3, serviceCount: 0, version: 0 },
  ],
};

export const SERVICES = {
  currency: 'LKR',
  items: [
    { id: TINT, name: 'Tint', categoryId: COLOUR },
    { id: GLOSS, name: 'Gloss', categoryId: COLOUR },
    { id: ROOTS, name: 'Root touch-up', categoryId: COLOUR },
    { id: GEL, name: 'Gel polish', categoryId: NAILS },
  ],
};

const WEEKDAYS = ['monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday', 'sunday'];

type Pause = { starts: string; finishes: string };

/** A working weekday as the API answers it (F06-S02). */
export const workday = (day: string, starts: string, finishes: string, breaks: Pause[] = []) => ({
  day,
  working: true,
  starts,
  finishes,
  breaks,
});

/** The seven weekdays as the API answers them: not working, but for the days given. */
export const week = (...working: ReturnType<typeof workday>[]) =>
  WEEKDAYS.map(
    (day) =>
      working.find((each) => each.day === day) ?? {
        day,
        working: false,
        starts: null as string | null,
        finishes: null as string | null,
        breaks: [] as Pause[],
      },
  );

export function member(id: string, name: string, overrides: Record<string, unknown> = {}) {
  return {
    id,
    name,
    title: null,
    highlights: null,
    bio: null,
    shownOnline: true,
    photoId: null,
    photoUrl: null,
    services: { categoryIds: [] as string[], serviceIds: [] as string[] },
    signInAccountId: null as string | null,
    signInLabel: null as string | null,
    weekly: week(),
    nextLeave: null as { firstDay: string; lastDay: string; type?: string } | null,
    version: 0,
    ...overrides,
  };
}

export const TEAM = {
  items: [
    member(KASUN, 'Kasun Silva', {
      title: 'Nail technician',
      shownOnline: false,
      services: { categoryIds: [], serviceIds: [GEL] },
    }),
    member(NIMALI, 'Nimali Perera', {
      title: 'Colour Specialist',
      bio: 'Ten years of colour work.',
      highlights: 'Balayage',
      services: { categoryIds: [COLOUR], serviceIds: [] },
      signInAccountId: ACCOUNT,
      signInLabel: 'nimali@example.test',
    }),
  ],
};

export const ACCOUNTS = {
  items: [
    { id: ACCOUNT, label: 'nimali@example.test', role: 'PROFESSIONAL', memberId: NIMALI },
    { id: FREE_ACCOUNT, label: 'free@example.test', role: 'MANAGER', memberId: null },
  ],
};
