/**
 * F05-S02 variants, Popular and a service's deposit from outside: variants inside the terms
 * version (every boundary, stable ids, versions with their variants kept, segments per variant),
 * the lowest and highest values, the menu's variants and Popular list, the six-service limit and two
 * services chosen at once, the Popular setting with its fallback, a service's own deposit, duplicate,
 * roles, a support session, tenant isolation and the audit trail.
 *
 * Expected results are the F05-S02 spec's (acceptance criteria, "API and data changes", "Design
 * notes"), decisions 091 and 095, and the F05-S01 catalogue as built (its "Corrections at close").
 * Prepared before the build: names only the build gives are in `PENDING_F05_S02`
 * (qa/shared/catalogue.ts), confirmed at the dry run.
 *
 * Not automated here: the "Most booked" ranking by booking counts, its 90-day window and leaving out
 * hidden and deleted services need bookings (case 015 of this stage, deferred to F07; the spec has an
 * integration test prove them). The mapping of a service's deposit to `booking.DepositRule` is a
 * Java function with no route; the developer's tests prove it, and these cases prove the deposit the
 * API stores and returns with each terms version.
 */
import { randomUUID } from 'node:crypto';

import { refused } from '../../shared/booking-rules';
import {
  CatalogueS02, PENDING_F05_S02, POPULAR_SETTING, popularPath, servicePath, catalogueAudit, catalogueSnapshot, catalogueTables, comparable, fieldRefusal,
  depositOf, marker, menuField, newSalon, rupees, saveOf, serviceInput, termsRows, variantInput, variantSegments, versionWithVariants,
  type ServiceS02, type VariantInput,
} from '../../shared/catalogue';
import type { Role, Tokens } from '../../shared/identity';
import { identitySql } from '../../shared/identity-storage';
import { test, expect, alpha, sqlLiteral, supportJson, type SupportStart } from '../../shared/operator';
import type { Salon } from '../../shared/tenants';

test.describe.configure({ timeout: 120_000 });

const ROLES_ALL: Role[] = ['OWNER', 'MANAGER', 'RECEPTIONIST', 'PROFESSIONAL', 'ACCOUNTANT'];
const chars = (n: number, letter = 'a') => `${letter.toUpperCase()}${letter.repeat(n - 1)}`;
const items = (n: number, from = 1): VariantInput[] => Array.from({ length: n }, (_, k) => ({
  name: `Option ${from + k}`, priceMinor: rupees(1000 + (from + k) * 100), durationMinutes: 30 + ((from + k) % 4) * 5,
}));
const menuServices = (menu: { categories: { services: Record<string, unknown>[] }[] }) => menu.categories.flatMap(c => c.services);

/** A service with variants in a named category of the salon, created now. */
async function withVariants(cat: CatalogueS02, salon: Salon, tokens: Tokens, categoryName = 'Cuts & styling', list?: VariantInput[], overrides: Record<string, unknown> = {}) {
  const category = await cat.category(salon, tokens, categoryName);
  return cat.createS02(salon, tokens, variantInput(category.id, list, overrides));
}

/** A plain service (no variants) as an S02 service. */
async function plain(cat: CatalogueS02, salon: Salon, tokens: Tokens, categoryName: string, overrides: Record<string, unknown> = {}) {
  const category = await cat.category(salon, tokens, categoryName);
  return cat.createS02(salon, tokens, { ...serviceInput(category.id), ...overrides });
}

// ---------------------------------------------------------------- AC-1 to AC-4 variants

test('TC-F05-S02-001 AC-1, AC-2, AC-3, AC-4: a service with three variants keeps them in order with ids, prices, times and segments with the buffer, its lowest and highest price and time, and the menu returns them; a service without variants returns none', async ({ identity, operator }) => {
  const cat = new CatalogueS02(identity);
  const { salon, owner } = await newSalon(operator, identity);
  const s = await withVariants(cat, salon, owner.tokens, 'Cuts & styling', undefined, { name: 'Ladies cut', bufferMinutes: 10 });
  expect(s.terms.variantSetName).toBe('Hair length');
  expect(s.terms.variants.map(v => [v.name, v.priceMinor, v.durationMinutes]), 'in the order given').toEqual([['Short', 250000, 30], ['Medium', 300000, 45], ['Long', 350000, 60]]);
  expect(new Set(s.terms.variants.map(v => v.id)).size, 'an id each').toBe(3);
  for (const v of s.terms.variants) expect(v.segments, `${v.name}: its time, then the buffer`).toEqual(variantSegments(v.durationMinutes, 10));
  expect(s.terms).toMatchObject({ number: 1, priceMinMinor: 250000, priceMaxMinor: 350000, durationMinMinutes: 30, durationMaxMinutes: 60 });
  expect([s.terms.priceMinor, s.terms.durationMinutes], 'the version\'s own price and time are the lowest and shortest (design notes)').toEqual([250000, 30]);
  expect(await cat.serviceS02(salon, owner.tokens, s.id), 'the same on reading it back').toEqual(s);
  const menu = await cat.menuS02(salon);
  const m = menuServices(menu).find(x => x.id === s.id)! as Record<string, unknown> & { variants: Record<string, unknown>[] };
  expect(m.variants.map(v => [v.id, v.name, v[PENDING_F05_S02.menuVariant.price], v[PENDING_F05_S02.menuVariant.minutes]]), 'the menu\'s variants, in order').toEqual(
    s.terms.variants.map(v => [v.id, v.name, v.priceMinor, v.durationMinutes]));
  const r = PENDING_F05_S02.menuRange;
  expect([m[r.priceMin], m[r.priceMax], m[r.durationMin], m[r.durationMax]], 'the menu\'s lowest and highest').toEqual([250000, 350000, 30, 60]);
  const p = await plain(cat, salon, owner.tokens, 'Nails', { name: 'Gel polish', priceMinor: rupees(2500), durationMinutes: 45, bufferMinutes: 0 });
  expect(p.terms.variants, 'no variants: an empty list').toEqual([]);
  expect(p.terms).toMatchObject({ variantSetName: null, priceMinMinor: 250000, priceMaxMinor: 250000, durationMinMinutes: 45, durationMaxMinutes: 45, segments: variantSegments(45, 0) });
  expect(menuServices(await cat.menuS02(salon)).find(x => x.id === p.id)!.variants, 'the menu: an empty list').toEqual([]);
});

test('TC-F05-S02-002 AC-1: two and ten variants, set names of 2 and 30, variant names of 30, Rs. 100 and Rs. 500,000 and 5 and 480 minutes are accepted without a price or time of the service\'s own', async ({ identity, operator }) => {
  const cat = new CatalogueS02(identity);
  const { salon, owner } = await newSalon(operator, identity);
  const { id: categoryId } = await cat.category(salon, owner.tokens, 'Colour');
  const rows: [string, VariantInput[], Record<string, unknown>][] = [
    ['two variants', items(2), {}],
    ['ten variants', items(10), {}],
    ['a set name of 2', items(2, 20), { variants: { name: 'Ab', items: items(2, 20) } }],
    ['a set name of 30', items(2, 30), { variants: { name: chars(30, 's'), items: items(2, 30) } }],
    ['a variant name of 30', [{ name: chars(30, 'v'), priceMinor: rupees(100), durationMinutes: 5 }, { name: 'Other', priceMinor: rupees(500_000), durationMinutes: 480 }], {}],
  ];
  for (const [label, list, extra] of rows) {
    await test.step(label, async () => {
      const s = await cat.createS02(salon, owner.tokens, variantInput(categoryId, list, { name: `QA ${marker()}`, ...extra }));
      expect(s.terms.variants.map(v => [v.name, v.priceMinor, v.durationMinutes]), label).toEqual(list.map(v => [v.name, v.priceMinor, v.durationMinutes]));
      if ('variants' in extra) expect(s.terms.variantSetName).toBe((extra.variants as { name: string }).name);
    });
  }
});

test('TC-F05-S02-003 AC-1: one or eleven variants, a set name of 1 or 31, a variant name empty, of 31 or repeated in any case, a price or time out of range, an unknown variant id or a set without a name is a 400 on that field and saves nothing', async ({ identity, operator }) => {
  const cat = new CatalogueS02(identity);
  const { salon, owner, created } = await newSalon(operator, identity);
  const { id: categoryId } = await cat.category(salon, owner.tokens, 'Colour');
  const existing = await cat.createS02(salon, owner.tokens, variantInput(categoryId, undefined, { name: 'Existing' }));
  const three = items(3);
  const set = (list: unknown[], name: unknown = 'Hair length') => ({ variants: { name, items: list } });
  const at = (i: number, change: Record<string, unknown>) => three.map((v, k) => (k === i ? { ...v, ...change } : v));
  const rows: [string, RegExp, Record<string, unknown>][] = [
    ['one variant', /^variants(\.items)?$/, set(items(1))],
    ['eleven variants', /^variants(\.items)?$/, set(items(11))],
    ['a set name of 1', /^variants\.name$/, set(three, 'A')],
    ['a set name of 31', /^variants\.name$/, set(three, chars(31))],
    ['a set without a name', /^variants\.name$/, set(three, null)],
    ['an empty variant name', new RegExp(`^${PENDING_F05_S02.variantField(1, 'name')}$`), set(at(1, { name: '' }))],
    ['a variant name of 31', new RegExp(`^${PENDING_F05_S02.variantField(1, 'name')}$`), set(at(1, { name: chars(31) }))],
    ['a repeated name in another case', new RegExp(`^${PENDING_F05_S02.variantField(2, 'name')}$`), set(at(2, { name: 'OPTION 1' }))],
    ['Rs. 99', new RegExp(`^${PENDING_F05_S02.variantField(0, 'priceMinor')}$`), set(at(0, { priceMinor: rupees(99) }))],
    ['Rs. 500,001', new RegExp(`^${PENDING_F05_S02.variantField(0, 'priceMinor')}$`), set(at(0, { priceMinor: rupees(500_001) }))],
    ['part of a rupee', new RegExp(`^${PENDING_F05_S02.variantField(0, 'priceMinor')}$`), set(at(0, { priceMinor: 10050 }))],
    ['0 minutes', new RegExp(`^${PENDING_F05_S02.variantField(2, 'durationMinutes')}$`), set(at(2, { durationMinutes: 0 }))],
    ['7 minutes', new RegExp(`^${PENDING_F05_S02.variantField(2, 'durationMinutes')}$`), set(at(2, { durationMinutes: 7 }))],
    ['485 minutes', new RegExp(`^${PENDING_F05_S02.variantField(2, 'durationMinutes')}$`), set(at(2, { durationMinutes: 485 }))],
  ];
  const wrong: string[] = [];
  const check = async (label: string, field: RegExp, send: () => ReturnType<CatalogueS02['createRaw']>) => {
    const before = catalogueSnapshot(created.id);
    const r = await send();
    const body = await r.text();
    let fields: string[] = [];
    try { fields = ((JSON.parse(body) as { errors?: { field: string }[] }).errors ?? []).map(e => e.field); } catch { /* not problem details */ }
    if (r.status() !== 400 || !body.includes('"bad_request"') || fields.length === 0 || !fields.every(f => field.test(f))) wrong.push(`${label}: ${r.status()} ${body.slice(0, 200)}`);
    if (catalogueSnapshot(created.id) !== before) wrong.push(`${label}: something was saved`);
  };
  for (const [label, field, change] of rows) {
    await test.step(`create: ${label}`, () => check(`create ${label}`, field, () => cat.createRaw(salon, owner.tokens, variantInput(categoryId, undefined, { name: `QA ${marker()}`, ...change }))));
    await test.step(`edit: ${label}`, () => check(`edit ${label}`, field, () => cat.updateRaw(salon, owner.tokens, existing.id, saveOf(existing, change))));
  }
  await test.step('an unknown variant id', () => check('unknown variant id', new RegExp(`^${PENDING_F05_S02.variantField(1, 'id')}$`),
    () => cat.updateRaw(salon, owner.tokens, existing.id, saveOf(existing, { variants: { name: 'Hair length', items: existing.terms.variants.map((v, k) => ({ ...v, id: k === 1 ? randomUUID() : v.id, segments: undefined })) } }))));
  expect(wrong, 'every broken variant rule is a 400 on its field that saves nothing').toEqual([]);
});

test('TC-F05-S02-004 AC-2: adding, removing, renaming, reordering a variant or changing its price or time makes a new terms version that keeps the variants\' ids; earlier versions keep their variants; turning variants off needs a price and a time of the service\'s own', async ({ identity, operator }) => {
  const cat = new CatalogueS02(identity);
  const { salon, owner, created } = await newSalon(operator, identity);
  let s = await withVariants(cat, salon, owner.tokens);
  const [short, medium, long] = s.terms.variants;
  const v1 = s.terms.versionId;
  const v1Rows = versionWithVariants(created.id, v1);
  expect(v1Rows, 'version 1 and its variants are stored').toContain('Medium');
  const keep = (list: { id?: string; name: string; priceMinor: number; durationMinutes: number }[]) => ({ variants: { name: 'Hair length', items: list } });
  const strip = (v: typeof short) => ({ id: v.id, name: v.name, priceMinor: v.priceMinor, durationMinutes: v.durationMinutes });
  const steps: [string, Record<string, unknown>, (after: ServiceS02) => void][] = [
    ['a price', keep([strip(short), { ...strip(medium), priceMinor: rupees(3200) }, strip(long)]), a => expect(a.terms.variants[1].priceMinor).toBe(320000)],
    ['a time', keep([strip(short), strip(medium), { ...strip(long), durationMinutes: 75 }]), a => expect(a.terms.variants[2].durationMinutes).toBe(75)],
    ['a name', keep([{ ...strip(short), name: 'Short (above the ear)' }, strip(medium), strip(long)]), a => expect(a.terms.variants[0].name).toBe('Short (above the ear)')],
    ['the order', keep([strip(long), strip(short), strip(medium)]), a => expect(a.terms.variants.map(v => v.id)).toEqual([long.id, short.id, medium.id])],
    ['a new variant', keep([strip(long), strip(short), strip(medium), { name: 'Extra long', priceMinor: rupees(4000), durationMinutes: 90 }]), a => expect(a.terms.variants).toHaveLength(4)],
    ['a removed variant', keep([strip(short), strip(medium), strip(long)]), a => expect(a.terms.variants.map(v => v.id)).toEqual([short.id, medium.id, long.id])],
  ];
  for (const [label, change, check] of steps) {
    await test.step(`changing ${label}`, async () => {
      const prior = s;
      const priorRows = versionWithVariants(created.id, prior.terms.versionId);
      // The variants the request names keep their ids; take the current ids for the ones this step keeps.
      const current = Object.fromEntries(prior.terms.variants.map(v => [v.id, v]));
      const items = (change.variants as { items: { id?: string; name: string; priceMinor: number; durationMinutes: number }[] }).items
        .map(v => (v.id && !current[v.id] ? { ...v, id: undefined } : v));
      s = await cat.saveS02(salon, owner.tokens, prior, { variants: { name: 'Hair length', items } });
      check(s);
      expect(s.terms.number, `${label}: a new version`).toBe(prior.terms.number + 1);
      for (const v of s.terms.variants.filter(x => current[x.id])) expect(prior.terms.variants.map(x => x.id), `${label}: ${v.name} keeps its id`).toContain(v.id);
      expect(versionWithVariants(created.id, prior.terms.versionId), `${label}: the version before, with its variants, is unchanged`).toBe(priorRows);
    });
  }
  const extraLong = s.terms.variants.find(v => v.name === 'Extra long');
  expect(extraLong, 'the removed variant is gone').toBeUndefined();
  expect(versionWithVariants(created.id, v1), 'version 1 with its three variants is unchanged').toBe(v1Rows);
  await test.step('a save that changes nothing', async () => {
    const before = catalogueSnapshot(created.id);
    await refused(await cat.updateRaw(salon, owner.tokens, s.id, saveOf(s)), 409, 'invalid_state', 'nothing changed');
    expect(catalogueSnapshot(created.id)).toBe(before);
  });
  await test.step('turning variants off', async () => {
    const before = catalogueSnapshot(created.id);
    await fieldRefusal(await cat.updateRaw(salon, owner.tokens, s.id, saveOf(s, { variants: null })), '(priceMinor|durationMinutes)', 'off without a price or time');
    expect(catalogueSnapshot(created.id), 'nothing saved').toBe(before);
    const off = await cat.saveS02(salon, owner.tokens, s, { variants: null, priceMinor: rupees(2800), durationMinutes: 40 });
    expect(off.terms).toMatchObject({ variants: [], variantSetName: null, priceMinor: 280000, durationMinutes: 40, priceMinMinor: 280000, priceMaxMinor: 280000 });
    expect(off.terms.number).toBe(s.terms.number + 1);
  });
});

test('TC-F05-S02-005 AC-2: of eight saves of one version with variants sent at once exactly one succeeds, the rest are 409 conflict, and exactly one terms version is added', async ({ identity, operator }) => {
  const cat = new CatalogueS02(identity);
  const { salon, owner, created } = await newSalon(operator, identity);
  const s = await withVariants(cat, salon, owner.tokens);
  const rows = termsRows(created.id);
  const prices = Array.from({ length: 8 }, (_, n) => rupees(2600 + n * 10));
  const bodies = prices.map(p => saveOf(s, { variants: { name: 'Hair length', items: s.terms.variants.map((v, k) => ({ id: v.id, name: v.name, priceMinor: k === 0 ? p : v.priceMinor, durationMinutes: v.durationMinutes })) } }));
  const responses = await Promise.all(bodies.map(b => cat.updateRaw(salon, owner.tokens, s.id, b)));
  const won = responses.map((r, n) => ({ r, n })).filter(x => x.r.status() === 200);
  expect(won.length, 'exactly one save').toBe(1);
  for (const r of responses.filter(x => x.status() !== 200)) await refused(r, 409, 'conflict', 'a losing save');
  expect(termsRows(created.id), 'exactly one version added').toBe(rows + 1);
  expect((await cat.serviceS02(salon, owner.tokens, s.id)).terms.variants[0].priceMinor, 'the winner\'s price').toBe(prices[won[0].n]);
});

test('TC-F05-S02-006 AC-3: the lowest and highest values follow the variants: equal prices give the same lowest and highest, and the menu agrees with the service', async ({ identity, operator }) => {
  const cat = new CatalogueS02(identity);
  const { salon, owner } = await newSalon(operator, identity);
  const same = await withVariants(cat, salon, owner.tokens, 'Nails', [
    { name: 'Hands', priceMinor: rupees(2000), durationMinutes: 30 }, { name: 'Feet', priceMinor: rupees(2000), durationMinutes: 45 },
  ], { name: 'Polish' });
  expect(same.terms).toMatchObject({ priceMinMinor: 200000, priceMaxMinor: 200000, durationMinMinutes: 30, durationMaxMinutes: 45 });
  const varies = await withVariants(cat, salon, owner.tokens, 'Nails', [
    { name: 'Ten nails', priceMinor: rupees(4500), durationMinutes: 60 }, { name: 'One nail', priceMinor: rupees(500), durationMinutes: 60 },
  ], { name: 'Nail art' });
  expect(varies.terms, 'the lowest is found wherever it is in the order').toMatchObject({ priceMinMinor: 50000, priceMaxMinor: 450000, durationMinMinutes: 60, durationMaxMinutes: 60 });
  const r = PENDING_F05_S02.menuRange;
  for (const s of [same, varies]) {
    const m = menuServices(await cat.menuS02(salon)).find(x => x.id === s.id)!;
    expect([m[r.priceMin], m[r.priceMax], m[r.durationMin], m[r.durationMax]], `${s.name}: the menu agrees`).toEqual(
      [s.terms.priceMinMinor, s.terms.priceMaxMinor, s.terms.durationMinMinutes, s.terms.durationMaxMinutes]);
    expect(menuField(m as never, 'price'), `${s.name}: the menu's price is the lowest`).toBe(s.terms.priceMinMinor);
  }
});

// ---------------------------------------------------------------- AC-5, AC-6 Popular

test('TC-F05-S02-007 AC-5, AC-6: up to six services are chosen without a terms version, a seventh is 409 limit_reached, a stale version is 409 conflict, and the menu lists the chosen online services in category then name order', async ({ identity, operator }) => {
  const cat = new CatalogueS02(identity);
  const { salon, owner, created } = await newSalon(operator, identity);
  const made = [
    await plain(cat, salon, owner.tokens, 'Nails', { name: 'Gel polish' }),
    await plain(cat, salon, owner.tokens, 'Cuts & styling', { name: 'Kids cut' }),
    await plain(cat, salon, owner.tokens, 'Cuts & styling', { name: 'Blow-dry' }),
    await plain(cat, salon, owner.tokens, 'Colour', { name: 'Root touch-up' }),
    await plain(cat, salon, owner.tokens, 'Waxing', { name: 'Brow wax', bookableOnline: false }),
    await withVariants(cat, salon, owner.tokens, 'Colour', undefined, { name: 'Balayage' }),
    await plain(cat, salon, owner.tokens, 'Massage & spa', { name: 'Hot stone' }),
  ];
  const versions = termsRows(created.id);
  const chosen: ServiceS02[] = [];
  for (const s of made.slice(0, 6)) {
    const c = await cat.choose(salon, owner.tokens, s);
    expect(c.popular, `${s.name} chosen`).toBe(true);
    expect(c.terms.versionId, `${s.name}: no terms version`).toBe(s.terms.versionId);
    chosen.push(c);
  }
  expect(termsRows(created.id), 'choosing makes no terms version').toBe(versions);
  const before = catalogueSnapshot(created.id);
  await refused(await cat.popularRaw(salon, owner.tokens, made[6].id, true, made[6].version), 409, 'limit_reached', 'a seventh');
  await refused(await cat.popularRaw(salon, owner.tokens, chosen[0].id, false, made[0].version), 409, 'conflict', 'a stale version');
  expect(catalogueSnapshot(created.id), 'nothing changed').toBe(before);
  const setting = await cat.setting(salon, owner.tokens);
  expect(setting.mode, 'a salon starts with its chosen services').toBe(PENDING_F05_S02.defaultMode);
  const byName = (n: string) => made.find(s => s.name === n)!.id;
  const expected = ['Blow-dry', 'Kids cut', 'Balayage', 'Root touch-up', 'Gel polish'].map(byName); // Cuts & styling, Colour, Nails; Brow wax is hidden
  expect((await cat.menuS02(salon)).popular, 'chosen and online, in category order then name order').toEqual(expected);
  await test.step('unchoosing frees a place', async () => {
    const off = await cat.choose(salon, owner.tokens, chosen[0], false);
    expect(off.popular).toBe(false);
    expect((await cat.choose(salon, owner.tokens, made[6])).popular).toBe(true);
  });
});

test('TC-F05-S02-008 AC-5: with five chosen, two services chosen at once as the sixth leave exactly six chosen: one succeeds and the other is 409 limit_reached', async ({ identity, operator }) => {
  const cat = new CatalogueS02(identity);
  const { salon, owner } = await newSalon(operator, identity);
  const made: ServiceS02[] = [];
  for (let n = 1; n <= 7; n++) made.push(await plain(cat, salon, owner.tokens, 'Nails', { name: `Service ${n}` }));
  for (const s of made.slice(0, 5)) await cat.choose(salon, owner.tokens, s);
  const [a, b] = await Promise.all([made[5], made[6]].map(s => cat.popularRaw(salon, owner.tokens, s.id, true, s.version)));
  const statuses = [a.status(), b.status()].sort();
  expect(statuses, 'one wins').toEqual([200, 409]);
  await refused([a, b].find(r => r.status() === 409)!, 409, 'limit_reached', 'the other');
  expect((await cat.services(salon, owner.tokens)).filter(s => (s as unknown as ServiceS02).popular), 'exactly six chosen').toHaveLength(6);
});

test('TC-F05-S02-009 AC-6: the Popular setting switches between chosen and most booked with its version; with no booking counts, most booked shows the chosen services; a stale version is 409 conflict', async ({ identity, operator }) => {
  const cat = new CatalogueS02(identity);
  const { salon, owner, created } = await newSalon(operator, identity);
  const a = await cat.choose(salon, owner.tokens, await plain(cat, salon, owner.tokens, 'Nails', { name: 'Gel polish' }));
  const b = await cat.choose(salon, owner.tokens, await plain(cat, salon, owner.tokens, 'Colour', { name: 'Gloss' }));
  await plain(cat, salon, owner.tokens, 'Colour', { name: 'Highlights' });
  const start = await cat.setting(salon, owner.tokens);
  expect(start).toMatchObject({ mode: 'chosen', popular: [b.id, a.id] });
  const most = await cat.setMode(salon, owner.tokens, 'most_booked', start.version);
  expect(most.mode).toBe('most_booked');
  expect(most.version).not.toBe(start.version);
  expect(most.popular, 'no counts until F07: the chosen services').toEqual([b.id, a.id]);
  expect((await cat.menuS02(salon)).popular, 'the menu says the same').toEqual([b.id, a.id]);
  const before = catalogueSnapshot(created.id);
  await refused(await cat.settingRaw(salon, owner.tokens, 'PUT', { mode: 'chosen', version: start.version }), 409, 'conflict', 'stale setting');
  await fieldRefusal(await cat.settingRaw(salon, owner.tokens, 'PUT', { mode: 'random', version: most.version }), 'mode', 'unknown mode');
  expect(catalogueSnapshot(created.id), 'nothing changed').toBe(before);
  expect((await cat.setMode(salon, owner.tokens, 'chosen', most.version)).mode).toBe('chosen');
});

// Case 015 (AC-6, the ranking by booking counts) is deferred to F07: stage.yaml verification.deferred.

// ---------------------------------------------------------------- AC-7, AC-8 deposit

test('TC-F05-S02-010 AC-7, AC-8: a service keeps the salon\'s rule, no deposit, a percentage of 1 to 100 or an amount of Rs. 100 to Rs. 100,000 up to its lowest price; anything else is a 400 on deposit; a deposit change makes a terms version and the menu carries it', async ({ identity, operator }) => {
  const cat = new CatalogueS02(identity);
  const { salon, owner, created } = await newSalon(operator, identity);
  const { id: categoryId } = await cat.category(salon, owner.tokens, 'Colour');
  const made = await cat.createS02(salon, owner.tokens, { ...serviceInput(categoryId), priceMinor: rupees(2500) });
  expect(depositOf(made.deposit), 'without one: the salon\'s rule').toEqual(PENDING_F05_S02.defaultDeposit);
  let s = made;
  const accepted = [{ kind: 'none' }, { kind: 'percentage', percent: 1 }, { kind: 'percentage', percent: 100 }, { kind: 'amount', amountMinor: rupees(100) },
    { kind: 'amount', amountMinor: rupees(2500) }, { kind: 'salon_rule' }];
  for (const deposit of accepted) {
    await test.step(`accepted: ${JSON.stringify(deposit)}`, async () => {
      const prior = s;
      s = await cat.saveS02(salon, owner.tokens, s, { deposit });
      expect(depositOf(s.deposit)).toEqual(deposit);
      expect(s.terms.number, 'a new terms version').toBe(prior.terms.number + 1);
      const m = menuServices(await cat.menuS02(salon)).find(x => x.id === s.id)!;
      expect(depositOf(m.deposit), 'the menu carries it').toEqual(deposit);
    });
  }
  const big = await cat.createS02(salon, owner.tokens, { ...serviceInput(categoryId), priceMinor: rupees(200_000) });
  await test.step('Rs. 100,000 on a service of Rs. 200,000', async () => {
    expect(depositOf((await cat.saveS02(salon, owner.tokens, big, { deposit: { kind: 'amount', amountMinor: rupees(100_000) } })).deposit)).toEqual({ kind: 'amount', amountMinor: rupees(100_000) });
  });
  const varied = await cat.createS02(salon, owner.tokens, variantInput(categoryId, undefined, { name: 'Balayage' }));
  const refusedRows: [string, ServiceS02, unknown][] = [
    ['0%', s, { kind: 'percentage', percent: 0 }], ['101%', s, { kind: 'percentage', percent: 101 }], ['20.5%', s, { kind: 'percentage', percent: 20.5 }],
    ['Rs. 99', s, { kind: 'amount', amountMinor: rupees(99) }], ['part of a rupee', s, { kind: 'amount', amountMinor: 15050 }],
    ['more than the price (Rs. 2,600 on Rs. 2,500)', s, { kind: 'amount', amountMinor: rupees(2600) }],
    ['Rs. 100,001', (await cat.serviceS02(salon, owner.tokens, big.id)), { kind: 'amount', amountMinor: rupees(100_001) }],
    ['more than the lowest variant (Rs. 2,600 on From Rs. 2,500)', varied, { kind: 'amount', amountMinor: rupees(2600) }],
    ['an unknown kind', s, { kind: 'flat' }], ['a percentage with no percent', s, { kind: 'percentage' }],
  ];
  const wrong: string[] = [];
  for (const [label, target, deposit] of refusedRows) {
    await test.step(`refused: ${label}`, async () => {
      const before = catalogueSnapshot(created.id);
      const r = await cat.updateRaw(salon, owner.tokens, target.id, saveOf(target, { deposit }));
      const body = await r.text();
      let fields: string[] = [];
      try { fields = ((JSON.parse(body) as { errors?: { field: string }[] }).errors ?? []).map(e => e.field); } catch { /* not problem details */ }
      if (r.status() !== 400 || fields.length === 0 || !fields.every(f => /^deposit(\.|$)/.test(f))) wrong.push(`${label}: ${r.status()} ${body.slice(0, 200)}`);
      if (catalogueSnapshot(created.id) !== before) wrong.push(`${label}: something was saved`);
    });
  }
  expect(wrong, 'every broken deposit rule is a 400 on deposit that saves nothing').toEqual([]);
  await test.step('lowering a price below a fixed deposit is refused', async () => {
    const fixed = await cat.saveS02(salon, owner.tokens, s, { deposit: { kind: 'amount', amountMinor: rupees(2000) } });
    await fieldRefusal(await cat.updateRaw(salon, owner.tokens, fixed.id, saveOf(fixed, { priceMinor: rupees(1500) })), 'deposit\\.amountMinor', 'price under the deposit (Corrections at close, 5)');
  });
});

test('TC-F05-S02-011 AC-9: Duplicate copies the variants with new ids and the deposit, and not the Popular choice', async ({ identity, operator }) => {
  const cat = new CatalogueS02(identity);
  const { salon, owner } = await newSalon(operator, identity);
  let s = await withVariants(cat, salon, owner.tokens, 'Cuts & styling', undefined, { name: 'Ladies cut', deposit: { kind: 'percentage', percent: 30 } });
  s = await cat.choose(salon, owner.tokens, s);
  const copy = (await cat.duplicate(salon, owner.tokens, s.id)) as unknown as ServiceS02;
  expect(copy.popular, 'not chosen').toBe(false);
  expect(depositOf(copy.deposit)).toEqual({ kind: 'percentage', percent: 30 });
  expect(copy.terms.variantSetName).toBe('Hair length');
  expect(copy.terms.variants.map(v => [v.name, v.priceMinor, v.durationMinutes, v.segments])).toEqual(s.terms.variants.map(v => [v.name, v.priceMinor, v.durationMinutes, v.segments]));
  const ids = new Set(s.terms.variants.map(v => v.id));
  expect(copy.terms.variants.filter(v => ids.has(v.id)), 'new variant ids').toEqual([]);
  expect(await cat.serviceS02(salon, owner.tokens, s.id), 'the original is untouched').toEqual(s);
});

// ---------------------------------------------------------------- AC-10 roles, isolation, audit

test('TC-F05-S02-012 AC-10: every role reads the Popular setting; only the Owner and Manager choose services, change the setting and save variants and deposits; other roles are 403 permission_denied, a support session 403 support_read_only and signed out 401, each changing nothing', async ({ identity, operator }) => {
  test.setTimeout(240_000);
  const cat = new CatalogueS02(identity);
  const mark = marker();
  const manager = await identity.staff(alpha, 'MANAGER');
  const owner = await identity.staff(alpha, 'OWNER');
  const startMode = (await cat.setting(alpha, manager.tokens)).mode;
  try {
    const service = await withVariants(cat, alpha, manager.tokens, 'Nails', undefined, { name: `QA S02 roles ${mark}` });
    const changes = (s: ServiceS02, version: number) => [
      { label: 'choose', method: 'PUT', path: popularPath(s.id), data: { popular: !s.popular, version: s.version } },
      { label: 'set most booked', method: 'PUT', path: POPULAR_SETTING, data: { mode: 'most_booked', version } },
      { label: 'save variants', method: 'PUT', path: servicePath(s.id), data: saveOf(s, { variants: { name: 'Length', items: s.terms.variants.map(v => ({ id: v.id, name: v.name, priceMinor: v.priceMinor, durationMinutes: v.durationMinutes })) } }) },
      { label: 'save a deposit', method: 'PUT', path: servicePath(s.id), data: saveOf(s, { deposit: { kind: 'none' } }) },
    ];
    for (const role of ROLES_ALL) {
      await test.step(role, async () => {
        const actor = role === 'MANAGER' ? manager : role === 'OWNER' ? owner : await identity.staff(alpha, role);
        expect((await cat.setting(alpha, actor.tokens)).mode, `${role} reads the setting`).toBeTruthy();
        const s = await cat.serviceS02(alpha, manager.tokens, service.id);
        const version = (await cat.setting(alpha, manager.tokens)).version;
        if (role === 'OWNER' || role === 'MANAGER') {
          let current = await cat.choose(alpha, actor.tokens, s, !s.popular);
          current = await cat.choose(alpha, actor.tokens, current, !current.popular);
          const flipped = await cat.setMode(alpha, actor.tokens, 'most_booked', version);
          await cat.setMode(alpha, actor.tokens, 'chosen', flipped.version);
          current = await cat.saveS02(alpha, actor.tokens, current, { deposit: { kind: 'none' } });
          await cat.saveS02(alpha, actor.tokens, current, { deposit: { kind: 'salon_rule' } });
          return;
        }
        for (const route of changes(s, version)) {
          const before = catalogueSnapshot(alpha.id);
          await refused(await cat.call(alpha, route.method, route.path, actor.tokens, route.data), 403, 'permission_denied', `${role} ${route.label}`);
          expect(catalogueSnapshot(alpha.id), `${role} ${route.label}: nothing changed`).toBe(before);
        }
      });
    }
    await test.step('signed out', async () => {
      await refused(await cat.settingRaw(alpha, undefined), 401, 'invalid_session', 'signed out read');
      const s = await cat.serviceS02(alpha, manager.tokens, service.id);
      for (const route of changes(s, (await cat.setting(alpha, manager.tokens)).version)) {
        await refused(await cat.call(alpha, route.method, route.path, undefined, route.data), 401, 'invalid_session', `signed out ${route.label}`);
      }
    });
    await test.step('a support session', async () => {
      const { created, salon, owner: salonOwner } = await newSalon(operator, identity);
      const s = await withVariants(cat, salon, salonOwner.tokens);
      const support = await operator.actor('OPERATOR_SUPPORT');
      const session = await supportJson<SupportStart>(await operator.startSupport(support, created.id, { reason: 'QA F05-S02 support reads Popular' }), 'supportStart', 201);
      const tokens: Tokens = await operator.supportCredential(await operator.exchange(session.entryCode, salon.hostname));
      expect(await cat.setting(salon, tokens)).toEqual(await cat.setting(salon, salonOwner.tokens));
      for (const route of changes(s, (await cat.setting(salon, salonOwner.tokens)).version)) {
        const before = catalogueSnapshot(created.id);
        await refused(await cat.call(salon, route.method, route.path, tokens, route.data), 403, 'support_read_only', `support ${route.label}`);
        expect(catalogueSnapshot(created.id)).toBe(before);
      }
    });
  } finally {
    const now = await cat.setting(alpha, manager.tokens).catch(() => null);
    if (now && now.mode !== startMode) await cat.setMode(alpha, manager.tokens, startMode, now.version).catch(() => undefined);
    for (const s of (await cat.services(alpha, manager.tokens)).filter(x => x.name.includes(mark))) {
      if ((s as unknown as ServiceS02).popular) await cat.choose(alpha, manager.tokens, s as unknown as ServiceS02, false).catch(() => undefined);
    }
    await cat.removeMarked(alpha, manager.tokens, mark);
    await cat.dispose();
  }
});

test('TC-F05-S02-013 AC-10: another salon\'s services cannot be chosen and answer as unknown ids, each salon\'s setting and Popular list are its own, and with no tenant context the new tables return no rows', async ({ identity, operator }) => {
  const cat = new CatalogueS02(identity);
  const a = await newSalon(operator, identity);
  const b = await newSalon(operator, identity);
  const sa = await cat.choose(a.salon, a.owner.tokens, await withVariants(cat, a.salon, a.owner.tokens));
  const sb = await cat.choose(b.salon, b.owner.tokens, await withVariants(cat, b.salon, b.owner.tokens));
  const bBefore = catalogueSnapshot(b.created.id);
  const theirs = await cat.popularRaw(a.salon, a.owner.tokens, sb.id, false, sb.version);
  const none = await cat.popularRaw(a.salon, a.owner.tokens, randomUUID(), false, sb.version);
  await refused(theirs, 404, 'not_found', 'choosing beta\'s service');
  expect(comparable(await theirs.text()), 'as an unknown id').toBe(comparable(await none.text()));
  await refused(await cat.call(a.salon, 'GET', POPULAR_SETTING, b.owner.tokens), 404, 'not_found', 'b\'s Owner reading a\'s setting');
  await cat.setMode(a.salon, a.owner.tokens, 'most_booked');
  expect((await cat.setting(b.salon, b.owner.tokens)).mode, 'b\'s setting is its own').toBe('chosen');
  expect((await cat.menuS02(a.salon)).popular).toEqual([sa.id]);
  expect((await cat.menuS02(b.salon)).popular).toEqual([sb.id]);
  expect(catalogueSnapshot(b.created.id), 'b changed in no way').toBe(bBefore);
  for (const table of [PENDING_F05_S02.variantTable, PENDING_F05_S02.popularTable]) {
    expect(catalogueTables(), `${table} is a catalogue table with tenant_id`).toContain(table);
    expect(Number(identitySql(`SELECT count(*) FROM ${table} WHERE tenant_id = ${sqlLiteral(a.created.id)}`)), `${table}: a has rows`).toBeGreaterThan(0);
    expect(identitySql(`SELECT count(*) FROM ${table}`, null), `${table}: no tenant, no rows`).toBe('0');
    expect(identitySql(`SELECT count(*) FROM ${table} WHERE tenant_id <> ${sqlLiteral(a.created.id)}`, a.created.id), `${table}: only its own`).toBe('0');
  }
});

test('TC-F05-S02-014 AC-10: a change of variants, of a deposit, of a Popular choice and of the Popular setting is audited with the actor and before and after values; a refused one is not', async ({ identity, operator }) => {
  const cat = new CatalogueS02(identity);
  const { salon, owner, created } = await newSalon(operator, identity);
  let s = await withVariants(cat, salon, owner.tokens);
  const t0 = await identity.now();
  let seen = catalogueAudit(created.id, t0).length;
  const audited = async (label: string, change: () => Promise<unknown>) => {
    await change();
    const rows = catalogueAudit(created.id, t0).slice(seen);
    seen += rows.length;
    expect(rows.length, `${label}: audited`).toBeGreaterThan(0);
    for (const row of rows) expect(row.actorId, `${label}: the actor`).toBe(owner.principal.id);
    return rows.map(r => r.details).join('\n');
  };
  const variants = await audited('a variant price', async () => {
    s = await cat.saveS02(salon, owner.tokens, s, { variants: { name: 'Hair length', items: s.terms.variants.map((v, k) => ({ id: v.id, name: v.name, priceMinor: k === 1 ? rupees(3300) : v.priceMinor, durationMinutes: v.durationMinutes })) } });
  });
  expect(variants, 'before').toContain('300000');
  expect(variants, 'after').toContain('330000');
  expect(variants, 'the new terms version').toContain(s.terms.versionId);
  const deposit = await audited('a deposit', async () => { s = await cat.saveS02(salon, owner.tokens, s, { deposit: { kind: 'percentage', percent: 35 } }); });
  expect(deposit).toContain('salon_rule');
  expect(deposit).toContain('35');
  expect(await audited('a Popular choice', async () => { s = await cat.choose(salon, owner.tokens, s); })).toMatch(/popular/i);
  const setting = await audited('the Popular setting', () => cat.setMode(salon, owner.tokens, 'most_booked'));
  expect(setting).toContain('chosen');
  expect(setting).toContain('most_booked');
  await refused(await cat.popularRaw(salon, owner.tokens, s.id, false, s.version - 1), 409, 'conflict', 'stale');
  expect(catalogueAudit(created.id, t0).length, 'the refusal is not audited').toBe(seen);
});
