/**
 * F05's journeys across its stages, from the F05 README's "Feature-level test focus": a salon builds
 * its menu (categories in order, services with and without variants, a duplicate shown after
 * review, a hidden service, Popular, a deposit, tax) and the public menu and tax answer exactly what
 * customers may book and pay; an operator outside a support session reaches none of it; and a salon
 * in another currency has its menu, products and tax in that currency (NFR-13).
 *
 * Each journey asserts what passes between the stages, not each stage's rules again; those are the
 * stages' cases. Expected amounts come from `split`, the rule of F05-S03 AC-4 written in
 * qa/shared/catalogue-tax.ts, never from the product's arithmetic.
 */
import {
  CATEGORIES, CatalogueS02, POPULAR_SETTING, SERVICES, catalogueSnapshot, categoryPath, newSalon, rupees, serviceInput, servicePath,
  termsRow, variantInput, type MenuS02,
} from '../../shared/catalogue';
import { PRODUCTS, TAX_HISTORY, TAX_SETTINGS, TaxCatalogue, productInput, productPath, productSnapshot, split, taxInput, taxSnapshot } from '../../shared/catalogue-tax';
import type { Tokens } from '../../shared/identity';
import { test, expect, type OperatorActor } from '../../shared/operator';

test.describe.configure({ timeout: 180_000 });

test('TC-F05-001 CAT-01, CAT-02, CAT-03, CAT-04, CAT-05, CAT-06: an Owner builds a menu and the public menu returns exactly the services bookable online, in the salon\'s category order, with "From" prices, Popular and the tax customers pay included; a price change keeps the earlier terms', async ({ identity, operator }) => {
  const cat = new CatalogueS02(identity);
  const tax = new TaxCatalogue(identity);
  const { salon, owner, created } = await newSalon(operator, identity);
  const t = owner.tokens;
  await test.step('categories in the salon\'s order', async () => {
    const nails = await cat.category(salon, t, 'Nails');
    for (let n = 0; n < 3; n++) await cat.move(salon, t, nails.id, 'up'); // Nails first
  });
  const nails = await cat.category(salon, t, 'Nails');
  const colour = await cat.category(salon, t, 'Colour');
  const gel = await cat.createS02(salon, t, { ...serviceInput(nails.id), name: 'Gel polish', priceMinor: rupees(2500), durationMinutes: 45, bufferMinutes: 10 });
  const cut = await cat.createS02(salon, t, variantInput(colour.id, undefined, { name: 'Balayage', deposit: { kind: 'percentage', percent: 30 } }));
  const hidden = await cat.createS02(salon, t, { ...serviceInput(nails.id), name: 'Nail art', bookableOnline: false });
  const copy = await cat.duplicate(salon, t, gel.id);
  expect(copy.bookableOnline, 'the duplicate starts hidden').toBe(false);
  await cat.online(salon, t, await cat.update(salon, t, copy, { name: 'Gel polish deluxe', priceMinor: rupees(3200) }), true);
  await cat.choose(salon, t, cut);
  await tax.save(salon, t, taxInput({ vat: 'LK-2001', sc: 10 }), 0);

  const menu = await cat.menuS02(salon) as MenuS02 & { categories: { name: string; services: Record<string, unknown>[] }[] };
  const pub = (await tax.published(salon))!;
  await test.step('what the menu offers', async () => {
    expect(menu.categories.map(c => c.name), 'only categories with online services, Nails first').toEqual(['Nails', 'Colour']);
    expect(menu.categories[0].services.map(s => s.name), 'online services by name: the reviewed duplicate, not the hidden one').toEqual(['Gel polish', 'Gel polish deluxe']);
    expect(menu.categories.flatMap(c => c.services).map(s => s.id)).not.toContain(hidden.id);
    const balayage = menu.categories[1].services[0];
    expect([balayage.priceMinMinor, balayage.priceMaxMinor], 'a "From" price: the lowest and highest variant').toEqual([250000, 350000]);
    expect((balayage.variants as unknown[]).length).toBe(3);
    expect(menu.popular, 'Popular: the chosen online service').toEqual([cut.id]);
    expect(menu.currency).toBe('LKR');
  });
  await test.step('what customers pay: the menu\'s price, the parts adding up to it', async () => {
    expect([pub.vat?.ratePercent, pub.serviceCharge?.percent, pub.serviceCharge?.onProducts]).toEqual([18, 10, false]);
    for (const s of menu.categories.flatMap(c => c.services)) {
      const price = Number(s.priceMinor);
      const parts = split(price, pub.vat!.ratePercent, pub.serviceCharge!.percent);
      expect(parts.vat + parts.serviceCharge + parts.net, `${s.name}: to the cent`).toBe(price);
    }
    expect(split(Number(menu.categories[0].services[0].priceMinor), 18, 10), 'Gel polish at Rs. 2,500: the prototype\'s figures').toMatchObject({ serviceCharge: 19260, vat: 38136 });
  });
  await test.step('a price change: new bookings use the new terms, the earlier ones stay', async () => {
    const before = termsRow(created.id, gel.terms.versionId);
    const changed = await cat.update(salon, t, gel, { priceMinor: rupees(2750) });
    expect(changed.terms.versionId).not.toBe(gel.terms.versionId);
    expect(termsRow(created.id, gel.terms.versionId), 'version 1 is as it was').toBe(before);
    const again = (await cat.menuS02(salon)).categories[0].services.find(s => s.id === gel.id)!;
    expect([again.priceMinor, again.termsVersionId], 'the menu shows the new terms').toEqual([275000, changed.terms.versionId]);
  });
  await cat.dispose();
});

test('TC-F05-002 CAT-01, CAT-02, CAT-05, CAT-06, INV-01: an operator Admin or Support account outside a support session can neither read nor change a salon\'s catalogue, Popular setting, tax settings or products', async ({ identity, operator }) => {
  const cat = new CatalogueS02(identity);
  const tax = new TaxCatalogue(identity);
  const { salon, owner, created } = await newSalon(operator, identity);
  const t = owner.tokens;
  const nails = await cat.category(salon, t, 'Nails');
  const s = await cat.createS02(salon, t, { ...serviceInput(nails.id), name: 'Gel polish' });
  await tax.save(salon, t, taxInput({ vat: 'QA-SECRET-REG' }), 0);
  const p = await tax.addProduct(salon, t, productInput({ name: 'QA operator must not read this' }));
  const routes: { label: string; method: string; path: string; data?: unknown }[] = [
    { label: 'categories', method: 'GET', path: CATEGORIES },
    { label: 'add category', method: 'POST', path: CATEGORIES, data: { name: 'Operator category' } },
    { label: 'rename category', method: 'PUT', path: categoryPath(nails.id), data: { name: 'Renamed', version: nails.version } },
    { label: 'services', method: 'GET', path: SERVICES },
    { label: 'service', method: 'GET', path: servicePath(s.id) },
    { label: 'save service', method: 'PUT', path: servicePath(s.id), data: { ...serviceInput(nails.id), name: 'Renamed', version: s.version } },
    { label: 'delete service', method: 'DELETE', path: `${servicePath(s.id)}?version=${s.version}` },
    { label: 'Popular setting', method: 'GET', path: POPULAR_SETTING },
    { label: 'set Popular', method: 'PUT', path: POPULAR_SETTING, data: { mode: 'most_booked', version: 0 } },
    { label: 'tax settings', method: 'GET', path: TAX_SETTINGS },
    { label: 'tax history', method: 'GET', path: TAX_HISTORY },
    { label: 'save tax', method: 'PUT', path: TAX_SETTINGS, data: { version: 1, ...taxInput({ sc: 10 }) } },
    { label: 'products', method: 'GET', path: PRODUCTS },
    { label: 'add product', method: 'POST', path: PRODUCTS, data: productInput() },
    { label: 'delete product', method: 'DELETE', path: `${productPath(p.id)}?version=${p.version}` },
  ];
  const snapshot = () => [catalogueSnapshot(created.id), taxSnapshot(created.id), productSnapshot(created.id)].join('/');
  const before = snapshot();
  const actors: [string, OperatorActor][] = [['Admin', await operator.actor()], ['Support', await operator.actor('OPERATOR_SUPPORT')]];
  for (const [who, actor] of actors) {
    await test.step(`${who} outside a support session`, async () => {
      for (const route of routes) {
        const r = await cat.call(salon, route.method, route.path, actor.tokens as Tokens, route.data);
        const body = await r.text();
        expect([401, 403, 404], `${who} ${route.label} (${r.status()} ${body.slice(0, 160)})`).toContain(r.status());
        expect(JSON.parse(body).code, `${who} ${route.label}: a refusal`).toMatch(/^(invalid_session|permission_denied|not_found)$/);
        for (const secret of ['QA-SECRET-REG', 'QA operator must not read this', 'Gel polish']) expect(body, `${who} ${route.label}: nothing of the salon`).not.toContain(secret);
      }
    });
  }
  expect(snapshot(), 'nothing changed').toBe(before);
  await cat.dispose();
});

test('TC-F05-003 CAT-03, CAT-05, INV-01: a salon in another currency (NFR-13) has its variants, menu, products and tax in that currency, and the parts of each price add up to it', async ({ identity, operator }) => {
  const cat = new CatalogueS02(identity);
  const tax = new TaxCatalogue(identity);
  const { salon, owner } = await newSalon(operator, identity, { currency: 'USD' });
  const t = owner.tokens;
  const colour = await cat.category(salon, t, 'Colour');
  const s = await cat.createS02(salon, t, variantInput(colour.id, [
    { name: 'Short', priceMinor: 12000, durationMinutes: 60 }, { name: 'Long', priceMinor: 18500, durationMinutes: 90 },
  ], { name: 'Balayage' }));
  expect(s.terms.currency).toBe('USD');
  const p = await tax.addProduct(salon, t, productInput({ name: 'Argan oil', priceMinor: 2400 }));
  expect(p.currency, 'the product in the salon\'s currency').toBe('USD');
  await tax.save(salon, t, taxInput({ vat: 'US-1', sc: 15, onProducts: true }), 0);
  const menu = await cat.menuS02(salon);
  expect(menu.currency).toBe('USD');
  const m = menu.categories.flatMap(c => c.services).find(x => x.id === s.id)!;
  expect([m.priceMinMinor, m.priceMaxMinor, m.currency]).toEqual([12000, 18500, 'USD']);
  const pub = (await tax.published(salon))!;
  for (const price of [12000, 18500]) {
    const parts = split(price, pub.vat!.ratePercent, pub.serviceCharge!.percent);
    expect(parts.vat + parts.serviceCharge + parts.net, `${price}: to the cent`).toBe(price);
  }
  const productParts = split(p.priceMinor, pub.vat!.ratePercent, pub.serviceCharge!.onProducts ? pub.serviceCharge!.percent : null);
  expect(productParts.vat + productParts.serviceCharge + productParts.net).toBe(2400);
  await cat.dispose();
});
