/**
 * F05-S03 tax settings and products from outside: the public tax read (JSON null before the first
 * save, never the registration number), every field boundary, immutable versions carrying their own
 * name and rate, identical, stale and simultaneous saves, the history, products with their limits,
 * roles, a support session, suspended and read-only salons, tenant isolation and the audit trail.
 *
 * Expected results are the F05-S03 spec's (acceptance criteria, "API and data changes", "Design
 * notes"), decisions 085, 091, 092 and 095, and F04-S04's settings pattern. Prepared before the
 * build: names only the build gives are in `PENDING_F05_S03` (qa/shared/catalogue-tax.ts).
 *
 * The arithmetic of AC-4 is proved by the developer's unit test over its whole range; from outside,
 * the browser case of "What customers pay" compares what the product shows with an independent copy
 * of the rule (`split`), fed with the rates the public read gives. `TaxSettings` and `Products` (AC-6,
 * AC-9) are Java interfaces with no route: what they promise from outside (versions kept by id, a
 * deleted product gone, each salon's own) is proved here through the API and the database.
 */
import { randomUUID } from 'node:crypto';

import { readOnlySalon } from '../../shared/billing';
import { refused } from '../../shared/booking-rules';
import { comparable, fieldRefusal, newSalon } from '../../shared/catalogue';
import {
  CONFIGURED, PENDING_F05_S03, PRODUCTS, TAX, TAX_HISTORY, TAX_SETTINGS, TaxCatalogue, auditOf, distinctTax, productInput, productPath,
  productSnapshot, split, taxInput, taxRow, taxRows, taxSnapshot, type Product, type TaxInput,
} from '../../shared/catalogue-tax';
import { json, type Role, type Tokens } from '../../shared/identity';
import { identitySql } from '../../shared/identity-storage';
import { test, expect, alpha, operatorJson, sqlLiteral, supportJson, type SupportStart } from '../../shared/operator';

test.describe.configure({ timeout: 120_000 });

const ROLES_ALL: Role[] = ['OWNER', 'MANAGER', 'RECEPTIONIST', 'PROFESSIONAL', 'ACCOUNTANT'];
const chars = (n: number, letter = 'a') => `${letter.toUpperCase()}${letter.repeat(n - 1)}`;
const unknownHost = () => `qa-unknown-${randomUUID().slice(0, 8)}.localhost`;
/** What the save stores of an input, as the settings read returns it. */
const stored = (i: TaxInput) => structuredClone(i);

/** The settings routes with a body to send; the save is the one write. */
const settingsRoutes = (version: number) => [
  { label: 'settings', method: 'GET', path: TAX_SETTINGS, data: undefined as unknown },
  { label: 'history', method: 'GET', path: TAX_HISTORY, data: undefined as unknown },
  { label: 'save', method: 'PUT', path: TAX_SETTINGS, data: { version, ...distinctTax() } as unknown },
];
/** The product change routes for a product. */
const productRoutes = (p: Product) => [
  { label: 'add product', method: 'POST', path: PRODUCTS, data: productInput() as unknown },
  { label: 'edit product', method: 'PUT', path: productPath(p.id), data: { ...productInput({ name: p.name }), priceMinor: p.priceMinor + 100, version: p.version } as unknown },
  { label: 'delete product', method: 'DELETE', path: `${productPath(p.id)}?version=${p.version}`, data: undefined as unknown },
];

// ---------------------------------------------------------------- AC-1 to AC-3 tax settings

test('TC-F05-S03-001 AC-1, AC-6: before the first save the public tax is a JSON null with no sign-in, and the settings say version 0, nothing saved and the configured names and rates', async ({ identity, operator }) => {
  const tax = new TaxCatalogue(identity);
  const { salon, owner, created } = await newSalon(operator, identity);
  const pub = await tax.publicRaw(salon.hostname);
  expect(pub.status, 'no credential needed').toBe(200);
  expect(pub.text.trim(), 'the JSON literal null').toBe('null');
  expect(await tax.settings(salon, owner.tokens)).toEqual({ version: 0, settings: null, configured: CONFIGURED });
  expect((await tax.history(salon, owner.tokens)).total).toBe(0);
  expect(taxRows(created.id), 'nothing stored').toBe(0);
});

test('TC-F05-S03-002 AC-2, AC-6: VAT with registration numbers of 1 and 20 letters, digits and hyphens and a service charge of 1 and 20%, on products or not, are saved and read back; the public read carries the names and rates and never the registration number', async ({ identity, operator }) => {
  const tax = new TaxCatalogue(identity);
  const { salon, owner } = await newSalon(operator, identity);
  const rows: [string, TaxInput][] = [
    // A letter no UUID holds, so finding it anywhere in the public read means the number leaked.
    ['VAT with a 1-character number', taxInput({ vat: 'Q' })],
    ['VAT with a 20-character number', taxInput({ vat: 'AB-1234-5678-90-XYZ1' })],
    ['a service charge of 1%', taxInput({ sc: 1 })],
    ['a service charge of 20% on products too', taxInput({ sc: 20, onProducts: true })],
    ['both, the prototype\'s 10%', taxInput({ vat: '134-567-890-7000', sc: 10 })],
    ['both off again', taxInput()],
  ];
  for (const [label, input] of rows) {
    await test.step(label, async () => {
      const saved = await tax.save(salon, owner.tokens, input);
      expect(saved.settings, `${label}: saved as sent`).toEqual(stored(input));
      const pub = await tax.published(salon);
      expect(pub, `${label}: the public read`).not.toBeNull();
      expect(pub!.vat, `${label}: VAT`).toEqual(input.vat.registered ? { name: CONFIGURED.vatName, ratePercent: CONFIGURED.vatRatePercent } : null);
      expect(pub!.serviceCharge, `${label}: service charge`).toEqual(input.serviceCharge.on
        ? { name: CONFIGURED.serviceChargeName, percent: input.serviceCharge.percent, onProducts: input.serviceCharge.onProducts } : null);
      const text = (await tax.publicRaw(salon.hostname)).text;
      if (input.vat.registrationNumber) expect(text, `${label}: never the registration number`).not.toContain(input.vat.registrationNumber);
      expect(text).not.toMatch(/registration/i);
    });
  }
});

test('TC-F05-S03-003 AC-2: VAT without a number or with one of 21 characters or other characters, and a service charge of 0, 21, part of a percent or none, are a 400 on that field and save nothing', async ({ identity, operator }) => {
  const tax = new TaxCatalogue(identity);
  const { salon, owner, created } = await newSalon(operator, identity);
  const reg = (registrationNumber: unknown) => ({ vat: { registered: true, registrationNumber }, serviceCharge: { on: false, percent: null, onProducts: false } });
  const sc = (percent: unknown) => ({ vat: { registered: false, registrationNumber: null }, serviceCharge: { on: true, percent, onProducts: false } });
  const rows: [string, RegExp, unknown][] = [
    ['VAT with no number', /vat\.registrationNumber$/, reg(null)], ['VAT with an empty number', /vat\.registrationNumber$/, reg('')],
    ['a number of 21', /vat\.registrationNumber$/, reg('A'.repeat(21))], ['a space', /vat\.registrationNumber$/, reg('AB 12')],
    ['an underscore', /vat\.registrationNumber$/, reg('AB_12')], ['a letter with an accent', /vat\.registrationNumber$/, reg('ÄB12')],
    ['a number as a number', /vat\.registrationNumber$/, reg(12345)],
    ['a service charge of 0', /serviceCharge\.percent$/, sc(0)], ['a service charge of 21', /serviceCharge\.percent$/, sc(21)],
    ['10.5%', /serviceCharge\.percent$/, sc(10.5)], ['no rate', /serviceCharge\.percent$/, sc(null)], ['a rate as text', /serviceCharge\.percent$/, sc('10')],
  ];
  const wrong: string[] = [];
  for (const [label, field, input] of rows) {
    await test.step(label, async () => {
      const { version } = await tax.settings(salon, owner.tokens);
      const before = taxSnapshot(created.id);
      const r = await tax.saveRaw(salon, owner.tokens, version, input);
      const body = await r.text();
      let fields: string[] = [];
      try { fields = ((JSON.parse(body) as { errors?: { field: string }[] }).errors ?? []).map(e => e.field); } catch { /* not problem details */ }
      if (r.status() !== 400 || fields.length === 0 || !fields.every(f => field.test(f))) wrong.push(`${label}: ${r.status()} ${body.slice(0, 200)}`);
      if (taxSnapshot(created.id) !== before) wrong.push(`${label}: something was saved`);
    });
  }
  expect(wrong, 'every broken rule is a 400 on its field that saves nothing').toEqual([]);
});

test('TC-F05-S03-004 AC-3: each save makes a new current version carrying the configured name and rate, earlier versions stay as they were, an identical save is 409 invalid_state and a stale or future version is 409 conflict', async ({ identity, operator }) => {
  const tax = new TaxCatalogue(identity);
  const { salon, owner, created } = await newSalon(operator, identity);
  const first = taxInput({ vat: 'LK-1001', sc: 10 });
  const s1 = await tax.save(salon, owner.tokens, first, 0);
  expect(s1.version).toBe(1);
  const v1 = (await tax.published(salon))!.versionId;
  const v1Row = taxRow(created.id, v1);
  expect(v1Row, 'version 1 keeps the name and rate it was saved with').toContain('"VAT"');
  expect(v1Row).toContain('18');
  const second = taxInput({ vat: 'LK-1001', sc: 12, onProducts: true });
  const s2 = await tax.save(salon, owner.tokens, second, 1);
  expect(s2.version).toBe(2);
  const v2 = (await tax.published(salon))!.versionId;
  expect(v2, 'a new current version').not.toBe(v1);
  expect(taxRow(created.id, v1), 'version 1 is unchanged').toBe(v1Row);
  await test.step('identical, stale and future', async () => {
    const before = taxSnapshot(created.id);
    await refused(await tax.saveRaw(salon, owner.tokens, 2, second), 409, 'invalid_state', 'identical');
    await refused(await tax.saveRaw(salon, owner.tokens, 1, distinctTax()), 409, 'conflict', 'stale');
    await refused(await tax.saveRaw(salon, owner.tokens, 0, distinctTax()), 409, 'conflict', 'as if never saved');
    await refused(await tax.saveRaw(salon, owner.tokens, 9, distinctTax()), 409, 'conflict', 'from the future');
    expect(taxSnapshot(created.id), 'nothing changed').toBe(before);
    expect((await tax.settings(salon, owner.tokens)).settings).toEqual(stored(second));
  });
  await test.step('the application role can neither change nor delete a version', async () => {
    const t = PENDING_F05_S03.taxTable;
    expect(identitySql(`UPDATE ${t} SET tenant_id = tenant_id WHERE id = ${sqlLiteral(v1)}`, created.id, false)).toMatch(new RegExp(`ERROR:\\s+permission denied for table ${t}`));
    expect(identitySql(`DELETE FROM ${t} WHERE id = ${sqlLiteral(v1)}`, created.id, false)).toMatch(new RegExp(`ERROR:\\s+permission denied for table ${t}`));
    expect(taxRow(created.id, v1)).toBe(v1Row);
  });
});

test('TC-F05-S03-005 AC-3: of eight saves of one version sent at once exactly one succeeds, the rest are 409 conflict, and exactly one version is added', async ({ identity, operator }) => {
  const tax = new TaxCatalogue(identity);
  const { salon, owner, created } = await newSalon(operator, identity);
  await tax.save(salon, owner.tokens, taxInput({ sc: 5 }), 0);
  const rows = taxRows(created.id);
  const inputs = Array.from({ length: 8 }, (_, n) => taxInput({ sc: 10 + n }));
  const responses = await Promise.all(inputs.map(i => tax.saveRaw(salon, owner.tokens, 1, i)));
  const won = responses.map((r, n) => ({ r, n })).filter(x => x.r.status() === 200);
  expect(won.length, 'exactly one save').toBe(1);
  for (const r of responses.filter(x => x.status() !== 200)) await refused(r, 409, 'conflict', 'a losing save');
  expect(taxRows(created.id), 'exactly one version added').toBe(rows + 1);
  expect((await tax.settings(salon, owner.tokens)).settings).toEqual(stored(inputs[won[0].n]));
});

test('TC-F05-S03-006 AC-3: the history lists each version newest first with its time, author and the fields it changed, paged by page and size', async ({ identity, operator }) => {
  const tax = new TaxCatalogue(identity);
  const { salon, owner, input } = await newSalon(operator, identity);
  const t0 = await identity.now();
  await tax.save(salon, owner.tokens, taxInput({ vat: 'LK-1' }), 0);
  await tax.save(salon, owner.tokens, taxInput({ vat: 'LK-1', sc: 10 }));
  await tax.save(salon, owner.tokens, taxInput({ vat: 'LK-1', sc: 10, onProducts: true }));
  const t1 = await identity.now();
  const history = await tax.history(salon, owner.tokens);
  expect(history).toMatchObject({ page: 0, total: 3 });
  expect(history.items.map(i => i.version), 'newest first').toEqual([3, 2, 1]);
  for (const item of history.items) {
    expect(item.createdBy).toBe(owner.principal.id);
    expect(item.createdByLabel).toBe(input.owner.email);
    expect(Date.parse(item.createdAt)).toBeGreaterThanOrEqual(Date.parse(t0) - 1000);
    expect(Date.parse(item.createdAt)).toBeLessThanOrEqual(Date.parse(t1) + 1000);
  }
  expect(history.items[1].changedFields.join(' '), 'the second turned the service charge on').toMatch(/serviceCharge/);
  expect(history.items[0].changedFields.join(' '), 'the third changed products only').toMatch(/onProducts/);
  expect(history.items[0].changedFields.join(' ')).not.toMatch(/vat/i);
  const first = await tax.history(salon, owner.tokens, '?page=0&size=2');
  const second = await tax.history(salon, owner.tokens, '?page=1&size=2');
  expect(first.items.map(i => i.version)).toEqual([3, 2]);
  expect(second.items.map(i => i.version)).toEqual([1]);
  for (const q of ['?size=0', '?size=101', '?page=-1', '?size=x']) await refused(await tax.historyRaw(salon, owner.tokens, q), 400, 'bad_request', `history ${q}`);
});

test('TC-F05-S03-007 AC-4, AC-6: the public read gives each version\'s rates, and with them the rule gives the prototype\'s figures and parts that add up to every price of the salon\'s menu', async ({ identity, operator }) => {
  const tax = new TaxCatalogue(identity);
  const { salon, owner } = await newSalon(operator, identity);
  const { id: categoryId } = await tax.category(salon, owner.tokens, 'Colour');
  for (const price of [100, 2500, 3199, 4750, 500_000]) await tax.create(salon, owner.tokens, { name: `Rs. ${price}`, categoryId, priceMinor: price * 100, durationMinutes: 30, bufferMinutes: 0 });
  await tax.save(salon, owner.tokens, taxInput({ vat: 'LK-77', sc: 10 }), 0);
  const pub = (await tax.published(salon))!;
  const v = pub.vat!.ratePercent, s = pub.serviceCharge!.percent;
  expect([v, s]).toEqual([18, 10]);
  expect(split(250000, v, s), 'Rs. 2,500: service charge Rs. 192.60 and VAT Rs. 381.36').toMatchObject({ serviceCharge: 19260, vat: 38136 });
  expect(split(320000, v, null), 'Rs. 3,200 with VAT only: Rs. 488.14').toMatchObject({ vat: 48814, serviceCharge: 0 });
  for (const service of (await tax.menu(salon)).categories.flatMap(c => c.services)) {
    const price = Number(service.priceMinor);
    const parts = split(price, v, s);
    expect(parts.vat + parts.serviceCharge + parts.net, `${service.name}: the parts add up`).toBe(price);
  }
});

test('TC-F05-S03-008 AC-6, AC-12: each salon\'s public tax, settings and history are its own; another salon\'s Owner gets "not found"; an unknown host is "not found"; with no tenant context the new tables return no rows', async ({ identity, operator }) => {
  const tax = new TaxCatalogue(identity);
  const a = await newSalon(operator, identity);
  const b = await newSalon(operator, identity);
  await tax.save(a.salon, a.owner.tokens, taxInput({ vat: 'A-1' }), 0);
  await tax.save(b.salon, b.owner.tokens, taxInput({ sc: 15 }), 0);
  await tax.addProduct(a.salon, a.owner.tokens, productInput());
  await tax.addProduct(b.salon, b.owner.tokens, productInput());
  const pa = (await tax.published(a.salon))!, pb = (await tax.published(b.salon))!;
  expect([pa.vat?.ratePercent, pa.serviceCharge], 'a: VAT only').toEqual([18, null]);
  expect([pb.vat, pb.serviceCharge?.percent], 'b: service charge only').toEqual([null, 15]);
  expect(pa.versionId).not.toBe(pb.versionId);
  const before = taxSnapshot(a.created.id);
  for (const route of settingsRoutes(1)) await refused(await tax.call(a.salon, route.method, route.path, b.owner.tokens, route.data), 404, 'not_found', `b on a: ${route.label}`);
  expect(taxSnapshot(a.created.id), 'a unchanged').toBe(before);
  const unknown = await identity.api.getOn(unknownHost(), TAX);
  expect(unknown.status(), 'an unknown host').toBe(404);
  for (const table of [PENDING_F05_S03.taxTable, PENDING_F05_S03.productTable]) {
    expect(Number(identitySql(`SELECT count(*) FROM ${table} WHERE tenant_id = ${sqlLiteral(a.created.id)}`)), `${table}: a has rows`).toBeGreaterThan(0);
    expect(identitySql(`SELECT count(*) FROM ${table}`, null), `${table}: no tenant, no rows`).toBe('0');
    expect(identitySql(`SELECT count(*) FROM ${table} WHERE tenant_id <> ${sqlLiteral(a.created.id)}`, a.created.id), `${table}: only its own`).toBe('0');
  }
});

// ---------------------------------------------------------------- AC-8, AC-9 products

test('TC-F05-S03-009 AC-8, AC-9: an Owner adds, lists, edits and deletes products in minor units with the salon\'s currency; the VAT choice is kept while VAT is off; a deleted product leaves the list and frees its name; a stale version is 409 conflict', async ({ identity, operator }) => {
  const tax = new TaxCatalogue(identity);
  const { salon, owner, created } = await newSalon(operator, identity);
  const shampoo = await tax.addProduct(salon, owner.tokens, productInput({ name: 'Argan oil shampoo 250 ml', priceMinor: 320000, chargesVat: false, soldAtCheckout: true }));
  expect(shampoo).toMatchObject({ name: 'Argan oil shampoo 250 ml', priceMinor: 320000, currency: 'LKR', chargesVat: false, soldAtCheckout: true });
  const oil = await tax.addProduct(salon, owner.tokens, productInput({ name: 'Hair oil', priceMinor: 100, soldAtCheckout: false }));
  expect((await tax.products(salon, owner.tokens)).map(p => p.id).sort()).toEqual([shampoo.id, oil.id].sort());
  await test.step('the VAT choice survives VAT turning on and off', async () => {
    await tax.save(salon, owner.tokens, taxInput({ vat: 'LK-9' }), 0);
    await tax.save(salon, owner.tokens, taxInput());
    expect((await tax.products(salon, owner.tokens)).find(p => p.id === shampoo.id)!.chargesVat, 'still no VAT').toBe(false);
  });
  const edited = await tax.editProduct(salon, owner.tokens, shampoo, { priceMinor: 350000, chargesVat: true });
  expect(edited).toMatchObject({ priceMinor: 350000, chargesVat: true });
  expect(edited.version).not.toBe(shampoo.version);
  const before = productSnapshot(created.id);
  await refused(await tax.editProductRaw(salon, owner.tokens, shampoo.id, { ...productInput({ name: shampoo.name }), version: shampoo.version }), 409, 'conflict', 'stale edit');
  await refused(await tax.deleteProductRaw(salon, owner.tokens, shampoo.id, shampoo.version), 409, 'conflict', 'stale delete');
  expect(productSnapshot(created.id), 'nothing changed').toBe(before);
  const del = await tax.deleteProductRaw(salon, owner.tokens, edited.id, edited.version);
  expect(del.status()).toBe(204);
  expect((await tax.products(salon, owner.tokens)).map(p => p.id), 'gone from the list').toEqual([oil.id]);
  await refused(await tax.deleteProductRaw(salon, owner.tokens, edited.id, edited.version), 404, 'not_found', 'deleting it again');
  expect((await tax.addProduct(salon, owner.tokens, productInput({ name: 'ARGAN OIL SHAMPOO 250 ML' }))).name, 'its name is free').toBe('ARGAN OIL SHAMPOO 250 ML');
});

test('TC-F05-S03-010 AC-8: a product name of under 2 or over 40 characters or another product\'s in any case, a price under Rs. 1, over Rs. 500,000 or part of a rupee, or a setting that is not true or false is a 400 on that field and saves nothing', async ({ identity, operator }) => {
  const tax = new TaxCatalogue(identity);
  const { salon, owner, created } = await newSalon(operator, identity);
  const existing = await tax.addProduct(salon, owner.tokens, productInput({ name: 'Hair oil' }));
  const rows: [string, string, Record<string, unknown>][] = [
    ['no name', 'name', { name: undefined }], ['a name of 1', 'name', { name: 'A' }], ['a name of 41', 'name', { name: chars(41) }],
    ['another product\'s name in upper case', 'name', { name: 'HAIR OIL' }],
    ['Rs. 0', 'priceMinor', { priceMinor: 0 }], ['part of a rupee', 'priceMinor', { priceMinor: 150 }], ['Rs. 500,001', 'priceMinor', { priceMinor: 50_000_100 }],
    ['a negative price', 'priceMinor', { priceMinor: -100 }], ['a price as text', 'priceMinor', { priceMinor: '100' }],
    ['VAT as text', 'chargesVat', { chargesVat: 'yes' }], ['sold as a number', 'soldAtCheckout', { soldAtCheckout: 1 }],
  ];
  const wrong: string[] = [];
  for (const [label, field, change] of rows) {
    for (const action of ['add', 'edit'] as const) {
      if (action === 'edit' && label.startsWith('another')) continue; // its own name in another case is not another product's
      await test.step(`${action}: ${label}`, async () => {
        const before = productSnapshot(created.id);
        const body = JSON.parse(JSON.stringify({ ...productInput(), ...(action === 'edit' ? { name: existing.name, version: existing.version } : {}), ...change }));
        const r = action === 'add' ? await tax.addProductRaw(salon, owner.tokens, body) : await tax.editProductRaw(salon, owner.tokens, existing.id, body);
        const text = await r.text();
        let fields: string[] = [];
        try { fields = ((JSON.parse(text) as { errors?: { field: string }[] }).errors ?? []).map(e => e.field); } catch { /* not problem details */ }
        if (r.status() !== 400 || fields.length === 0 || !fields.every(f => new RegExp(`(^|\\.)${field}$`).test(f))) wrong.push(`${action} ${label}: ${r.status()} ${text.slice(0, 160)}`);
        if (productSnapshot(created.id) !== before) wrong.push(`${action} ${label}: something was saved`);
      });
    }
  }
  expect(wrong, 'every broken rule is a 400 on its field that saves nothing').toEqual([]);
  await test.step('the limits themselves are accepted', async () => {
    for (const [name, priceMinor] of [['Ab', 100], [chars(40, 'p'), 50_000_000]] as const) {
      expect((await tax.addProduct(salon, owner.tokens, productInput({ name, priceMinor }))).priceMinor).toBe(priceMinor);
    }
  });
});

test('TC-F05-S03-011 AC-8: a salon holds 300 products and a 301st is 409 limit_reached that saves nothing', async ({ identity, operator }) => {
  test.setTimeout(300_000);
  const tax = new TaxCatalogue(identity);
  const { salon, owner, created } = await newSalon(operator, identity);
  for (let n = 0; n < 300; n += 10) {
    await Promise.all(Array.from({ length: 10 }, (_, k) => tax.addProduct(salon, owner.tokens, productInput({ name: `Product ${n + k + 1}` }))));
  }
  expect(await tax.products(salon, owner.tokens)).toHaveLength(300);
  const before = productSnapshot(created.id);
  await refused(await tax.addProductRaw(salon, owner.tokens, productInput({ name: 'Product 301' })), 409, 'limit_reached', 'a 301st');
  expect(productSnapshot(created.id)).toBe(before);
});

// ---------------------------------------------------------------- AC-11 roles and salon states

test('TC-F05-S03-012 AC-11: the Owner and Manager read and save tax settings and change products; the other roles are 403 permission_denied on the settings and on product changes but read products; signed out is 401; everyone reads the public tax', async ({ identity }) => {
  test.setTimeout(240_000);
  const tax = new TaxCatalogue(identity);
  const manager = await identity.staff(alpha, 'MANAGER');
  const made: Product[] = [];
  try {
    const product = await tax.addProduct(alpha, manager.tokens, productInput());
    made.push(product);
    for (const role of ROLES_ALL) {
      await test.step(role, async () => {
        const actor = role === 'MANAGER' ? manager : await identity.staff(alpha, role);
        expect((await tax.publicRaw(alpha.hostname)).status, `${role}: the public tax`).toBe(200);
        expect((await tax.products(alpha, actor.tokens)).map(p => p.id), `${role} reads products`).toContain(product.id);
        if (role === 'OWNER' || role === 'MANAGER') {
          const view = await tax.settings(alpha, actor.tokens);
          await tax.history(alpha, actor.tokens);
          await tax.save(alpha, actor.tokens, distinctTax(), view.version);
          const p = await tax.addProduct(alpha, actor.tokens, productInput());
          made.push(await tax.editProduct(alpha, actor.tokens, p, { priceMinor: 400000 }));
          return;
        }
        const version = (await tax.settings(alpha, manager.tokens)).version;
        const current = (await tax.products(alpha, manager.tokens)).find(p => p.id === product.id)!;
        for (const route of [...settingsRoutes(version), ...productRoutes(current)]) {
          const before = [taxSnapshot(alpha.id), productSnapshot(alpha.id)].join();
          await refused(await tax.call(alpha, route.method, route.path, actor.tokens, route.data), 403, 'permission_denied', `${role} ${route.label}`);
          expect([taxSnapshot(alpha.id), productSnapshot(alpha.id)].join(), `${role} ${route.label}: nothing changed`).toBe(before);
        }
      });
    }
    await test.step('signed out', async () => {
      const current = (await tax.products(alpha, manager.tokens)).find(p => p.id === product.id)!;
      for (const route of [...settingsRoutes(1), { label: 'list products', method: 'GET', path: PRODUCTS, data: undefined as unknown }, ...productRoutes(current)]) {
        await refused(await tax.call(alpha, route.method, route.path, undefined, route.data), 401, 'invalid_session', `signed out ${route.label}`);
      }
      expect((await tax.publicRaw(alpha.hostname)).status).toBe(200);
    });
  } finally {
    for (const p of await tax.products(alpha, manager.tokens).catch(() => [] as Product[])) {
      if (p.name.startsWith('QA product')) await tax.deleteProductRaw(alpha, manager.tokens, p.id, p.version);
    }
  }
});

test('TC-F05-S03-013 AC-11: a support session reads the tax settings, history and products and every change is 403 support_read_only; a suspended salon is 403 salon_suspended everywhere; a read-only salon reads and refuses every change with salon_read_only', async ({ identity, operator, clock }) => {
  test.setTimeout(240_000);
  const tax = new TaxCatalogue(identity);
  await test.step('a support session', async () => {
    const { created, salon, owner } = await newSalon(operator, identity);
    await tax.save(salon, owner.tokens, taxInput({ vat: 'LK-5' }), 0);
    const product = await tax.addProduct(salon, owner.tokens, productInput());
    const support = await operator.actor('OPERATOR_SUPPORT');
    const session = await supportJson<SupportStart>(await operator.startSupport(support, created.id, { reason: 'QA F05-S03 support reads tax and products' }), 'supportStart', 201);
    const tokens: Tokens = await operator.supportCredential(await operator.exchange(session.entryCode, salon.hostname));
    expect(await tax.settings(salon, tokens)).toEqual(await tax.settings(salon, owner.tokens));
    expect(await tax.history(salon, tokens)).toEqual(await tax.history(salon, owner.tokens));
    expect(await tax.products(salon, tokens)).toEqual(await tax.products(salon, owner.tokens));
    for (const route of [settingsRoutes(1)[2], ...productRoutes(product)]) {
      const before = [taxSnapshot(created.id), productSnapshot(created.id)].join();
      await refused(await tax.call(salon, route.method, route.path, tokens, route.data), 403, 'support_read_only', `support ${route.label}`);
      expect([taxSnapshot(created.id), productSnapshot(created.id)].join()).toBe(before);
    }
  });
  await clock.set('2026-11-20T07:00:00Z');
  await test.step('suspended', async () => {
    const { admin, created, salon, owner } = await newSalon(operator, identity);
    await tax.save(salon, owner.tokens, taxInput({ sc: 10 }), 0);
    const product = await tax.addProduct(salon, owner.tokens, productInput());
    await operatorJson(await operator.suspend(admin, created.id, await operator.version(admin, created.id)), 'salon');
    for (const route of [{ label: 'public tax', method: 'GET', path: TAX, data: undefined as unknown }, ...settingsRoutes(1),
      { label: 'list products', method: 'GET', path: PRODUCTS, data: undefined as unknown }, ...productRoutes(product)]) {
      await refused(await tax.call(salon, route.method, route.path, owner.tokens, route.data), 403, 'salon_suspended', `suspended ${route.label}`);
    }
  });
  await test.step('read-only', async () => {
    const { admin, created, salon, owner } = await newSalon(operator, identity);
    await tax.save(salon, owner.tokens, taxInput({ sc: 10 }), 0);
    const product = await tax.addProduct(salon, owner.tokens, productInput());
    await readOnlySalon(operator, clock, admin, created.id);
    const now = await identity.login(salon, owner.account);
    expect((await tax.settings(salon, now.tokens)).version).toBe(1);
    expect((await tax.products(salon, now.tokens)).map(p => p.id)).toEqual([product.id]);
    expect((await tax.published(salon))?.serviceCharge?.percent, 'customers still read it').toBe(10);
    for (const route of [settingsRoutes(1)[2], ...productRoutes(product)]) {
      const before = [taxSnapshot(created.id), productSnapshot(created.id)].join();
      await refused(await tax.call(salon, route.method, route.path, now.tokens, route.data), 403, 'salon_read_only', `read-only ${route.label}`);
      expect([taxSnapshot(created.id), productSnapshot(created.id)].join()).toBe(before);
    }
  });
});

// ---------------------------------------------------------------- AC-12 isolation and audit

test('TC-F05-S03-014 AC-12: another salon\'s products answer every edit and delete exactly as unknown ids and never appear in the list', async ({ identity, operator }) => {
  const tax = new TaxCatalogue(identity);
  const a = await newSalon(operator, identity);
  const b = await newSalon(operator, identity);
  const theirs = await tax.addProduct(b.salon, b.owner.tokens, productInput({ name: 'Beta shampoo' }));
  expect((await tax.products(b.salon, b.owner.tokens)).map(p => p.id), 'b has it').toContain(theirs.id);
  const before = productSnapshot(b.created.id);
  for (const route of productRoutes(theirs).filter(r => r.label !== 'add product')) {
    const r = await tax.call(a.salon, route.method, route.path, a.owner.tokens, route.data);
    const none = await tax.call(a.salon, route.method, route.path.replace(theirs.id, randomUUID()), a.owner.tokens, route.data);
    await refused(r, 404, 'not_found', `a with b's id: ${route.label}`);
    expect(comparable(await r.text()), `${route.label}: as an unknown id`).toBe(comparable(await none.text()));
  }
  expect((await tax.products(a.salon, a.owner.tokens)).map(p => p.id)).not.toContain(theirs.id);
  for (const route of productRoutes(theirs)) await refused(await tax.call(a.salon, route.method, route.path, b.owner.tokens, route.data), 404, 'not_found', `b's Owner on a: ${route.label}`);
  expect(productSnapshot(b.created.id), 'b changed in no way').toBe(before);
});

test('TC-F05-S03-015 AC-12: every tax save and every product change is audited with the actor and before and after values; refused ones are not', async ({ identity, operator }) => {
  const tax = new TaxCatalogue(identity);
  const { salon, owner, created } = await newSalon(operator, identity);
  const t0 = await identity.now();
  const count = () => auditOf(created.id, PENDING_F05_S03.taxAuditPrefix, t0).length + auditOf(created.id, PENDING_F05_S03.productAuditPrefix, t0).length;
  let seen = 0;
  const audited = async (label: string, change: () => Promise<unknown>) => {
    await change();
    const rows = [...auditOf(created.id, PENDING_F05_S03.taxAuditPrefix, t0), ...auditOf(created.id, PENDING_F05_S03.productAuditPrefix, t0)]
      .sort((x, y) => Date.parse(x.at) - Date.parse(y.at)).slice(seen);
    seen += rows.length;
    expect(rows.length, `${label}: audited`).toBeGreaterThan(0);
    for (const row of rows) expect(row.actorId, `${label}: the actor`).toBe(owner.principal.id);
    return rows.map(r => r.details).join('\n');
  };
  await audited('the first save', () => tax.save(salon, owner.tokens, taxInput({ sc: 10 }), 0));
  const second = await audited('a second save', () => tax.save(salon, owner.tokens, taxInput({ sc: 12 }), 1));
  expect(second, 'before').toMatch(/\b10\b/);
  expect(second, 'after').toMatch(/\b12\b/);
  let p!: Product;
  expect(await audited('add a product', async () => { p = await tax.addProduct(salon, owner.tokens, productInput({ name: 'Hair oil', priceMinor: 150000 })); })).toContain('Hair oil');
  const edit = await audited('edit it', async () => { p = await tax.editProduct(salon, owner.tokens, p, { priceMinor: 175000 }); });
  expect(edit).toContain('150000');
  expect(edit).toContain('175000');
  await audited('delete it', () => tax.deleteProductRaw(salon, owner.tokens, p.id, p.version));
  await refused(await tax.saveRaw(salon, owner.tokens, 1, distinctTax()), 409, 'conflict', 'stale save');
  expect(count(), 'the refusal is not audited').toBe(seen);
});

// ---------------------------------------------------------------- after the PR #19 review

test('TC-F05-S03-025 AC-7: the products list gives the salon\'s currency also before its first product, in a salon of each currency', async ({ identity, operator }) => {
  const tax = new TaxCatalogue(identity);
  for (const currency of ['USD', 'LKR']) {
    await test.step(currency, async () => {
      const { salon, owner } = await newSalon(operator, identity, { currency });
      const list = await json<{ currency: string; items: Product[] }>(await tax.call(salon, 'GET', PRODUCTS, owner.tokens), PENDING_F05_S03.schemas.productList);
      expect(list, 'an empty list, with the currency').toEqual({ currency, items: [] });
      const p = await tax.addProduct(salon, owner.tokens, productInput());
      expect(p.currency).toBe(currency);
      const after = await json<{ currency: string; items: Product[] }>(await tax.call(salon, 'GET', PRODUCTS, owner.tokens), PENDING_F05_S03.schemas.productList);
      expect(after.currency).toBe(currency);
    });
  }
});
