import type { TeamCategory, TeamMember, TeamService } from '../types';

/** A person's fields as the profile sheet holds them. */
export type MemberValues = {
  name: string;
  title: string;
  highlights: string;
  bio: string;
  shownOnline: boolean;
  categoryIds: string[];
  serviceIds: string[];
  signInAccountId: string | null;
};

/** A broken rule the sheet says: the first four as the person types (the prototype's `stErr`), the rest from the API. */
export type MemberProblem = 'name' | 'title' | 'highlights' | 'bio' | 'services' | 'signInAccountId';

const NAME_MIN = 2;
const NAME_MAX = 40;
const TITLE_MAX = 40;
const HIGHLIGHTS_MAX = 60;
export const BIO_MAX = 140;

/** Characters as the API counts them, so that an emoji is one. */
export const lengthOf = (text: string) => [...text].length;

export const newMember = (): MemberValues => ({
  name: '',
  title: '',
  highlights: '',
  bio: '',
  shownOnline: true,
  categoryIds: [],
  serviceIds: [],
  signInAccountId: null,
});

export const valuesOf = (member: TeamMember): MemberValues => ({
  name: member.name,
  title: member.title ?? '',
  highlights: member.highlights ?? '',
  bio: member.bio ?? '',
  shownOnline: member.shownOnline,
  categoryIds: [...member.services.categoryIds],
  serviceIds: [...member.services.serviceIds],
  signInAccountId: member.signInAccountId ?? null,
});

const sameSet = (a: string[], b: string[]) => a.length === b.length && a.every((each) => b.includes(each));

export function sameValues(a: MemberValues, b: MemberValues): boolean {
  return (
    a.name.trim() === b.name.trim() &&
    a.title.trim() === b.title.trim() &&
    a.highlights.trim() === b.highlights.trim() &&
    a.bio.trim() === b.bio.trim() &&
    a.shownOnline === b.shownOnline &&
    sameSet(a.categoryIds, b.categoryIds) &&
    sameSet(a.serviceIds, b.serviceIds) &&
    a.signInAccountId === b.signInAccountId
  );
}

/** The rules the sheet can check itself (F06-S01 AC-1), in the order of the fields. */
export function problemsOf(values: MemberValues): MemberProblem[] {
  const problems: MemberProblem[] = [];
  const name = lengthOf(values.name.trim());
  if (name < NAME_MIN || name > NAME_MAX) problems.push('name');
  if (lengthOf(values.title.trim()) > TITLE_MAX) problems.push('title');
  if (lengthOf(values.highlights.trim()) > HIGHLIGHTS_MAX) problems.push('highlights');
  if (lengthOf(values.bio.trim()) > BIO_MAX) problems.push('bio');
  return problems;
}

const ANSWERED: MemberProblem[] = ['name', 'title', 'highlights', 'bio', 'services', 'signInAccountId'];

/** The problems a 400 named, in the sheet's order; an unknown field says nothing here and the page says refused. */
export const problemsOfFields = (fields: string[]): MemberProblem[] =>
  ANSWERED.filter((problem) => fields.includes(problem));

export const requestOf = (values: MemberValues) => ({
  name: values.name.trim(),
  title: values.title.trim() || null,
  highlights: values.highlights.trim() || null,
  bio: values.bio.trim() || null,
  shownOnline: values.shownOnline,
  services: { categoryIds: values.categoryIds, serviceIds: values.serviceIds },
  signInAccountId: values.signInAccountId,
});

/** A category of the picker with what it holds, for the rows and their words. */
export type CategoryRow = {
  category: TeamCategory;
  services: TeamService[];
  /** Whole: every service, and any added later. Some: only `chosen`. None: not performed. */
  state: 'whole' | 'some' | 'none';
  chosen: string[];
};

/** The salon's categories that have services, in its order, with what this person performs of each (AC-3). */
export function categoryRows(values: MemberValues, categories: TeamCategory[], services: TeamService[]): CategoryRow[] {
  return categories.flatMap((category) => {
    const inIt = services.filter((service) => service.categoryId === category.id);
    if (inIt.length === 0) return [];
    const chosen = inIt.filter((service) => values.serviceIds.includes(service.id)).map((service) => service.id);
    const state: CategoryRow['state'] = values.categoryIds.includes(category.id)
      ? 'whole'
      : chosen.length > 0
        ? 'some'
        : 'none';
    return [{ category, services: inIt, state, chosen }];
  });
}

/** Ticking a category means all of it; unticking it, none of it. */
export function toggleCategory(values: MemberValues, row: CategoryRow): MemberValues {
  const inIt = row.services.map((service) => service.id);
  const singles = values.serviceIds.filter((id) => !inIt.includes(id));
  return row.state === 'none'
    ? { ...values, categoryIds: [...values.categoryIds, row.category.id], serviceIds: singles }
    : { ...values, categoryIds: values.categoryIds.filter((id) => id !== row.category.id), serviceIds: singles };
}

/**
 * **Choose** narrows a ticked category to single services (AC-3): one taken out of a whole category leaves the others
 * as single services, and choosing every one again makes it whole again, so services added later are included again.
 */
export function toggleService(values: MemberValues, row: CategoryRow, serviceId: string): MemberValues {
  const inIt = row.services.map((service) => service.id);
  const current = row.state === 'whole' ? inIt : row.chosen;
  const next = current.includes(serviceId) ? current.filter((id) => id !== serviceId) : [...current, serviceId];
  const others = values.serviceIds.filter((id) => !inIt.includes(id));
  const withoutCategory = values.categoryIds.filter((id) => id !== row.category.id);
  return next.length === inIt.length
    ? { ...values, categoryIds: [...withoutCategory, row.category.id], serviceIds: others }
    : { ...values, categoryIds: withoutCategory, serviceIds: [...others, ...next] };
}

/** The names of the categories a person performs, in the salon's order: whole, or through a single service. */
export function performedCategoryNames(
  member: TeamMember,
  categories: TeamCategory[],
  services: TeamService[],
): string[] {
  const throughSingle = new Set(
    services.filter((service) => member.services.serviceIds.includes(service.id)).map((service) => service.categoryId),
  );
  return categories
    .filter((category) => member.services.categoryIds.includes(category.id) || throughSingle.has(category.id))
    .map((category) => category.name);
}

/** The person's first letter, for a card with no photo. */
export const initialOf = (name: string) => [...name.trim()][0]?.toUpperCase() ?? '?';

const TONES = ['bg-avatar-1', 'bg-avatar-2', 'bg-avatar-3', 'bg-avatar-4', 'bg-avatar-5', 'bg-avatar-6'];

/** One of the prototype's six colours for a person's initial, always the same for one id (AC-9). */
export function toneOf(id: string): string {
  let sum = 0;
  for (const character of id) sum += character.charCodeAt(0);
  return TONES[sum % TONES.length]!;
}
