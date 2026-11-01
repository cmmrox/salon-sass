import { describe, expect, it } from 'vitest';
import {
  categoryRows,
  initialOf,
  newMember,
  performedCategoryNames,
  problemsOf,
  problemsOfFields,
  requestOf,
  sameValues,
  toggleCategory,
  toggleService,
  toneOf,
} from './team-form';
import type { TeamMember } from '../types';

const COLOUR = { id: 'colour', name: 'Colour' };
const NAILS = { id: 'nails', name: 'Nails' };
const EMPTY = { id: 'empty', name: 'Waxing' };
const TINT = { id: 'tint', name: 'Tint', categoryId: 'colour' };
const GLOSS = { id: 'gloss', name: 'Gloss', categoryId: 'colour' };
const GEL = { id: 'gel', name: 'Gel', categoryId: 'nails' };
const CATEGORIES = [COLOUR, NAILS, EMPTY];
const SERVICES = [TINT, GLOSS, GEL];

const row = (values: ReturnType<typeof newMember>, id: string) =>
  categoryRows(values, CATEGORIES, SERVICES).find((each) => each.category.id === id)!;

describe('the sheet’s rules (F06-S01 AC-1)', () => {
  const named = (name: string) => ({ ...newMember(), name });

  it.each([
    ['K', ['name']],
    ['Ka', []],
    ['x'.repeat(40), []],
    ['x'.repeat(41), ['name']],
    ['   ', ['name']],
    ['  Ka  ', []],
  ])('a name of %j breaks %j', (name, expected) => {
    expect(problemsOf(named(name))).toEqual(expected);
  });

  it.each([
    ['title', 40],
    ['highlights', 60],
    ['bio', 140],
  ] as const)('%s allows %i characters and no more', (field, limit) => {
    expect(problemsOf({ ...named('Ka'), [field]: 'x'.repeat(limit) })).toEqual([]);
    expect(problemsOf({ ...named('Ka'), [field]: 'x'.repeat(limit + 1) })).toEqual([field]);
  });

  it('counts an emoji as one character, as the API does', () => {
    expect(problemsOf({ ...named('Ka'), title: '😀'.repeat(40) })).toEqual([]);
  });

  it('keeps only the fields it can say from a 400', () => {
    expect(problemsOfFields(['signInAccountId', 'somethingElse', 'name'])).toEqual(['name', 'signInAccountId']);
  });

  it('sends an empty optional field as none, and trims', () => {
    const request = requestOf({ ...named('  Ka  '), title: '  ', bio: ' Hello ' });
    expect(request).toMatchObject({ name: 'Ka', title: null, highlights: null, bio: 'Hello' });
  });

  it('sees no change in the order of what is chosen, or in spaces around a name', () => {
    const left = { ...named('Ka'), categoryIds: ['a', 'b'] };
    const right = { ...named(' Ka '), categoryIds: ['b', 'a'] };
    expect(sameValues(left, right)).toBe(true);
    expect(sameValues(left, { ...right, categoryIds: ['b'] })).toBe(false);
  });
});

describe('what a person performs (F06-S01 AC-3)', () => {
  it('lists only the categories that have services, in the salon’s order', () => {
    expect(categoryRows(newMember(), CATEGORIES, SERVICES).map((each) => each.category.name)).toEqual([
      'Colour',
      'Nails',
    ]);
  });

  it('ticking a category means all of it, and unticking it none of it', () => {
    const ticked = toggleCategory(newMember(), row(newMember(), 'colour'));
    expect(ticked).toMatchObject({ categoryIds: ['colour'], serviceIds: [] });
    expect(row(ticked, 'colour').state).toBe('whole');
    expect(toggleCategory(ticked, row(ticked, 'colour'))).toMatchObject({ categoryIds: [], serviceIds: [] });
  });

  it('taking one service out of a whole category leaves the others as single services', () => {
    const ticked = toggleCategory(newMember(), row(newMember(), 'colour'));
    const narrowed = toggleService(ticked, row(ticked, 'colour'), 'tint');
    expect(narrowed).toMatchObject({ categoryIds: [], serviceIds: ['gloss'] });
    expect(row(narrowed, 'colour')).toMatchObject({ state: 'some', chosen: ['gloss'] });
  });

  it('choosing every service again makes the category whole, and taking the last out unticks it', () => {
    const some = { ...newMember(), serviceIds: ['gloss'] };
    expect(toggleService(some, row(some, 'colour'), 'tint')).toMatchObject({
      categoryIds: ['colour'],
      serviceIds: [],
    });
    const none = toggleService(some, row(some, 'colour'), 'gloss');
    expect(none).toMatchObject({ categoryIds: [], serviceIds: [] });
    expect(row(none, 'colour').state).toBe('none');
  });

  it('leaves what is chosen in other categories alone', () => {
    const start = { ...newMember(), serviceIds: ['gel'] };
    const ticked = toggleCategory(start, row(start, 'colour'));
    expect(ticked).toMatchObject({ categoryIds: ['colour'], serviceIds: ['gel'] });
  });

  it('names the categories performed, whole or through a single service, in the salon’s order', () => {
    const member = { services: { categoryIds: ['nails'], serviceIds: ['tint'] } } as unknown as TeamMember;
    expect(performedCategoryNames(member, CATEGORIES, SERVICES)).toEqual(['Colour', 'Nails']);
  });
});

describe('a person with no photo (F06-S01 AC-9)', () => {
  it('shows their initial', () => {
    expect(initialOf('  nimali')).toBe('N');
    expect(initialOf('')).toBe('?');
  });

  it('keeps the same one of six colours for one id', () => {
    const id = '11111111-0000-4000-8000-000000000001';
    expect(toneOf(id)).toBe(toneOf(id));
    expect(toneOf(id)).toMatch(/^bg-avatar-[1-6]$/);
  });
});
