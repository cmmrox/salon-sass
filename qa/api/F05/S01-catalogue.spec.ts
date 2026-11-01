/**
 * F05-S01 categories, services, terms versions, photos and the public menu from outside: the ten
 * default categories, every field boundary and limit, versioned terms stored as segments, stale and
 * simultaneous saves and moves, duplicate naming, hiding and deleting, photo checks and serving,
 * the public menu, each role, a support session, suspended and read-only salons, tenant isolation
 * through the API and at the database, and the audit trail.
 *
 * Expected results are the F05-S01 spec's (acceptance criteria, "API and data changes", "Design
 * notes"), decisions 091 and 093, docs/qa/test-types.md and, once it exists, the generated
 * contract. Prepared before the build: names only the build can give are in
 * `PENDING_F05_S01` (qa/shared/catalogue.ts) and are confirmed at the dry run.
 *
 * Not automated here: AC-9's refusal while an upcoming booking uses the service (case 020 of this stage,
 * deferred to F07: until `booking` implements `UpcomingServiceBookings` the interface answers
 * "none", and the spec has the developer's integration test prove the refusal), and AC-6's
 * `ServiceTerms`, a Java interface with no route, which the developer's integration tests call; its
 * promise (a version keeps its values by id, after later saves and after delete, for its salon only)
 * is proved here at the database (case 016).
 */
import { randomUUID } from 'node:crypto';

import { answerOf, expectNothingOfOtherSalons } from '../../shared/answers';
import { readOnlySalon } from '../../shared/billing';
import { refused } from '../../shared/booking-rules';
import {
  CATEGORIES, Catalogue, DEFAULT_CATEGORIES, LIMITS, PENDING_F05_S01, SERVICES, catalogueAudit, catalogueRows, catalogueSnapshot,
  catalogueTables, categoryPath, comparable, fieldRefusal, marker, menuField, newSalon, photoIdOf, rowsMentioning, rupees,
  segmentsOf, serviceInput, servicePath, termsRow, termsRows, updateOf, type Category, type Service,
} from '../../shared/catalogue';
import { webUrl } from '../../shared/env';
import type { Role, Tokens } from '../../shared/identity';
import { identitySql } from '../../shared/identity-storage';
import { fixture, grayPng, pngUpload, readJpeg, type FileUpload } from '../../shared/salon-brand';
import { test, expect, alpha, beta, operatorJson, sqlLiteral, supportJson, type SupportStart } from '../../shared/operator';
import { salonUrl, type Salon } from '../../shared/tenants';

test.describe.configure({ timeout: 120_000 });

const ROLES_ALL: Role[] = ['OWNER', 'MANAGER', 'RECEPTIONIST', 'PROFESSIONAL', 'ACCOUNTANT'];
const chars = (n: number, letter = 'a') => `${letter.toUpperCase()}${letter.repeat(n - 1)}`;
const names = (list: { items: Category[] }) => list.items.map(c => c.name);
const unknownHost = () => `qa-unknown-${randomUUID().slice(0, 8)}.localhost`;

/** A service in a named category of the salon, created now. */
async function serviceIn(cat: Catalogue, salon: Salon, tokens: Tokens, categoryName: string, overrides: Parameters<typeof serviceInput>[1] = {}) {
  const category = await cat.category(salon, tokens, categoryName);
  return cat.create(salon, tokens, serviceInput(category.id, overrides));
}

/** Every change endpoint, with a body that would succeed for the service and category given. */
function changeRoutes(service: Service, category: Category, listVersion: number) {
  return [
    { label: 'add category', method: 'POST', path: CATEGORIES, data: { name: `QA cat ${marker()}` } },
    { label: 'rename category', method: 'PUT', path: categoryPath(category.id), data: { name: `QA cat ${marker()}`, version: category.version } },
    { label: 'move category', method: 'POST', path: `${categoryPath(category.id)}/move`, data: { direction: 'down', listVersion } },
    { label: 'delete category', method: 'DELETE', path: `${categoryPath(category.id)}?version=${category.version}`, data: undefined },
    { label: 'create service', method: 'POST', path: SERVICES, data: serviceInput(category.id) },
    { label: 'update service', method: 'PUT', path: servicePath(service.id), data: updateOf(service, { priceMinor: service.terms.priceMinor + rupees(100) }) },
    { label: 'duplicate service', method: 'POST', path: `${servicePath(service.id)}/duplicate`, data: undefined },
    { label: 'hide service', method: 'PUT', path: `${servicePath(service.id)}/online`, data: { bookableOnline: !service.bookableOnline, version: service.version } },
    { label: 'delete service', method: 'DELETE', path: `${servicePath(service.id)}?version=${service.version}`, data: undefined },
    { label: 'remove photo', method: 'DELETE', path: `${servicePath(service.id)}/photo`, data: undefined },
  ];
}

const PHOTO = () => fixture('logo-700x600-banded.png');
const SMALL_PHOTO = () => pngUpload(grayPng(320, 320, { value: 90 }), 'small.png');

// ---------------------------------------------------------------- AC-1 default categories

test('TC-F05-S01-001 AC-1: a salon provisioned now starts with the ten default categories in order and no services, keeps a rename, a move and a delete, and is not given them again', async ({ identity, operator }) => {
  const cat = new Catalogue(identity);
  const { salon, owner, created } = await newSalon(operator, identity);
  const first = await cat.categories(salon, owner.tokens);
  expect(names(first), 'the prototype\'s ten, in order').toEqual([...DEFAULT_CATEGORIES]);
  expect(first.items.map(c => c.serviceCount), 'no services yet').toEqual(DEFAULT_CATEGORIES.map(() => 0));
  const positions = first.items.map(c => c.position);
  expect([...positions].sort((a, b) => a - b), 'positions ascend in list order, none shared').toEqual(positions);
  expect(new Set(positions).size).toBe(10);
  expect(identitySql(`SELECT count(*) FROM ${PENDING_F05_S01.categoryTable} WHERE tenant_id = ${sqlLiteral(created.id)}`), 'stored once').toBe('10');
  expect((await cat.services(salon, owner.tokens)), 'no services').toEqual([]);
  expect((await cat.menu(salon)).categories, 'the menu leaves out categories with nothing bookable').toEqual([]);

  await cat.renameCategory(salon, owner.tokens, first.items[3], 'Nail bar');
  await cat.move(salon, owner.tokens, first.items[1].id, 'down');
  await cat.deleteCategory(salon, owner.tokens, await cat.category(salon, owner.tokens, 'Bridal'));
  const expected = ['Cuts & styling', 'Facials & skin', 'Colour', 'Nail bar', 'Brows & lashes', 'Waxing', 'Makeup & draping', 'Massage & spa', 'Consultations'];
  expect(names(await cat.categories(salon, owner.tokens)), 'the salon\'s changes').toEqual(expected);
  expect(names(await cat.categories(salon, owner.tokens)), 'a second read: nothing re-added').toEqual(expected);
  const other = await newSalon(operator, identity);
  expect(names(await cat.categories(other.salon, other.owner.tokens)), 'the next salon gets the ten').toEqual([...DEFAULT_CATEGORIES]);
  expect(names(await cat.categories(salon, owner.tokens)), 'and this salon keeps its own').toEqual(expected);
});

// Case 002 (AC-1, existing salons after the migration) is the shell check qa/checks/F05/S01-category-backfill.sh.

// ---------------------------------------------------------------- AC-2 categories

test('TC-F05-S01-003 AC-2: an Owner adds categories of 2 and 30 characters, trimmed, each at the end of the list, and renames one under the same rules', async ({ identity, operator }) => {
  const cat = new Catalogue(identity);
  const { salon, owner } = await newSalon(operator, identity);
  const added = await cat.addCategory(salon, owner.tokens, '  Kids’ cuts  ');
  expect(added.name, 'trimmed').toBe('Kids’ cuts');
  expect(added.serviceCount).toBe(0);
  const two = await cat.addCategory(salon, owner.tokens, 'Ab');
  const thirty = await cat.addCategory(salon, owner.tokens, chars(30, 'z'));
  const list = await cat.categories(salon, owner.tokens);
  expect(names(list).slice(-3), 'each new one goes to the end').toEqual(['Kids’ cuts', 'Ab', chars(30, 'z')]);
  expect(list.items[list.items.length - 1].position, 'the last position').toBe(Math.max(...list.items.map(c => c.position)));
  const renamed = await cat.renameCategory(salon, owner.tokens, two, `  ${chars(30, 'y')} `);
  expect(renamed.name, 'renamed, trimmed, at 30').toBe(chars(30, 'y'));
  expect(renamed.version, 'a new version').not.toBe(two.version);
  const after = await cat.categories(salon, owner.tokens);
  expect(names(after), 'in place').toEqual([...DEFAULT_CATEGORIES, 'Kids’ cuts', chars(30, 'y'), chars(30, 'z')]);
  expect(after.items.find(c => c.id === thirty.id)?.name).toBe(chars(30, 'z'));
});

test('TC-F05-S01-004 AC-2: a category name of under 2 or over 30 characters after trimming, or one another category has in any case, is refused on name and changes nothing', async ({ identity, operator }) => {
  const cat = new Catalogue(identity);
  const { salon, owner, created } = await newSalon(operator, identity);
  const nails = await cat.category(salon, owner.tokens, 'Nails');
  const tooShortOrLong: [string, unknown][] = [['empty', ''], ['spaces only', '   '], ['one character', 'A'], ['one after trimming', '  A  '],
    ['31 characters', chars(31)], ['not a string', 42], ['null', null]];
  for (const [label, name] of tooShortOrLong) {
    await test.step(`add: ${label}`, async () => {
      const before = catalogueSnapshot(created.id);
      await fieldRefusal(await cat.addCategoryRaw(salon, owner.tokens, name), 'name', `add ${label}`);
      expect(catalogueSnapshot(created.id), `add ${label}: nothing changed`).toBe(before);
    });
    await test.step(`rename: ${label}`, async () => {
      const before = catalogueSnapshot(created.id);
      await fieldRefusal(await cat.renameCategoryRaw(salon, owner.tokens, nails.id, name, nails.version), 'name', `rename ${label}`);
      expect(catalogueSnapshot(created.id), `rename ${label}: nothing changed`).toBe(before);
    });
  }
  // A duplicate name, in any case, is 400 on name (Corrections at close).
  for (const [label, send] of [
    ['add "COLOUR"', () => cat.addCategoryRaw(salon, owner.tokens, 'COLOUR')],
    ['add " colour " (trimmed)', () => cat.addCategoryRaw(salon, owner.tokens, ' colour ')],
    ['rename Nails to "waxing"', () => cat.renameCategoryRaw(salon, owner.tokens, nails.id, 'waxing', nails.version)],
  ] as const) {
    await test.step(label, async () => {
      const before = catalogueSnapshot(created.id);
      await fieldRefusal(await send(), 'name', label);
      expect(catalogueSnapshot(created.id), `${label}: nothing changed`).toBe(before);
    });
  }
  expect(names(await cat.categories(salon, owner.tokens)), 'still the ten').toEqual([...DEFAULT_CATEGORIES]);
});

test('TC-F05-S01-005 AC-2: moving a category up or down swaps it with its neighbour at once; a stale list version is 409 conflict, moving the first up or the last down is 409 invalid_state and no direction is 400, each changing nothing', async ({ identity, operator }) => {
  const cat = new Catalogue(identity);
  const { salon, owner, created } = await newSalon(operator, identity);
  const start = await cat.categories(salon, owner.tokens);
  const nails = start.items[3];
  const up = await cat.move(salon, owner.tokens, nails.id, 'up', start.listVersion);
  const swapped = [...DEFAULT_CATEGORIES]; [swapped[2], swapped[3]] = [swapped[3], swapped[2]];
  expect(names(up), 'Nails above Facials & skin').toEqual(swapped);
  expect(up.listVersion, 'a new list version').not.toBe(start.listVersion);
  expect(names(await cat.categories(salon, owner.tokens)), 'saved at once').toEqual(swapped);
  const back = await cat.move(salon, owner.tokens, nails.id, 'down', up.listVersion);
  expect(names(back), 'and back down').toEqual([...DEFAULT_CATEGORIES]);
  const refusals: [string, () => ReturnType<Catalogue['moveRaw']>, 'conflict' | 'invalid_state' | 'direction'][] = [
    ['a stale list version', () => cat.moveRaw(salon, owner.tokens, nails.id, 'up', start.listVersion), 'conflict'],
    ['the first category up', () => cat.moveRaw(salon, owner.tokens, back.items[0].id, 'up', back.listVersion), 'invalid_state'],
    ['the last category down', () => cat.moveRaw(salon, owner.tokens, back.items[9].id, 'down', back.listVersion), 'invalid_state'],
    ['direction "left"', () => cat.moveRaw(salon, owner.tokens, nails.id, 'left', back.listVersion), 'direction'],
  ];
  for (const [label, send, expected] of refusals) {
    await test.step(label, async () => {
      const before = catalogueSnapshot(created.id);
      const r = await send();
      if (expected === 'direction') await fieldRefusal(r, 'direction', label);
      else await refused(r, 409, expected, label);
      expect(catalogueSnapshot(created.id), `${label}: nothing changed`).toBe(before);
    });
  }
});

test('TC-F05-S01-006 AC-2: an empty category is deleted; one with services is 409 in_use, the last category is 409 invalid_state and a stale version is 409 conflict, each changing nothing', async ({ identity, operator }) => {
  const cat = new Catalogue(identity);
  const { salon, owner, created } = await newSalon(operator, identity);
  await test.step('an empty category', async () => {
    const extra = await cat.addCategory(salon, owner.tokens, 'Kids’ cuts');
    await cat.deleteCategory(salon, owner.tokens, extra);
    expect(names(await cat.categories(salon, owner.tokens))).toEqual([...DEFAULT_CATEGORIES]);
    await refused(await cat.deleteCategoryRaw(salon, owner.tokens, extra.id, extra.version), 404, 'not_found', 'deleting it again');
  });
  await test.step('a category with two services', async () => {
    await serviceIn(cat, salon, owner.tokens, 'Colour');
    await serviceIn(cat, salon, owner.tokens, 'Colour');
    const colour = await cat.category(salon, owner.tokens, 'Colour');
    expect(colour.serviceCount).toBe(2);
    const before = catalogueSnapshot(created.id);
    await refused(await cat.deleteCategoryRaw(salon, owner.tokens, colour.id, colour.version), 409, 'in_use', 'with services');
    expect(catalogueSnapshot(created.id), 'nothing changed').toBe(before);
  });
  await test.step('a stale version', async () => {
    const waxing = await cat.category(salon, owner.tokens, 'Waxing');
    await cat.renameCategory(salon, owner.tokens, waxing, 'Waxing & threading');
    const before = catalogueSnapshot(created.id);
    await refused(await cat.deleteCategoryRaw(salon, owner.tokens, waxing.id, waxing.version), 409, 'conflict', 'stale delete');
    expect(catalogueSnapshot(created.id), 'nothing changed').toBe(before);
  });
  await test.step('the last category', async () => {
    const other = await newSalon(operator, identity);
    const list = await cat.categories(other.salon, other.owner.tokens);
    for (const c of list.items.slice(1)) await cat.deleteCategory(other.salon, other.owner.tokens, c);
    const [last] = (await cat.categories(other.salon, other.owner.tokens)).items;
    expect(last.name).toBe(DEFAULT_CATEGORIES[0]);
    const before = catalogueSnapshot(other.created.id);
    await refused(await cat.deleteCategoryRaw(other.salon, other.owner.tokens, last.id, last.version), 409, 'invalid_state', 'the last category');
    expect(catalogueSnapshot(other.created.id), 'nothing changed').toBe(before);
    expect(names(await cat.categories(other.salon, other.owner.tokens))).toEqual([DEFAULT_CATEGORIES[0]]);
  });
});

test('TC-F05-S01-007 AC-2: a salon holds 50 categories and the 51st is 409 limit_reached, changing nothing', async ({ identity, operator }) => {
  const cat = new Catalogue(identity);
  const { salon, owner, created } = await newSalon(operator, identity);
  for (let n = 11; n <= LIMITS.categories; n++) await cat.addCategory(salon, owner.tokens, `Category ${n}`);
  expect((await cat.categories(salon, owner.tokens)).items).toHaveLength(LIMITS.categories);
  const before = catalogueSnapshot(created.id);
  await refused(await cat.addCategoryRaw(salon, owner.tokens, 'Category 51'), 409, 'limit_reached', 'the 51st category');
  expect(catalogueSnapshot(created.id), 'nothing changed').toBe(before);
  expect((await cat.categories(salon, owner.tokens)).items).toHaveLength(LIMITS.categories);
});

test('TC-F05-S01-008 AC-2: of eight moves sent at once against one list version exactly one is applied, the rest are 409 conflict, and the order is the old one with one pair swapped', async ({ identity, operator }) => {
  const cat = new Catalogue(identity);
  const { salon, owner } = await newSalon(operator, identity);
  const start = await cat.categories(salon, owner.tokens);
  const moves: [number, 'up' | 'down'][] = [[0, 'down'], [2, 'down'], [4, 'down'], [6, 'down'], [3, 'up'], [5, 'up'], [7, 'up'], [9, 'up']];
  const responses = await Promise.all(moves.map(([i, d]) => cat.moveRaw(salon, owner.tokens, start.items[i].id, d, start.listVersion)));
  const won = responses.map((r, n) => ({ r, n })).filter(x => x.r.status() === 200);
  expect(won.length, 'exactly one move').toBe(1);
  for (const r of responses.filter(x => x.status() !== 200)) await refused(r, 409, 'conflict', 'a losing move');
  const [i, d] = moves[won[0].n];
  const j = d === 'up' ? i - 1 : i + 1;
  const expected = [...DEFAULT_CATEGORIES] as string[]; [expected[i], expected[j]] = [expected[j], expected[i]];
  const after = await cat.categories(salon, owner.tokens);
  expect(names(after), 'one pair swapped').toEqual(expected);
  expect(new Set(after.items.map(c => c.position)).size, 'no two categories share a position').toBe(10);
});

// ---------------------------------------------------------------- AC-3 to AC-5 services and terms

test('TC-F05-S01-009 AC-3, AC-4, AC-5: an Owner creates a service with every field; it reads back as sent in minor units with its first terms version, its time as an active and a buffer segment, and no segment for a zero buffer', async ({ identity, operator }) => {
  const cat = new Catalogue(identity);
  const { salon, owner, created } = await newSalon(operator, identity);
  const colour = await cat.category(salon, owner.tokens, 'Colour');
  const input = serviceInput(colour.id, { name: 'Root touch-up', description: 'Roots only, up to 2 cm.', priceMinor: rupees(4750), durationMinutes: 60, bufferMinutes: 15 });
  const service = await cat.create(salon, owner.tokens, input);
  expect(service).toMatchObject({ name: 'Root touch-up', categoryId: colour.id, description: 'Roots only, up to 2 cm.', bookableOnline: true, photoUrl: null });
  expect(service.terms).toMatchObject({ number: 1, priceMinor: 475000, currency: 'LKR', durationMinutes: 60, bufferMinutes: 15 });
  expect(service.terms.segments, 'active, then buffer').toEqual(segmentsOf(60, 15));
  expect(await cat.service(salon, owner.tokens, service.id), 'the same on reading it back').toEqual(service);
  expect((await cat.services(salon, owner.tokens)).map(s => s.id), 'listed').toEqual([service.id]);
  expect((await cat.category(salon, owner.tokens, 'Colour')).serviceCount).toBe(1);
  expect(termsRows(created.id), 'one terms version').toBe(1);
  expect(termsRow(created.id, service.terms.versionId), 'Rs. 4,750 kept as 475000 minor units').toContain('475000');
  const noBuffer = await cat.create(salon, owner.tokens, serviceInput(colour.id, { name: 'Gloss', durationMinutes: 30, bufferMinutes: 0 }));
  expect(noBuffer.terms.segments, 'a zero buffer: the active segment only').toEqual(segmentsOf(30, 0));
});

test('TC-F05-S01-010 AC-3: every limit of the service form is accepted and read back: names of 2 and 40, a description of 90 or none, Rs. 100 and Rs. 500,000, 5 and 480 minutes, a buffer of 0 and 60, and online by default', async ({ identity, operator }) => {
  const cat = new Catalogue(identity);
  const { salon, owner } = await newSalon(operator, identity);
  const { id: categoryId } = await cat.category(salon, owner.tokens, 'Nails');
  const rows: { label: string; input: Record<string, unknown>; check: (s: Service) => void }[] = [
    { label: 'a name of 2', input: { name: 'Ab' }, check: s => expect(s.name).toBe('Ab') },
    { label: 'a name of 40', input: { name: chars(40, 'n') }, check: s => expect(s.name).toBe(chars(40, 'n')) },
    { label: 'a description of 90', input: { description: chars(90, 'd') }, check: s => expect(s.description).toBe(chars(90, 'd')) },
    { label: 'an empty description', input: { description: '' }, check: s => expect(s.description ?? '').toBe('') },
    { label: 'no description', input: { description: undefined }, check: s => expect(s.description ?? '').toBe('') },
    { label: 'Rs. 100', input: { priceMinor: rupees(100) }, check: s => expect(s.terms.priceMinor).toBe(10000) },
    { label: 'Rs. 500,000', input: { priceMinor: rupees(500_000) }, check: s => expect(s.terms.priceMinor).toBe(50_000_000) },
    { label: '5 minutes', input: { durationMinutes: 5 }, check: s => expect(s.terms.durationMinutes).toBe(5) },
    { label: '480 minutes', input: { durationMinutes: 480 }, check: s => expect(s.terms.durationMinutes).toBe(480) },
    { label: 'a buffer of 0', input: { bufferMinutes: 0 }, check: s => expect(s.terms.bufferMinutes).toBe(0) },
    { label: 'a buffer of 60', input: { bufferMinutes: 60 }, check: s => expect(s.terms.segments).toEqual(segmentsOf(s.terms.durationMinutes, 60)) },
    { label: 'hidden online', input: { bookableOnline: false }, check: s => expect(s.bookableOnline).toBe(false) },
    { label: 'online left out: on', input: { bookableOnline: undefined }, check: s => expect(s.bookableOnline).toBe(true) },
  ];
  for (const row of rows) {
    await test.step(row.label, async () => {
      const input = { ...serviceInput(categoryId), ...row.input };
      const created = await cat.create(salon, owner.tokens, JSON.parse(JSON.stringify(input)));
      row.check(created);
      row.check(await cat.service(salon, owner.tokens, created.id));
    });
  }
});

test('TC-F05-S01-011 AC-3: a name, category, description, price, time, buffer or online setting that breaks its rule is a 400 on that field, on create and on edit, and saves nothing', async ({ identity, operator }) => {
  const cat = new Catalogue(identity);
  const { salon, owner, created } = await newSalon(operator, identity);
  const { id: categoryId } = await cat.category(salon, owner.tokens, 'Nails');
  const existing = await cat.create(salon, owner.tokens, serviceInput(categoryId, { name: 'Gel polish' }));
  await cat.create(salon, owner.tokens, serviceInput(categoryId, { name: 'Acrylic set' }));
  const rows: [string, string, Record<string, unknown>][] = [
    ['no name', 'name', { name: undefined }], ['an empty name', 'name', { name: '' }], ['a name of 1', 'name', { name: 'A' }],
    ['a name of 41', 'name', { name: chars(41) }], ['another service\'s name in upper case', 'name', { name: 'ACRYLIC SET' }],
    ['no category', 'categoryId', { categoryId: undefined }], ['an unknown category', 'categoryId', { categoryId: randomUUID() }],
    ['a category that is not an id', 'categoryId', { categoryId: 'colour' }],
    ['a description of 91', 'description', { description: chars(91, 'd') }],
    ['Rs. 99', 'priceMinor', { priceMinor: rupees(99) }], ['Rs. 500,001', 'priceMinor', { priceMinor: rupees(500_001) }],
    ['part of a rupee (Rs. 100.50)', 'priceMinor', { priceMinor: 10050 }], ['no price', 'priceMinor', { priceMinor: undefined }],
    ['a price as text', 'priceMinor', { priceMinor: '250000' }], ['a negative price', 'priceMinor', { priceMinor: -10000 }],
    ['0 minutes', 'durationMinutes', { durationMinutes: 0 }], ['7 minutes', 'durationMinutes', { durationMinutes: 7 }],
    ['485 minutes', 'durationMinutes', { durationMinutes: 485 }], ['no time', 'durationMinutes', { durationMinutes: undefined }],
    ['a buffer of -5', 'bufferMinutes', { bufferMinutes: -5 }], ['a buffer of 7', 'bufferMinutes', { bufferMinutes: 7 }],
    ['a buffer of 65', 'bufferMinutes', { bufferMinutes: 65 }], ['online as text', 'bookableOnline', { bookableOnline: 'yes' }],
  ];
  const wrong: string[] = [];
  for (const [label, field, change] of rows) {
    for (const action of ['create', 'edit'] as const) {
      await test.step(`${action}: ${label}`, async () => {
        const before = catalogueSnapshot(created.id);
        const body = JSON.parse(JSON.stringify(action === 'create' ? { ...serviceInput(categoryId), ...change } : { ...updateOf(existing), ...change }));
        const r = action === 'create' ? await cat.createRaw(salon, owner.tokens, body) : await cat.updateRaw(salon, owner.tokens, existing.id, body);
        const answer = await answerOf(r);
        let fields: string[] = [];
        try { fields = ((JSON.parse(answer.body) as { errors?: { field: string }[] }).errors ?? []).map(e => e.field); } catch { /* not problem details */ }
        if (answer.status !== 400 || !answer.body.includes('"bad_request"') || fields.length === 0 || !fields.every(f => new RegExp(`(^|\\.)${field}$`).test(f))) {
          wrong.push(`${action} ${label}: ${answer.status} ${answer.body.slice(0, 160)}`);
        }
        if (catalogueSnapshot(created.id) !== before) wrong.push(`${action} ${label}: something was saved`);
      });
    }
  }
  expect(wrong, 'every broken rule is a 400 on its field that saves nothing').toEqual([]);
});

test('TC-F05-S01-012 AC-3: a salon holds 300 services, and a 301st, by creating or by duplicating, is 409 limit_reached and saves nothing', async ({ identity, operator }) => {
  test.setTimeout(300_000);
  const cat = new Catalogue(identity);
  const { salon, owner, created } = await newSalon(operator, identity);
  const { id: categoryId } = await cat.category(salon, owner.tokens, 'Cuts & styling');
  for (let n = 0; n < LIMITS.services; n += 10) {
    await Promise.all(Array.from({ length: 10 }, (_, k) => cat.create(salon, owner.tokens, serviceInput(categoryId, { name: `Service ${n + k + 1}` }))));
  }
  const all = await cat.services(salon, owner.tokens);
  expect(all).toHaveLength(LIMITS.services);
  const before = catalogueSnapshot(created.id);
  await refused(await cat.createRaw(salon, owner.tokens, serviceInput(categoryId, { name: 'Service 301' })), 409, 'limit_reached', 'a 301st service');
  await refused(await cat.duplicateRaw(salon, owner.tokens, all[0].id), 409, 'limit_reached', 'a duplicate as the 301st');
  expect(catalogueSnapshot(created.id), 'nothing saved').toBe(before);
  await test.step('deleting one makes room for one', async () => {
    await cat.remove(salon, owner.tokens, all[0]);
    await cat.create(salon, owner.tokens, serviceInput(categoryId, { name: 'Service 301' }));
    await refused(await cat.createRaw(salon, owner.tokens, serviceInput(categoryId, { name: 'Service 302' })), 409, 'limit_reached', 'full again');
  });
});

test('TC-F05-S01-013 AC-3: a salon in another currency (NFR-13) enters, keeps and reads prices in that currency with the limits in its whole units', async ({ identity, operator }) => {
  // The operator provisions currencies of two fraction digits only (F04-S04-015), so the limits are the LKR numbers in that currency.
  const cat = new Catalogue(identity);
  const { salon, owner, created } = await newSalon(operator, identity, { currency: 'USD' });
  const { id: categoryId } = await cat.category(salon, owner.tokens, 'Nails');
  for (const [label, priceMinor] of [['99 units', 9900], ['500,001 units', 50_000_100], ['part of a unit', 10050]] as const) {
    const before = catalogueSnapshot(created.id);
    await fieldRefusal(await cat.createRaw(salon, owner.tokens, serviceInput(categoryId, { priceMinor })), 'priceMinor', label);
    expect(catalogueSnapshot(created.id), `${label}: nothing saved`).toBe(before);
  }
  const service = await cat.create(salon, owner.tokens, serviceInput(categoryId, { priceMinor: 10000 }));
  expect(service.terms).toMatchObject({ priceMinor: 10000, currency: 'USD' });
  const menu = await cat.menu(salon);
  expect(menu.currency, 'customers read the salon\'s currency').toBe('USD');
  expect(termsRow(created.id, service.terms.versionId), 'the version keeps its currency').toContain('USD');
});

test('TC-F05-S01-014 AC-4: a price, time or buffer change makes a new current terms version and leaves the earlier ones as they were; a name, description, category or online change makes none; a save of nothing is 409 invalid_state and a stale version is 409 conflict', async ({ identity, operator }) => {
  const cat = new Catalogue(identity);
  const { salon, owner, created } = await newSalon(operator, identity);
  const colour = await cat.category(salon, owner.tokens, 'Colour');
  const nails = await cat.category(salon, owner.tokens, 'Nails');
  let s = await cat.create(salon, owner.tokens, serviceInput(colour.id, { name: 'Highlights', priceMinor: rupees(8000), durationMinutes: 90, bufferMinutes: 10 }));
  const v1 = s.terms.versionId;
  const v1Row = termsRow(created.id, v1);
  const steps: [string, Record<string, unknown>, Partial<Service['terms']>][] = [
    ['the price', { priceMinor: rupees(8500) }, { priceMinor: 850000 }],
    ['the time', { durationMinutes: 120 }, { durationMinutes: 120 }],
    ['the buffer', { bufferMinutes: 20 }, { bufferMinutes: 20 }],
  ];
  const versions = [v1];
  for (const [label, change, expected] of steps) {
    await test.step(`changing ${label}`, async () => {
      const prior = s;
      const priorRow = termsRow(created.id, prior.terms.versionId);
      s = await cat.update(salon, owner.tokens, s, change);
      expect(s.terms, `${label}: a new version, current`).toMatchObject({ ...expected, number: prior.terms.number + 1 });
      expect(versions, `${label}: a new version id`).not.toContain(s.terms.versionId);
      versions.push(s.terms.versionId);
      expect(termsRow(created.id, prior.terms.versionId), `${label}: the version before is unchanged`).toBe(priorRow);
    });
  }
  expect(s.terms.segments, 'the newest segments').toEqual(segmentsOf(120, 20));
  expect(termsRows(created.id), 'four versions').toBe(4);
  expect(termsRow(created.id, v1), 'version 1 has its original values').toBe(v1Row);
  for (const [label, change] of [
    ['the name', { name: 'Full highlights' }], ['the description', { description: 'Foils, full head.' }],
    ['the category', { categoryId: nails.id }], ['the online setting', { bookableOnline: false }],
  ] as const) {
    await test.step(`changing ${label} only`, async () => {
      const prior = s;
      s = await cat.update(salon, owner.tokens, s, change);
      expect(s.terms.versionId, `${label}: the same terms version`).toBe(prior.terms.versionId);
      expect(s.version, `${label}: the service's own version moves on`).not.toBe(prior.version);
      expect(termsRows(created.id), `${label}: no terms version`).toBe(4);
    });
  }
  await test.step('a save that changes nothing', async () => {
    const before = catalogueSnapshot(created.id);
    await refused(await cat.updateRaw(salon, owner.tokens, s.id, updateOf(s)), 409, 'invalid_state', 'nothing changed');
    expect(catalogueSnapshot(created.id)).toBe(before);
  });
  await test.step('a stale version, and one from the future', async () => {
    const before = catalogueSnapshot(created.id);
    for (const version of [s.version - 1, s.version + 7]) {
      await refused(await cat.updateRaw(salon, owner.tokens, s.id, { ...updateOf(s, { priceMinor: rupees(9999) }), version }), 409, 'conflict', `version ${version}`);
    }
    expect(catalogueSnapshot(created.id), 'nothing changed').toBe(before);
    expect((await cat.service(salon, owner.tokens, s.id)).terms.priceMinor).toBe(850000);
  });
});

test('TC-F05-S01-015 AC-4: of eight saves of one service version sent at once exactly one succeeds, the rest are 409 conflict, and exactly one terms version is added', async ({ identity, operator }) => {
  const cat = new Catalogue(identity);
  const { salon, owner, created } = await newSalon(operator, identity);
  const s = await serviceIn(cat, salon, owner.tokens, 'Nails');
  const rows = termsRows(created.id);
  const prices = Array.from({ length: 8 }, (_, n) => rupees(3000 + n * 10));
  const responses = await Promise.all(prices.map(priceMinor => cat.updateRaw(salon, owner.tokens, s.id, updateOf(s, { priceMinor }))));
  const won = responses.map((r, n) => ({ r, n })).filter(x => x.r.status() === 200);
  expect(won.length, 'exactly one save').toBe(1);
  for (const r of responses.filter(x => x.status() !== 200)) await refused(r, 409, 'conflict', 'a losing save');
  expect(termsRows(created.id), 'exactly one version added').toBe(rows + 1);
  const after = await cat.service(salon, owner.tokens, s.id);
  expect(after.terms).toMatchObject({ number: 2, priceMinor: prices[won[0].n] });
});

test('TC-F05-S01-016 AC-5, AC-6, AC-4: a terms version keeps its stored values after three later saves and after the service is deleted, the application role can neither change nor delete one, another salon\'s context never reaches it, and no request stores a processing segment', async ({ identity, operator }) => {
  const cat = new Catalogue(identity);
  const { salon, owner, created } = await newSalon(operator, identity);
  const other = await newSalon(operator, identity);
  let s = await serviceIn(cat, salon, owner.tokens, 'Massage & spa', { name: 'Hot stone', priceMinor: rupees(6000), durationMinutes: 75, bufferMinutes: 15 });
  const v1 = s.terms.versionId;
  const original = termsRow(created.id, v1);
  expect(original, 'version 1 is stored').toContain(v1);
  for (const change of [{ priceMinor: rupees(6500) }, { durationMinutes: 90 }, { bufferMinutes: 5 }]) s = await cat.update(salon, owner.tokens, s, change);
  expect(s.terms.number).toBe(4);
  expect(termsRow(created.id, v1), 'unchanged after three later saves').toBe(original);
  await test.step('the application role cannot change or delete a version, even its own salon\'s', async () => {
    const table = PENDING_F05_S01.termsTable;
    expect(identitySql(`UPDATE ${table} SET tenant_id = tenant_id WHERE id = ${sqlLiteral(v1)}`, created.id, false)).toMatch(new RegExp(`ERROR:\\s+permission denied for table ${table}`));
    expect(identitySql(`DELETE FROM ${table} WHERE id = ${sqlLiteral(v1)}`, created.id, false)).toMatch(new RegExp(`ERROR:\\s+permission denied for table ${table}`));
    expect(termsRow(created.id, v1)).toBe(original);
  });
  await test.step('another salon\'s context does not reach the version', async () => {
    expect(identitySql(`SELECT count(*) FROM ${PENDING_F05_S01.termsTable} WHERE id = ${sqlLiteral(v1)}`, created.id), 'its salon sees it').toBe('1');
    expect(identitySql(`SELECT count(*) FROM ${PENDING_F05_S01.termsTable} WHERE id = ${sqlLiteral(v1)}`, other.created.id), 'another does not').toBe('0');
  });
  await test.step('after the service is deleted', async () => {
    const current = termsRow(created.id, s.terms.versionId);
    await cat.remove(salon, owner.tokens, s);
    expect(termsRow(created.id, v1), 'version 1 is kept, unchanged').toBe(original);
    expect(termsRow(created.id, s.terms.versionId), 'and the last version').toBe(current);
    expect(termsRows(created.id)).toBe(4);
  });
  await test.step('no request stores a processing segment', async () => {
    const { id: categoryId } = await cat.category(salon, owner.tokens, 'Colour');
    const extra = { segments: [{ kind: 'active', minutes: 30 }, { kind: 'processing', minutes: 20 }, { kind: 'active', minutes: 10 }], processingMinutes: 20 };
    // The request has no field for a processing segment, and an unknown field is a 400 (handoff, AC-3 and AC-5).
    const before = catalogueSnapshot(created.id);
    await refused(await cat.createRaw(salon, owner.tokens, { ...serviceInput(categoryId, { name: 'Colour with a long wait', durationMinutes: 60 }), ...extra }), 400, 'bad_request', 'create with processing');
    expect(catalogueSnapshot(created.id), 'the refused create saved nothing').toBe(before);
    const made = await cat.create(salon, owner.tokens, serviceInput(categoryId, { name: 'Colour with a long wait', durationMinutes: 60 }));
    const after = catalogueSnapshot(created.id);
    await refused(await cat.updateRaw(salon, owner.tokens, made.id, { ...updateOf(made, { durationMinutes: 65 }), ...extra }), 400, 'bad_request', 'edit with processing');
    expect(catalogueSnapshot(created.id), 'the refused edit saved nothing').toBe(after);
    for (const svc of await cat.services(salon, owner.tokens)) {
      expect(svc.terms.segments.map(x => x.kind).every(k => k === 'active' || k === 'buffer'), `${svc.name}: active and buffer segments only`).toBe(true);
    }
    expect(rowsMentioning(created.id, 'processing'), 'no stored row mentions a processing segment').toBe(0);
  });
});

// ---------------------------------------------------------------- AC-7 to AC-9 duplicate, online, delete

test('TC-F05-S01-017 AC-7: Duplicate makes "<name> (copy)", then "(copy 2)" and "(copy 3)", shortening a long name to fit 40 characters; each copy has its own first terms version, the same terms and starts hidden online', async ({ identity, operator }) => {
  const cat = new Catalogue(identity);
  const { salon, owner, created } = await newSalon(operator, identity);
  const original = await serviceIn(cat, salon, owner.tokens, 'Facials & skin', { name: 'Hair spa', description: 'Steam and mask.', priceMinor: rupees(3500), durationMinutes: 50, bufferMinutes: 10 });
  const copies: Service[] = [];
  for (const expected of ['Hair spa (copy)', 'Hair spa (copy 2)', 'Hair spa (copy 3)']) {
    const copy = await cat.duplicate(salon, owner.tokens, original.id);
    expect(copy.name).toBe(expected);
    expect(copy.bookableOnline, `${expected}: hidden online`).toBe(false);
    expect(copy).toMatchObject({ categoryId: original.categoryId, description: original.description });
    expect(copy.terms).toMatchObject({ number: 1, priceMinor: 350000, durationMinutes: 50, bufferMinutes: 10, segments: segmentsOf(50, 10) });
    expect([original, ...copies].map(x => x.terms.versionId), `${expected}: its own terms version`).not.toContain(copy.terms.versionId);
    copies.push(copy);
  }
  expect(await cat.service(salon, owner.tokens, original.id), 'the original is untouched').toEqual(original);
  expect(termsRows(created.id), 'one version each').toBe(4);
  await test.step('a 40-character name', async () => {
    const long = await serviceIn(cat, salon, owner.tokens, 'Nails', { name: chars(40, 'l') });
    for (const suffix of [' (copy)', ' (copy 2)']) {
      const copy = await cat.duplicate(salon, owner.tokens, long.id);
      expect(copy.name.length, `${suffix}: fits 40`).toBeLessThanOrEqual(40);
      expect(copy.name.endsWith(suffix), `${copy.name} ends with "${suffix}"`).toBe(true);
      expect(chars(40, 'l').startsWith(copy.name.slice(0, -suffix.length)), 'the base is the name shortened').toBe(true);
    }
  });
  await test.step('a deleted service cannot be duplicated', async () => {
    await cat.remove(salon, owner.tokens, copies[2]);
    await refused(await cat.duplicateRaw(salon, owner.tokens, copies[2].id), 404, 'not_found', 'duplicate of a deleted service');
  });
});

test('TC-F05-S01-018 AC-8: Hide online and Show online change only the online setting; a hidden service stays listed and leaves the public menu; a stale version is 409 conflict and the value it already has 409 invalid_state', async ({ identity, operator }) => {
  const cat = new Catalogue(identity);
  const { salon, owner, created } = await newSalon(operator, identity);
  const s = await serviceIn(cat, salon, owner.tokens, 'Waxing', { name: 'Full leg wax' });
  const inMenu = async () => (await cat.menu(salon)).categories.flatMap(c => c.services).some(x => x.id === s.id);
  expect(await inMenu(), 'online: on the menu').toBe(true);
  const hidden = await cat.online(salon, owner.tokens, s, false);
  expect(hidden.bookableOnline).toBe(false);
  expect({ ...hidden, bookableOnline: true, version: s.version }, 'nothing else changed').toEqual(s);
  expect(termsRows(created.id), 'no terms version').toBe(1);
  expect((await cat.services(salon, owner.tokens)).map(x => x.id), 'still in the workspace').toContain(s.id);
  expect(await inMenu(), 'hidden: off the menu').toBe(false);
  const before = catalogueSnapshot(created.id);
  await refused(await cat.onlineRaw(salon, owner.tokens, s.id, true, s.version), 409, 'conflict', 'stale version');
  await fieldRefusal(await cat.onlineRaw(salon, owner.tokens, s.id, 'yes' as unknown as boolean, hidden.version), 'bookableOnline', 'not a boolean');
  await refused(await cat.onlineRaw(salon, owner.tokens, s.id, false, hidden.version), 409, 'invalid_state', 'hiding a hidden service');
  expect(catalogueSnapshot(created.id), 'nothing changed').toBe(before);
  const shown = await cat.online(salon, owner.tokens, hidden, true);
  expect(shown.bookableOnline).toBe(true);
  expect(await inMenu(), 'shown: back on the menu').toBe(true);
});

test('TC-F05-S01-019 AC-9: a deleted service leaves the workspace and the menu, is "not found", keeps its terms versions, frees its name and its category; a stale version is 409 conflict', async ({ identity, operator }) => {
  const cat = new Catalogue(identity);
  const { salon, owner, created } = await newSalon(operator, identity);
  const s = await serviceIn(cat, salon, owner.tokens, 'Bridal', { name: 'Bridal trial' });
  const updated = await cat.update(salon, owner.tokens, s, { priceMinor: rupees(9000) });
  const before = catalogueSnapshot(created.id);
  await refused(await cat.deleteRaw(salon, owner.tokens, s.id, s.version), 409, 'conflict', 'stale delete');
  expect(catalogueSnapshot(created.id), 'nothing changed').toBe(before);
  const rows = [termsRow(created.id, s.terms.versionId), termsRow(created.id, updated.terms.versionId)];
  await cat.remove(salon, owner.tokens, updated);
  expect((await cat.services(salon, owner.tokens)).map(x => x.id), 'gone from the workspace').not.toContain(s.id);
  await refused(await cat.serviceRaw(salon, owner.tokens, s.id), 404, 'not_found', 'read after delete');
  await refused(await cat.deleteRaw(salon, owner.tokens, s.id, updated.version), 404, 'not_found', 'delete again');
  await refused(await cat.updateRaw(salon, owner.tokens, s.id, updateOf(updated, { name: 'Renamed' })), 404, 'not_found', 'edit after delete');
  expect((await cat.menu(salon)).categories.flatMap(c => c.services).map(x => x.id), 'gone from the menu').not.toContain(s.id);
  expect([termsRow(created.id, s.terms.versionId), termsRow(created.id, updated.terms.versionId)], 'its versions are kept as they were').toEqual(rows);
  const bridal = await cat.category(salon, owner.tokens, 'Bridal');
  expect(bridal.serviceCount, 'the category counts it no more').toBe(0);
  await test.step('its name can be used again', async () => {
    const again = await cat.create(salon, owner.tokens, serviceInput(bridal.id, { name: 'Bridal trial' }));
    expect(again.id).not.toBe(s.id);
    expect(again.terms.number).toBe(1);
    await cat.remove(salon, owner.tokens, again);
  });
  await test.step('its category, now empty, can be deleted', async () => {
    await cat.deleteCategory(salon, owner.tokens, await cat.category(salon, owner.tokens, 'Bridal'));
  });
});

// Case 020 (AC-9, 409 in_use while an upcoming booking uses the service) is deferred to F07: stage.yaml verification.deferred.

// ---------------------------------------------------------------- AC-10 photos

test('TC-F05-S01-021 AC-10: a PNG, JPEG or WebP photo of at least 320 x 320 is kept as a square JPEG of at most 800 without metadata, served on the salon\'s hostname to anyone, replaced by the next, and makes no terms version', async ({ identity, operator }) => {
  const cat = new Catalogue(identity);
  const { salon, owner, created } = await newSalon(operator, identity);
  const s = await serviceIn(cat, salon, owner.tokens, 'Colour');
  const rows: { label: string; file: () => FileUpload; side: number }[] = [
    { label: 'a 700 x 600 PNG: its 600 x 600 centre', file: PHOTO, side: 600 },
    { label: 'a 2000 x 800 JPEG with camera and GPS data: 800 x 800 without them', file: () => fixture('cover-2000x800-exif.jpg'), side: 800 },
    { label: 'a 1000 x 400 WebP: 400 x 400', file: () => fixture('cover-1000x400.webp'), side: 400 },
    { label: 'the smallest, 320 x 320', file: SMALL_PHOTO, side: 320 },
    { label: 'a 1200 x 1000 PNG: scaled to 800 x 800', file: () => pngUpload(grayPng(1200, 1000)), side: 800 },
    { label: 'a declared type that is wrong does not matter when the content is a PNG', file: () => fixture('logo-700x600-banded.png', 'application/pdf', 'photo.pdf'), side: 600 },
  ];
  let previous: string | null = null;
  for (const row of rows) {
    await test.step(row.label, async () => {
      const updated = await cat.upload(salon, owner.tokens, s.id, row.file());
      const id = photoIdOf(updated.photoUrl!);
      expect(id, 'a new photo replaces the last').not.toBe(previous);
      const served = await cat.photo(salon.hostname, id);
      expect(served.status(), 'served to anyone on the salon\'s hostname').toBe(200);
      expect((served.headers()['content-type'] ?? '').split(';')[0]).toBe('image/jpeg');
      const body = await served.body();
      const jpeg = readJpeg(body);
      expect([jpeg.width, jpeg.height], 'a square of the expected side').toEqual([row.side, row.side]);
      expect(jpeg.markers.filter(m => m === 0xe1 || m === 0xfe), 'no Exif (APP1) or comment segment').toEqual([]);
      for (const text of ['QA-CAMERA', 'QA-SOFTWARE', 'QA-JPEG-COMMENT', 'QA-PNG-', 'GPS']) expect(body.toString('latin1'), text).not.toContain(text);
      if (previous) expect((await cat.photo(salon.hostname, previous)).status(), 'the replaced photo is no longer served').toBe(404);
      previous = id;
    });
  }
  expect((await cat.service(salon, owner.tokens, s.id)).terms.versionId, 'a photo makes no terms version').toBe(s.terms.versionId);
  expect(termsRows(created.id)).toBe(1);
  await cat.dispose();
});

test('TC-F05-S01-022 AC-10: a file that is not PNG, JPEG or WebP by content, is over 5 MB, is under 320 pixels on a side, is undecodable or is missing is refused on file, and the service keeps its previous photo', async ({ identity, operator }) => {
  test.setTimeout(180_000);
  const cat = new Catalogue(identity);
  const { salon, owner, created } = await newSalon(operator, identity);
  const s = await serviceIn(cat, salon, owner.tokens, 'Colour');
  const withPhoto = await cat.upload(salon, owner.tokens, s.id, SMALL_PHOTO());
  const kept = await (await cat.photo(salon.hostname, photoIdOf(withPhoto.photoUrl!))).body();
  const big = grayPng(400, 400, { padding: 6 * 1024 * 1024 });
  expect(big.length, 'a valid PNG over 5 MB').toBeGreaterThan(LIMITS.photoBytes);
  const rows: { label: string; file: FileUpload | null }[] = [
    { label: 'text named .png and declared image/png', file: fixture('not-an-image.txt', 'image/png', 'photo.png') },
    { label: 'an SVG declared image/png', file: fixture('logo-300.svg', 'image/png', 'photo.png') },
    { label: 'a GIF', file: fixture('logo-300.gif') },
    { label: 'a BMP renamed .jpg', file: fixture('logo-300.bmp', 'image/jpeg', 'photo.jpg') },
    { label: 'a truncated PNG', file: fixture('logo-truncated.png') },
    { label: 'a 300 x 300 WebP', file: fixture('logo-300.webp') },
    { label: '319 x 320', file: pngUpload(grayPng(319, 320)) },
    { label: '320 x 319', file: pngUpload(grayPng(320, 319)) },
    { label: 'a valid PNG of 6 MB', file: pngUpload(big) },
    { label: 'a PNG declaring 60000 x 60000 pixels', file: pngUpload(grayPng(16, 16, { declared: [60000, 60000] })) },
    { label: 'an empty file', file: pngUpload(Buffer.alloc(0), 'empty.png') },
    { label: 'no file part', file: null },
  ];
  // Every row runs and every wrong answer is listed: one refusal missing must not hide another.
  const wrong: string[] = [];
  for (const row of rows) {
    await test.step(row.label, async () => {
      const before = catalogueSnapshot(created.id);
      const r = await cat.uploadRaw(salon, owner.tokens, s.id, row.file);
      const body = await r.text();
      let fields: string[] = [];
      try { fields = ((JSON.parse(body) as { errors?: { field: string }[] }).errors ?? []).map(e => e.field); } catch { /* not problem details */ }
      const field = new RegExp(`(^|\\.)${PENDING_F05_S01.photoField}$`);
      if (r.status() !== 400 || !body.includes('"bad_request"') || fields.length === 0 || !fields.every(f => field.test(f))) wrong.push(`${row.label}: ${r.status()} ${body.slice(0, 160)}`);
      if (catalogueSnapshot(created.id) !== before) wrong.push(`${row.label}: something was stored`);
    });
  }
  expect(wrong, 'every refused file is a 400 on its field that stores nothing').toEqual([]);
  const after = await cat.service(salon, owner.tokens, s.id);
  expect(after.photoUrl, 'the previous photo is kept').toBe(withPhoto.photoUrl);
  expect(Buffer.compare(await (await cat.photo(salon.hostname, photoIdOf(after.photoUrl!))).body(), kept), 'and served as it was').toBe(0);
  expect((await identity.api.health()).status(), 'the API is healthy after the hostile files').toBe(200);
  await cat.dispose();
});

test('TC-F05-S01-023 AC-10, AC-14: a photo is served on its own salon\'s hostname by the API and through the web, never on another salon\'s or an unknown hostname, and Remove photo removes it', async ({ identity, operator }) => {
  const cat = new Catalogue(identity);
  const a = await newSalon(operator, identity);
  const b = await newSalon(operator, identity);
  const s = await serviceIn(cat, a.salon, a.owner.tokens, 'Nails');
  const id = photoIdOf((await cat.upload(a.salon, a.owner.tokens, s.id, SMALL_PHOTO())).photoUrl!);
  const bytes = await (await cat.photo(a.salon.hostname, id)).body();
  await test.step('through the web on the salon\'s hostname', async () => {
    const web = await identity.api.send('GET', `${salonUrl(a.salon, webUrl)}${PENDING_F05_S01.webPhotoPath(id)}`);
    expect(web.status(), 'the BFF serves it').toBe(200);
    expect((web.headers()['content-type'] ?? '').split(';')[0]).toBe('image/jpeg');
    expect(Buffer.compare(await web.body(), bytes), 'the same image').toBe(0);
  });
  await test.step('another salon\'s and an unknown hostname answer as for an unknown photo', async () => {
    for (const host of [b.salon.hostname, unknownHost()]) {
      const r = await cat.photo(host, id);
      const unknown = await cat.photo(host, randomUUID());
      expect(r.status(), `${host}: not found`).toBe(404);
      expect(comparable(await r.text()), `${host}: the same as an unknown id`).toBe(comparable(await unknown.text()));
    }
    const webOther = await identity.api.send('GET', `${salonUrl(b.salon, webUrl)}${PENDING_F05_S01.webPhotoPath(id)}`);
    expect(webOther.status(), 'not through the other salon\'s web').toBe(404);
  });
  await test.step('Remove photo', async () => {
    const r = await cat.removePhotoRaw(a.salon, a.owner.tokens, s.id);
    expect([200, 204], `remove photo (${r.status()})`).toContain(r.status());
    expect((await cat.service(a.salon, a.owner.tokens, s.id)).photoUrl, 'no photo').toBeNull();
    expect((await cat.photo(a.salon.hostname, id)).status(), 'no longer served').toBe(404);
    const menuService = (await cat.menu(a.salon)).categories.flatMap(c => c.services).find(x => x.id === s.id)!;
    expect(menuField(menuService, 'photo'), 'the menu says null').toBeNull();
  });
  await cat.dispose();
});

// ---------------------------------------------------------------- AC-11 the public menu

test('TC-F05-S01-024 AC-11: the public menu needs no sign-in and lists, in the salon\'s category order, each category\'s online services by name with price, currency, the customer\'s time without buffer, photo and current terms version, leaving out hidden and deleted services and empty categories', async ({ identity, operator }) => {
  const cat = new Catalogue(identity);
  const { salon, owner, created } = await newSalon(operator, identity);
  const nails = await cat.category(salon, owner.tokens, 'Nails');
  await cat.move(salon, owner.tokens, nails.id, 'up');
  await cat.move(salon, owner.tokens, nails.id, 'up'); // Cuts & styling, Nails, Colour, ...
  const gel = await serviceIn(cat, salon, owner.tokens, 'Nails', { name: 'Gel polish', priceMinor: rupees(2500), durationMinutes: 45, bufferMinutes: 0 });
  const acrylic = await serviceIn(cat, salon, owner.tokens, 'Nails', { name: 'Acrylic set', priceMinor: rupees(5500), durationMinutes: 90, bufferMinutes: 15, description: 'Full set.' });
  await serviceIn(cat, salon, owner.tokens, 'Nails', { name: 'Nail art', bookableOnline: false });
  let root = await serviceIn(cat, salon, owner.tokens, 'Colour', { name: 'Root touch-up', priceMinor: rupees(4750), durationMinutes: 60, bufferMinutes: 15 });
  root = await cat.upload(salon, owner.tokens, root.id, SMALL_PHOTO());
  await serviceIn(cat, salon, owner.tokens, 'Waxing', { name: 'Brow wax', bookableOnline: false });
  await cat.remove(salon, owner.tokens, await serviceIn(cat, salon, owner.tokens, 'Bridal', { name: 'Bridal trial' }));
  const response = await cat.menuRaw(salon.hostname);
  const answer = await answerOf(response);
  const menu = await cat.menu(salon);
  expect(menu.currency).toBe('LKR');
  expect(menu.categories.map(c => c.name), 'only categories with online services, in the salon\'s order').toEqual(['Nails', 'Colour']);
  expect(menu.categories[0].id).toBe(nails.id);
  expect(menu.categories[0].services.map(x => x.name), 'by name').toEqual(['Acrylic set', 'Gel polish']);
  const [a, g] = menu.categories[0].services;
  const [r] = menu.categories[1].services;
  expect([a.id, g.id, r.id]).toEqual([acrylic.id, gel.id, root.id]);
  expect(a.description).toBe('Full set.');
  expect([menuField(a, 'price'), menuField(g, 'price'), menuField(r, 'price')], 'prices in minor units').toEqual([550000, 250000, 475000]);
  expect([menuField(a, 'minutes'), menuField(g, 'minutes'), menuField(r, 'minutes')], 'the active time only, no buffer').toEqual([90, 45, 60]);
  expect([menuField(a, 'photo'), menuField(g, 'photo')], 'no photo: null').toEqual([null, null]);
  expect(photoIdOf(String(menuField(r, 'photo'))), 'the photo\'s URL').toBe(photoIdOf(root.photoUrl!));
  expect([menuField(a, 'termsVersion'), menuField(r, 'termsVersion')]).toEqual([acrylic.terms.versionId, root.terms.versionId]);
  expect(answer.body, 'the tenant id is never shown').not.toContain(created.id);
  expectNothingOfOtherSalons(answer, null, 'the menu');
  await test.step('a price change shows at once with its new terms version', async () => {
    const changed = await cat.update(salon, owner.tokens, gel, { priceMinor: rupees(2750) });
    const again = (await cat.menu(salon)).categories[0].services.find(x => x.id === gel.id)!;
    expect(menuField(again, 'price')).toBe(275000);
    expect(menuField(again, 'termsVersion')).toBe(changed.terms.versionId);
  });
  await cat.dispose();
});

test('TC-F05-S01-025 AC-11, AC-14: each salon\'s menu shows only its own services, also when requests for two salons are sent together; an unknown, a suspended and a closed salon\'s menu answer as the salon\'s public brand does', async ({ identity, operator }) => {
  const cat = new Catalogue(identity);
  const a = await newSalon(operator, identity);
  const b = await newSalon(operator, identity);
  const sa = await serviceIn(cat, a.salon, a.owner.tokens, 'Nails', { name: `Alpha only ${marker()}` });
  const sb = await serviceIn(cat, b.salon, b.owner.tokens, 'Nails', { name: `Beta only ${marker()}` });
  const ids = async (salon: Salon) => (await cat.menu(salon)).categories.flatMap(c => c.services).map(x => x.id);
  expect(await ids(a.salon)).toEqual([sa.id]);
  expect(await ids(b.salon)).toEqual([sb.id]);
  await test.step('twenty requests for the two salons at once never cross', async () => {
    const order = Array.from({ length: 20 }, (_, n) => (n % 2 ? b : a));
    const answers = await Promise.all(order.map(x => cat.menuRaw(x.salon.hostname).then(r => r.text())));
    answers.forEach((text, n) => {
      const own = order[n] === a ? sa : sb;
      const other = order[n] === a ? sb : sa;
      expect(text, `request ${n}: its own service`).toContain(own.id);
      expect(text, `request ${n}: nothing of the other salon`).not.toContain(other.id);
      expect(text).not.toContain(other.name);
    });
  });
  const asBrand = async (host: string, label: string) => {
    const menu = await cat.menuRaw(host);
    const brand = await identity.api.getOn(host, '/api/v1/salon');
    expect(menu.status(), `${label}: the brand's status`).toBe(brand.status());
    expect(comparable(await menu.text()), `${label}: the brand's answer`).toBe(comparable(await brand.text()));
    return menu.status();
  };
  expect(await asBrand(unknownHost(), 'an unknown salon')).toBe(404);
  await test.step('suspended', async () => {
    await operatorJson(await operator.suspend(b.admin, b.created.id, await operator.version(b.admin, b.created.id)), 'salon');
    expect(await asBrand(b.salon.hostname, 'suspended')).toBe(403);
    await refused(await cat.menuRaw(b.salon.hostname), 403, 'salon_suspended', 'suspended menu');
  });
  await test.step('closed', async () => {
    await operatorJson(await operator.close(a.admin, a.created.id, await operator.version(a.admin, a.created.id), a.created.slug), 'salon');
    expect(await asBrand(a.salon.hostname, 'closed')).toBe(404);
  });
});

// ---------------------------------------------------------------- AC-13 roles and salon states

test('TC-F05-S01-026 AC-13: all five roles read the categories and services; only the Owner and Manager change them, the other roles are 403 permission_denied on every change and signed out is 401, each changing nothing; the menu and photos need no sign-in', async ({ identity }) => {
  test.setTimeout(240_000);
  const cat = new Catalogue(identity);
  const mark = marker();
  const manager = await identity.staff(alpha, 'MANAGER');
  try {
    const category = await cat.addCategory(alpha, manager.tokens, `QA roles ${mark}`);
    const service = await cat.upload(alpha, manager.tokens, (await cat.create(alpha, manager.tokens, serviceInput(category.id, { name: `QA roles ${mark}` }))).id, SMALL_PHOTO());
    const photoId = photoIdOf(service.photoUrl!);
    for (const role of ROLES_ALL) {
      await test.step(role, async () => {
        const actor = await identity.staff(alpha, role);
        expect((await cat.categories(alpha, actor.tokens)).items.map(c => c.id), `${role} reads the categories`).toContain(category.id);
        expect((await cat.services(alpha, actor.tokens)).map(s => s.id), `${role} reads the services`).toContain(service.id);
        expect((await cat.service(alpha, actor.tokens, service.id)).id, `${role} reads a service`).toBe(service.id);
        if (role === 'OWNER' || role === 'MANAGER') {
          const own = await cat.addCategory(alpha, actor.tokens, `QA ${role} ${mark}`);
          const renamed = await cat.renameCategory(alpha, actor.tokens, own, `QA ${role} renamed ${mark}`);
          await cat.move(alpha, actor.tokens, renamed.id, 'up');
          let s = await cat.create(alpha, actor.tokens, serviceInput(renamed.id, { name: `QA ${role} svc ${mark}` }));
          s = await cat.update(alpha, actor.tokens, s, { priceMinor: rupees(2600) });
          s = await cat.online(alpha, actor.tokens, s, false);
          const copy = await cat.duplicate(alpha, actor.tokens, s.id);
          s = await cat.upload(alpha, actor.tokens, s.id, SMALL_PHOTO());
          expect([200, 204]).toContain((await cat.removePhotoRaw(alpha, actor.tokens, s.id)).status());
          await cat.remove(alpha, actor.tokens, copy);
          await cat.remove(alpha, actor.tokens, await cat.service(alpha, actor.tokens, s.id));
          await cat.deleteCategory(alpha, actor.tokens, await cat.category(alpha, actor.tokens, `QA ${role} renamed ${mark}`));
          return;
        }
        const list = await cat.categories(alpha, manager.tokens);
        const routes = changeRoutes(await cat.service(alpha, manager.tokens, service.id), list.items.find(c => c.id === category.id)!, list.listVersion);
        for (const route of routes) {
          const before = catalogueSnapshot(alpha.id);
          await refused(await cat.call(alpha, route.method, route.path, actor.tokens, route.data), 403, 'permission_denied', `${role} ${route.label}`);
          expect(catalogueSnapshot(alpha.id), `${role} ${route.label}: nothing changed`).toBe(before);
        }
        const before = catalogueSnapshot(alpha.id);
        await refused(await cat.uploadRaw(alpha, actor.tokens, service.id, SMALL_PHOTO()), 403, 'permission_denied', `${role} upload photo`);
        expect(catalogueSnapshot(alpha.id), `${role} upload: nothing changed`).toBe(before);
      });
    }
    await test.step('signed out', async () => {
      for (const path of [CATEGORIES, SERVICES, servicePath(service.id)]) {
        await refused(await cat.call(alpha, 'GET', path), 401, 'invalid_session', `signed out GET ${path}`);
      }
      const list = await cat.categories(alpha, manager.tokens);
      for (const route of changeRoutes(service, list.items.find(c => c.id === category.id)!, list.listVersion)) {
        const before = catalogueSnapshot(alpha.id);
        await refused(await cat.call(alpha, route.method, route.path, undefined, route.data), 401, 'invalid_session', `signed out ${route.label}`);
        expect(catalogueSnapshot(alpha.id)).toBe(before);
      }
      expect((await cat.menuRaw(alpha.hostname)).status(), 'the menu is public').toBe(200);
      expect((await cat.photo(alpha.hostname, photoId)).status(), 'a photo is public').toBe(200);
    });
  } finally {
    await cat.removeMarked(alpha, manager.tokens, mark);
    await cat.dispose();
  }
});

test('TC-F05-S01-027 AC-13: a support session reads the categories and services as the Owner does and every change is 403 support_read_only, changing nothing', async ({ identity, operator }) => {
  const cat = new Catalogue(identity);
  const { created, salon, owner } = await newSalon(operator, identity);
  const service = await serviceIn(cat, salon, owner.tokens, 'Colour');
  const support = await operator.actor('OPERATOR_SUPPORT');
  const session = await supportJson<SupportStart>(await operator.startSupport(support, created.id, { reason: 'QA F05 support reads the service catalogue' }), 'supportStart', 201);
  const tokens: Tokens = await operator.supportCredential(await operator.exchange(session.entryCode, salon.hostname));
  expect(await cat.categories(salon, tokens), 'the same categories').toEqual(await cat.categories(salon, owner.tokens));
  expect(await cat.services(salon, tokens), 'the same services').toEqual(await cat.services(salon, owner.tokens));
  expect(await cat.service(salon, tokens, service.id)).toEqual(await cat.service(salon, owner.tokens, service.id));
  const list = await cat.categories(salon, owner.tokens);
  const colour = list.items.find(c => c.name === 'Colour')!;
  for (const route of changeRoutes(service, colour, list.listVersion)) {
    const before = catalogueSnapshot(created.id);
    await refused(await cat.call(salon, route.method, route.path, tokens, route.data), 403, 'support_read_only', `support ${route.label}`);
    expect(catalogueSnapshot(created.id), `support ${route.label}: nothing changed`).toBe(before);
  }
  const before = catalogueSnapshot(created.id);
  await refused(await cat.uploadRaw(salon, tokens, service.id, SMALL_PHOTO()), 403, 'support_read_only', 'support upload');
  expect(catalogueSnapshot(created.id)).toBe(before);
  await cat.dispose();
});

test('TC-F05-S01-028 AC-13: a suspended salon answers 403 salon_suspended on every catalogue endpoint, and a read-only salon reads, serves its menu and photos and refuses every change with salon_read_only', async ({ identity, operator, clock }) => {
  test.setTimeout(240_000);
  const cat = new Catalogue(identity);
  await clock.set('2026-11-20T07:00:00Z');
  await test.step('suspended', async () => {
    const { admin, created, salon, owner } = await newSalon(operator, identity);
    const service = await cat.upload(salon, owner.tokens, (await serviceIn(cat, salon, owner.tokens, 'Nails')).id, SMALL_PHOTO());
    const list = await cat.categories(salon, owner.tokens);
    const nails = list.items.find(c => c.name === 'Nails')!;
    await operatorJson(await operator.suspend(admin, created.id, await operator.version(admin, created.id)), 'salon');
    const before = catalogueSnapshot(created.id);
    const reads = [CATEGORIES, SERVICES, servicePath(service.id)].map(path => ({ label: `GET ${path}`, method: 'GET', path, data: undefined }));
    for (const route of [...reads, ...changeRoutes(service, nails, list.listVersion)]) {
      await refused(await cat.call(salon, route.method, route.path, owner.tokens, route.data), 403, 'salon_suspended', `suspended ${route.label}`);
    }
    await refused(await cat.menuRaw(salon.hostname), 403, 'salon_suspended', 'suspended menu');
    await refused(await cat.photo(salon.hostname, photoIdOf(service.photoUrl!)), 403, 'salon_suspended', 'suspended photo');
    await refused(await cat.uploadRaw(salon, owner.tokens, service.id, SMALL_PHOTO()), 403, 'salon_suspended', 'suspended upload');
    expect(catalogueSnapshot(created.id), 'nothing changed').toBe(before);
  });
  await test.step('read-only', async () => {
    const { admin, created, salon, owner } = await newSalon(operator, identity);
    const service = await cat.upload(salon, owner.tokens, (await serviceIn(cat, salon, owner.tokens, 'Nails')).id, SMALL_PHOTO());
    await readOnlySalon(operator, clock, admin, created.id);
    const now = await identity.login(salon, owner.account);
    expect((await cat.services(salon, now.tokens)).map(s => s.id), 'still readable').toEqual([service.id]);
    expect((await cat.menu(salon)).categories.flatMap(c => c.services).map(s => s.id), 'customers still read the menu').toEqual([service.id]);
    expect((await cat.photo(salon.hostname, photoIdOf(service.photoUrl!))).status(), 'and its photo').toBe(200);
    const list = await cat.categories(salon, now.tokens);
    const before = catalogueSnapshot(created.id);
    for (const route of changeRoutes(service, list.items.find(c => c.name === 'Nails')!, list.listVersion)) {
      await refused(await cat.call(salon, route.method, route.path, now.tokens, route.data), 403, 'salon_read_only', `read-only ${route.label}`);
    }
    await refused(await cat.uploadRaw(salon, now.tokens, service.id, SMALL_PHOTO()), 403, 'salon_read_only', 'read-only upload');
    expect(catalogueSnapshot(created.id), 'nothing changed').toBe(before);
  });
  await cat.dispose();
});

// ---------------------------------------------------------------- AC-14 tenant isolation

test('TC-F05-S01-029 AC-14: signed in to alpha, beta\'s categories, services and photos are "not found" to every read and change exactly as unknown ids are, never listed, and cannot be used as a service\'s category; beta\'s staff reach nothing on alpha', async ({ identity }) => {
  test.setTimeout(180_000);
  const cat = new Catalogue(identity);
  const mark = marker();
  const alphaManager = await identity.staff(alpha, 'MANAGER');
  const betaManager = await identity.staff(beta, 'MANAGER');
  try {
    const betaCategory = await cat.addCategory(beta, betaManager.tokens, `QA beta ${mark}`);
    const betaService = await cat.upload(beta, betaManager.tokens, (await cat.create(beta, betaManager.tokens, serviceInput(betaCategory.id, { name: `QA beta ${mark}` }))).id, SMALL_PHOTO());
    expect((await cat.service(beta, betaManager.tokens, betaService.id)).id, 'beta reads its own service').toBe(betaService.id);
    const alphaCategory = await cat.addCategory(alpha, alphaManager.tokens, `QA alpha ${mark}`);
    const alphaService = await cat.create(alpha, alphaManager.tokens, serviceInput(alphaCategory.id, { name: `QA alpha ${mark}` }));
    const betaBefore = catalogueSnapshot(beta.id);
    const alphaBefore = catalogueSnapshot(alpha.id);
    const unknownService = { ...betaService, id: randomUUID() };
    const unknownCategory = { ...betaCategory, id: randomUUID() };
    await test.step('every route with beta\'s ids answers as with unknown ids', async () => {
      const routes = [
        { label: 'GET service', method: 'GET', path: servicePath(betaService.id), unknown: servicePath(unknownService.id), data: undefined },
        // Alpha's own list version, so that the only thing wrong with a move is the category id.
        ...changeRoutes(betaService, betaCategory, (await cat.categories(alpha, alphaManager.tokens)).listVersion).filter(r => !['add category', 'create service'].includes(r.label)).map(r => ({
          ...r, unknown: r.path.replace(betaService.id, unknownService.id).replace(betaCategory.id, unknownCategory.id),
        })),
      ];
      for (const route of routes) {
        const theirs = await cat.call(alpha, route.method, route.path, alphaManager.tokens, route.data);
        const none = await cat.call(alpha, route.method, route.unknown, alphaManager.tokens, route.data);
        await refused(theirs, 404, 'not_found', `beta's id: ${route.label}`);
        expect(comparable(await theirs.text()), `${route.label}: the same as an unknown id`).toBe(comparable(await none.text()));
      }
      await refused(await cat.uploadRaw(alpha, alphaManager.tokens, betaService.id, SMALL_PHOTO()), 404, 'not_found', 'beta\'s id: upload photo');
    });
    await test.step('lists never show beta\'s records', async () => {
      expect((await cat.categories(beta, betaManager.tokens)).items.some(c => c.id === betaCategory.id), 'beta has the category').toBe(true);
      expect((await cat.services(beta, betaManager.tokens)).some(s => s.id === betaService.id), 'beta has the service').toBe(true);
      const betaIds = new Set([...(await cat.categories(beta, betaManager.tokens)).items.map(c => c.id), ...(await cat.services(beta, betaManager.tokens)).map(s => s.id)]);
      const seen = [...(await cat.categories(alpha, alphaManager.tokens)).items.map(c => c.id), ...(await cat.services(alpha, alphaManager.tokens)).map(s => s.id)];
      expect(seen.filter(x => betaIds.has(x)), 'none of beta\'s ids on alpha').toEqual([]);
    });
    await test.step('a service cannot be put in beta\'s category', async () => {
      const theirs = await cat.createRaw(alpha, alphaManager.tokens, serviceInput(betaCategory.id));
      const none = await cat.createRaw(alpha, alphaManager.tokens, serviceInput(unknownCategory.id));
      expect(theirs.status(), 'create in beta\'s category is refused').toBeGreaterThanOrEqual(400);
      expect(comparable(await theirs.text()), 'as an unknown category').toBe(comparable(await none.text()));
      const moved = await cat.updateRaw(alpha, alphaManager.tokens, alphaService.id, updateOf(alphaService, { categoryId: betaCategory.id }));
      const movedNone = await cat.updateRaw(alpha, alphaManager.tokens, alphaService.id, updateOf(alphaService, { categoryId: unknownCategory.id }));
      expect(moved.status(), 'moving into beta\'s category is refused').toBeGreaterThanOrEqual(400);
      expect(comparable(await moved.text()), 'as an unknown category').toBe(comparable(await movedNone.text()));
    });
    await test.step('beta\'s Manager on alpha\'s hostname', async () => {
      const list = await cat.categories(alpha, alphaManager.tokens);
      for (const path of [CATEGORIES, SERVICES, servicePath(alphaService.id)]) {
        await refused(await cat.call(alpha, 'GET', path, betaManager.tokens), 404, 'not_found', `beta on alpha: GET ${path}`);
      }
      for (const route of changeRoutes(alphaService, list.items.find(c => c.id === alphaCategory.id)!, list.listVersion)) {
        await refused(await cat.call(alpha, route.method, route.path, betaManager.tokens, route.data), 404, 'not_found', `beta on alpha: ${route.label}`);
      }
    });
    expect(catalogueSnapshot(alpha.id), 'alpha changed in no way').toBe(alphaBefore);
    expect(catalogueSnapshot(beta.id), 'beta changed in no way').toBe(betaBefore);
  } finally {
    await cat.removeMarked(alpha, alphaManager.tokens, mark);
    await cat.removeMarked(beta, betaManager.tokens, mark);
    await cat.dispose();
  }
});

test('TC-F05-S01-030 AC-14: with no tenant context every catalogue table returns no rows to the application role, and a salon\'s context shows only its own rows', async ({ identity }) => {
  const cat = new Catalogue(identity);
  const mark = marker();
  const managers = { alpha: await identity.staff(alpha, 'MANAGER'), beta: await identity.staff(beta, 'MANAGER') };
  try {
    for (const [salon, m] of [[alpha, managers.alpha], [beta, managers.beta]] as const) {
      const c = await cat.addCategory(salon, m.tokens, `QA rls ${mark}`);
      await cat.upload(salon, m.tokens, (await cat.create(salon, m.tokens, serviceInput(c.id, { name: `QA rls ${mark}` }))).id, SMALL_PHOTO());
    }
    const alphaRows = catalogueRows(alpha.id);
    const betaRows = catalogueRows(beta.id);
    for (const table of catalogueTables()) {
      expect(alphaRows[table], `${table}: alpha has rows`).toBeGreaterThan(0);
      expect(betaRows[table], `${table}: beta has rows`).toBeGreaterThan(0);
      expect(identitySql(`SELECT count(*) FROM ${table}`, null), `${table}: no tenant, no rows`).toBe('0');
      expect(Number(identitySql(`SELECT count(*) FROM ${table}`, alpha.id)), `${table}: alpha sees its rows`).toBe(alphaRows[table]);
      expect(identitySql(`SELECT count(*) FROM ${table} WHERE tenant_id <> ${sqlLiteral(alpha.id)}`, alpha.id), `${table}: and no other salon's`).toBe('0');
    }
  } finally {
    await cat.removeMarked(alpha, managers.alpha.tokens, mark);
    await cat.removeMarked(beta, managers.beta.tokens, mark);
    await cat.dispose();
  }
});

// ---------------------------------------------------------------- AC-15 audit

test('TC-F05-S01-031 AC-15: every change to a category or a service is audited with the actor, the time and the before and after values, a terms change names its new version, and a refused change is not audited', async ({ identity, operator }) => {
  const cat = new Catalogue(identity);
  const { salon, owner, created } = await newSalon(operator, identity);
  const t0 = await identity.now();
  let seen = catalogueAudit(created.id, t0).length;
  /** Runs a change and returns the entries it wrote: at least one, each by the Owner, inside the change's time. */
  const audited = async (label: string, change: () => Promise<unknown>, subjectId?: () => string) => {
    const from = await identity.now();
    await change();
    const to = await identity.now();
    const rows = catalogueAudit(created.id, t0).slice(seen);
    seen += rows.length;
    expect(rows.length, `${label}: audited`).toBeGreaterThan(0);
    for (const row of rows) {
      expect(row.actorId, `${label}: the actor`).toBe(owner.principal.id);
      expect(Date.parse(row.at)).toBeGreaterThanOrEqual(Date.parse(from) - 1000);
      expect(Date.parse(row.at)).toBeLessThanOrEqual(Date.parse(to) + 1000);
    }
    if (subjectId) expect(rows.map(r => r.subjectId), `${label}: the subject`).toContain(subjectId());
    return rows.map(r => r.details).join('\n');
  };
  let category!: Category;
  let s!: Service;
  let copy!: Service;
  expect(await audited('add a category', async () => { category = await cat.addCategory(salon, owner.tokens, 'Kids’ cuts'); }, () => category.id)).toContain('Kids’ cuts');
  const renamed = await audited('rename it', async () => { category = await cat.renameCategory(salon, owner.tokens, category, 'Kids cuts'); }, () => category.id);
  expect(renamed, 'before').toContain('Kids’ cuts');
  expect(renamed, 'after').toContain('Kids cuts');
  await audited('move it', () => cat.move(salon, owner.tokens, category.id, 'up'));
  await audited('create a service', async () => { s = await cat.create(salon, owner.tokens, serviceInput(category.id, { name: 'Kids trim', priceMinor: rupees(1500) })); }, () => s.id);
  const name = await audited('rename the service', async () => { s = await cat.update(salon, owner.tokens, s, { name: 'Kids haircut' }); }, () => s.id);
  expect(name).toContain('Kids trim');
  expect(name).toContain('Kids haircut');
  const price = await audited('change its price', async () => { s = await cat.update(salon, owner.tokens, s, { priceMinor: rupees(1750) }); }, () => s.id);
  expect(price, 'before').toContain('150000');
  expect(price, 'after').toContain('175000');
  expect(price, 'the new terms version').toContain(s.terms.versionId);
  await audited('hide it online', async () => { s = await cat.online(salon, owner.tokens, s, false); }, () => s.id);
  await audited('duplicate it', async () => { copy = await cat.duplicate(salon, owner.tokens, s.id); }, () => copy.id);
  await audited('add a photo', async () => { s = await cat.upload(salon, owner.tokens, s.id, SMALL_PHOTO()); }, () => s.id);
  await audited('remove the photo', async () => {
    expect([200, 204]).toContain((await cat.removePhotoRaw(salon, owner.tokens, s.id)).status());
    s = await cat.service(salon, owner.tokens, s.id);
  }, () => s.id);
  await audited('delete the copy', () => cat.remove(salon, owner.tokens, copy), () => copy.id);
  const empty = await cat.addCategory(salon, owner.tokens, 'Short-lived');
  seen = catalogueAudit(created.id, t0).length;
  await audited('delete a category', () => cat.deleteCategory(salon, owner.tokens, empty), () => empty.id);
  await test.step('a refused change is not audited', async () => {
    await refused(await cat.updateRaw(salon, owner.tokens, s.id, { ...updateOf(s, { priceMinor: rupees(1800) }), version: s.version - 1 }), 409, 'conflict', 'stale');
    const current = await cat.category(salon, owner.tokens, 'Kids cuts');
    await refused(await cat.deleteCategoryRaw(salon, owner.tokens, current.id, current.version), 409, 'in_use', 'in use');
    expect(catalogueAudit(created.id, t0).length, 'nothing more audited').toBe(seen);
  });
  await cat.dispose();
});
