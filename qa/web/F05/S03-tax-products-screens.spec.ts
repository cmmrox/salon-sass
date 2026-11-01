/**
 * F05-S03 in the browser: Settings → Tax & charges (its place, the prototype's words, field errors,
 * saving, the history, a conflict, a configured rate that changed), "What customers pay" compared
 * with an independent copy of the inclusive arithmetic, the Products tab and its sheet, the tax
 * banner, who sees what, the loading, unavailable and refused states, the leave guard, every width,
 * keyboard use and accessibility.
 *
 * Expected results are the F05-S03 spec's (acceptance criteria, "UI and UX") and the prototype
 * (`setTax`, `prodTab`, `pePaint`, `peErr`, `peSave`), written here rather than read from the
 * product's translation file. The amounts the preview shows are checked against `split` (the rule
 * of AC-4, written in qa/shared/catalogue-tax.ts), never against the product's own arithmetic.
 *
 * Prepared before the build. Test ids follow the F04 Settings tabs and the F05-S01 build's names and
 * are gathered in `T3`, to confirm against the F05-S03 handoff at the dry run.
 */
import type { Locator, Page } from '@playwright/test';

import { DESIGN_WIDTHS } from '../../shared/design-reference';
import { newSalon as newCatalogueSalon, serviceInput } from '../../shared/catalogue';
import {
  PENDING_F05_S03, TaxCatalogue, moneyText, productInput, split, taxInput, taxRow, type Product,
} from '../../shared/catalogue-tax';
import { test as browserTest, expect, type AccountBrowser } from '../../shared/identity-browser';
import type { Actor, Role } from '../../shared/identity';
import { Operator, supportJson, type SupportStart } from '../../shared/operator';
import { fit, focusIndicator, seriousViolations } from '../../shared/page-checks';
import { startApi, stopApi } from '../../shared/services';
import { webUrl } from '../../shared/env';
import { alpha, salonUrl } from '../../shared/tenants';

const test = browserTest.extend<{ operator: Operator; tax: TaxCatalogue }>({
  operator: async ({ identity }, use) => {
    const operator = new Operator(identity);
    await operator.resetFixtures();
    try { await use(operator); } finally { await operator.resetToBaseline(); }
  },
  tax: async ({ identity }, use) => { const tax = new TaxCatalogue(identity); try { await use(tax); } finally { await tax.dispose(); } },
});
test.describe.configure({ timeout: 180_000 });

const TAX_ROUTE = '/workspace/settings/tax';
const PRODUCTS_ROUTE = '/workspace/services/products';
const SERVICES_ROUTE = '/workspace/services';

/** Test ids, to confirm against the F05-S03 handoff (F04's `rules-*` and `settings-tab-*` patterns, F05-S01's `services-*`). */
const T3 = {
  settingsTab: (t: string) => `settings-tab-${t}`, tab: 'tax-tab', loading: 'tax-loading', intro: 'tax-intro', form: 'tax-form',
  vatOn: 'tax-vat-registered', vatNumber: 'tax-vat-number', vatNumberError: 'tax-vat-number-error',
  scOn: 'tax-service-charge', scRate: 'tax-service-charge-rate', scRateError: 'tax-service-charge-rate-error', scProducts: 'tax-service-charge-products',
  save: 'tax-save', history: 'tax-history', historySheet: 'tax-history-sheet', historyRow: 'tax-history-row',
  conflict: 'tax-conflict', conflictReload: 'tax-conflict-reload', rateChanged: 'tax-rate-changed', unavailable: 'tax-unavailable', retry: 'tax-retry',
  readOnly: 'tax-read-only', refused: 'tax-refused',
  leaveDialog: 'tax-leave-dialog', leaveStay: 'tax-leave-stay', leaveConfirm: 'tax-leave-confirm',
  previewService: 'tax-preview-service', previewProduct: 'tax-preview-product', previewPart: 'tax-preview-part', previewNote: 'tax-preview-note',
  servicesLoading: 'services-loading', tabProducts: 'services-tab-products', taxBanner: 'services-tax-banner',
  productsSummary: 'products-summary', productsBanner: 'products-banner', productsNew: 'products-new', productsEmpty: 'products-empty',
  productsTable: 'products-table', productRow: 'product-row', productCard: 'product-card',
  productName: 'product-name', productPrice: 'product-price', productTax: 'product-tax', productStatus: 'product-status', productEdit: 'product-edit',
  sheet: 'product-sheet', sheetName: 'product-sheet-name', sheetPrice: 'product-sheet-price', sheetVat: 'product-sheet-vat', sheetVatOff: 'product-sheet-vat-off',
  sheetSold: 'product-sheet-sold', sheetSave: 'product-sheet-save', sheetDelete: 'product-sheet-delete', sheetClose: 'product-sheet-close', sheetProblem: 'product-sheet-problem',
  deleteDialog: 'product-delete-dialog', deleteConfirm: 'product-delete-confirm',
  productLeaveDialog: 'products-leave-dialog', productLeaveStay: 'products-leave-stay',
} as const;

const TEXT = {
  intro: 'Turn these on only if your salon is registered for them. Names and rates come from the platform for your country. Changes apply to new bookings and sales only.',
  note: 'Prices are always shown with tax and service charge included',
  defaultService: 'A service at Rs. 2,500', defaultProduct: 'A product at Rs. 3,200',
  rateChanged: (rate: number) => `The VAT rate is now ${rate}%. Save to apply it to new bookings and sales.`,
  productsBanner: 'Products you sell at the front desk, added to a sale at checkout. Stock isn’t tracked.',
  summary: (n: number, m: number) => `${n} product${n === 1 ? '' : 's'} · ${m} sold at checkout`,
  empty: 'No products yet', emptyHint: 'Add what you sell at the desk, like shampoo or hair oil.',
  errName: 'Product name must be 2 to 40 characters.', errTaken: 'Another product already uses this name.', errPrice: 'Price must be Rs. 1 to Rs. 500,000.',
  deleteBody: 'It will be removed from checkout. Past sales keep it. This can’t be undone.',
  bannerBoth: 'Prices you enter include service charge (10%) and VAT (18%). Customers see the same price.',
  bannerOff: 'Tax and service charge are off. Turn them on in Settings if your salon is registered.',
};

const id = (page: Page | Locator, name: string) => page.getByTestId(name).filter({ visible: true });
const say = (t: string) => t.replace(/\s+/g, ' ').trim();

async function readyTax(page: Page) {
  await expect(id(page, T3.loading)).toHaveCount(0);
  await expect(id(page, T3.form)).toBeVisible();
}
async function readyProducts(page: Page) {
  await expect(id(page, T3.servicesLoading)).toHaveCount(0);
  await expect(id(page, T3.tabProducts)).toHaveAttribute('aria-selected', 'true');
}
async function open(accounts: AccountBrowser, actor: Actor, route: string, width = 1280) {
  const context = await accounts.context(actor.salon, [actor], { width });
  const page = await accounts.open(context, route);
  if (route === TAX_ROUTE) await readyTax(page); else if (route === PRODUCTS_ROUTE) await readyProducts(page);
  return { context, page };
}
const newSalon = (operator: Operator, accounts: AccountBrowser) => newCatalogueSalon(operator, accounts.identity);
async function settled(page: Page) { await page.waitForFunction(() => document.getAnimations().every(a => a.playState !== 'running')); }
async function accessible(page: Page, label: string) {
  await settled(page);
  expect.soft(await seriousViolations(page), `${label}: no serious or critical violation`).toEqual([]);
}
async function tabTo(page: Page, target: Locator, limit = 120) {
  let steps = 0;
  while (!(await target.evaluate(el => el === document.activeElement)) && steps++ < limit) await page.keyboard.press('Tab');
  await expect(target, 'reached by Tab').toBeFocused();
}
const productRow = (page: Page, p: Product, wide: boolean) => page.locator(`[data-testid="${wide ? T3.productRow : T3.productCard}"][data-id="${p.id}"]`);

/** The parts a preview block shows, as text, in order. */
const partsShown = async (block: Locator) => (await block.getByTestId(T3.previewPart).allInnerTexts()).map(say);
/** Asserts a preview block names its item and price and shows exactly the expected parts, each from `split`. */
async function expectPreview(block: Locator, label: string, priceText: string, parts: { name: string; minor: number }[]) {
  await expect(block, `${label}: the item and its price`).toContainText(priceText);
  await expect.poll(() => partsShown(block), `${label}: one line per part that applies`).toHaveLength(parts.length);
  const shown = await partsShown(block);
  parts.forEach((p, k) => {
    expect(shown[k], `${label}: ${p.name}`).toMatch(new RegExp(p.name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'i'));
    expect(shown[k], `${label}: ${p.name} is ${p.minor} minor units`).toMatch(moneyText(p.minor));
  });
}

// ---------------------------------------------------------------- AC-1, AC-2 the tab and its form

test('TC-F05-S03-016 AC-1, AC-2: the Owner finds Tax & charges between Booking rules and Branding with both off and the prototype\'s words; VAT needs its registration number and the service charge a rate of 1 to 20, each message beside its field; Save creates version 1', async ({ accounts, operator, tax }) => {
  const { salon, owner } = await newSalon(operator, accounts);
  const { page } = await open(accounts, owner, TAX_ROUTE);
  const tabs = await Promise.all(['rules', 'tax', 'branding'].map(t => id(page, T3.settingsTab(t)).boundingBox()));
  expect(tabs.every(Boolean), 'the three tabs').toBe(true);
  expect(tabs[0]!.x < tabs[1]!.x && tabs[1]!.x < tabs[2]!.x, 'Tax & charges between Booking rules and Branding').toBe(true);
  await expect(id(page, T3.intro)).toHaveText(TEXT.intro);
  await expect(id(page, T3.vatOn)).toHaveAttribute('data-state', 'unchecked');
  await expect(id(page, T3.scOn)).toHaveAttribute('data-state', 'unchecked');
  await expect(id(page, T3.vatOn)).toHaveAccessibleName(/My salon is registered for VAT/);
  await id(page, T3.vatOn).click();
  await expect(id(page, T3.vatNumber), 'the configured registration label').toHaveAccessibleName('VAT registration number');
  for (const bad of ['', 'AB 12', 'A'.repeat(21)]) {
    await id(page, T3.vatNumber).fill(bad);
    await id(page, T3.save).click();
    await expect(id(page, T3.vatNumberError), `"${bad}": beside the field`).toBeVisible();
  }
  await id(page, T3.vatNumber).fill('134-567-890-7000');
  await id(page, T3.scOn).click();
  for (const bad of ['0', '21']) {
    await id(page, T3.scRate).fill(bad);
    await id(page, T3.save).click();
    await expect(id(page, T3.scRateError), `rate ${bad}: beside the field`).toBeVisible();
  }
  expect((await tax.settings(salon, owner.tokens)).version, 'nothing saved while a rule is broken').toBe(0);
  await id(page, T3.scRate).fill('10');
  await expect(id(page, T3.scProducts), '"Also on products" is off by default').toHaveAttribute('data-state', 'unchecked');
  await id(page, T3.save).click();
  await expect.poll(async () => (await tax.settings(salon, owner.tokens)).version, { message: 'saved as version 1' }).toBe(1);
  expect((await tax.settings(salon, owner.tokens)).settings).toEqual(taxInput({ vat: '134-567-890-7000', sc: 10 }));
});

// ---------------------------------------------------------------- AC-4, AC-5 What customers pay

test('TC-F05-S03-017 AC-4, AC-5: "What customers pay" shows the prototype\'s examples while there are no services or products, then the salon\'s first online service and first product sold, each part as the rule gives it, following the form before saving, rounding half to even', async ({ accounts, operator, tax }) => {
  const { salon, owner } = await newSalon(operator, accounts);
  const { page } = await open(accounts, owner, TAX_ROUTE);
  const service = id(page, T3.previewService), product = id(page, T3.previewProduct);
  await expect(id(page, T3.previewNote)).toContainText(TEXT.note);
  await test.step('both off: no parts', async () => {
    await expectPreview(service, 'service', TEXT.defaultService, []);
    await expectPreview(product, 'product', TEXT.defaultProduct, []);
  });
  await test.step('VAT and a 10% service charge, before saving: the prototype\'s figures', async () => {
    await id(page, T3.vatOn).click();
    await id(page, T3.vatNumber).fill('LK-1');
    await id(page, T3.scOn).click();
    await id(page, T3.scRate).fill('10');
    const a = split(250000, 18, 10), b = split(320000, 18, null);
    expect([a.serviceCharge, a.vat, b.vat], 'the oracle agrees with the prototype').toEqual([19260, 38136, 48814]);
    await expectPreview(service, 'service', TEXT.defaultService, [{ name: 'Service charge 10%', minor: a.serviceCharge }, { name: 'VAT 18%', minor: a.vat }]);
    await expectPreview(product, 'product, VAT only', TEXT.defaultProduct, [{ name: 'VAT 18%', minor: b.vat }]);
    await id(page, T3.scProducts).click();
    const c = split(320000, 18, 10);
    await expectPreview(product, 'product, also on products', TEXT.defaultProduct, [{ name: 'Service charge 10%', minor: c.serviceCharge }, { name: 'VAT 18%', minor: c.vat }]);
    await id(page, T3.scProducts).click();
    await id(page, T3.save).click();
    await expect.poll(async () => (await tax.settings(salon, owner.tokens)).version).toBe(1);
  });
  await test.step('the salon\'s own first service and product, with a 20% service charge: a tie rounded half to even', async () => {
    const cuts = await tax.category(salon, owner.tokens, 'Cuts & styling');
    await tax.create(salon, owner.tokens, serviceInput(cuts.id, { name: 'Fringe trim', priceMinor: 10000, bookableOnline: true }));
    await tax.addProduct(salon, owner.tokens, productInput({ name: 'Hair oil', priceMinor: 100, soldAtCheckout: true }));
    await page.reload();
    await readyTax(page);
    await id(page, T3.scRate).fill('20');
    await id(page, T3.scProducts).click();
    const s = split(10000, 18, 20), p = split(100, 18, 20);
    expect(s, 'Rs. 100: VAT Rs. 15.25 and a service charge of Rs. 14.13 (half to even; half up would give Rs. 14.12)').toMatchObject({ vat: 1525, serviceCharge: 1413 });
    await expectPreview(service, 'Fringe trim', 'Fringe trim', [{ name: 'Service charge 20%', minor: s.serviceCharge }, { name: 'VAT 18%', minor: s.vat }]);
    await expect(service).toContainText('Rs. 100');
    await expectPreview(product, 'Hair oil', 'Hair oil', [{ name: 'Service charge 20%', minor: p.serviceCharge }, { name: 'VAT 18%', minor: p.vat }]);
  });
});

// ---------------------------------------------------------------- AC-3 history, conflict, a configured rate that changed

test('TC-F05-S03-018 AC-3: History lists the versions newest first with "You" and the changed fields; a save someone else overtook says so and Reload shows theirs', async ({ accounts, operator, tax }) => {
  const { salon, owner } = await newSalon(operator, accounts);
  await tax.save(salon, owner.tokens, taxInput({ vat: 'LK-1' }), 0);
  await tax.save(salon, owner.tokens, taxInput({ vat: 'LK-1', sc: 10 }), 1);
  const { page } = await open(accounts, owner, TAX_ROUTE);
  await id(page, T3.history).click();
  const rowsShown = id(page, T3.historySheet).getByTestId(T3.historyRow);
  await expect(rowsShown).toHaveCount(2);
  await expect(rowsShown.first(), 'the author as "You"').toContainText('You');
  await expect(rowsShown.first(), 'what it changed').toContainText(/service charge/i);
  await page.keyboard.press('Escape');
  await tax.save(salon, owner.tokens, taxInput({ vat: 'LK-1', sc: 12 }), 2);
  await id(page, T3.scRate).fill('15');
  await id(page, T3.save).click();
  await expect(page.getByRole('alert').filter({ hasText: /someone else/i }), 'the person is told').toBeVisible();
  expect((await tax.settings(salon, owner.tokens)).settings?.serviceCharge.percent, 'their rate stands').toBe(12);
  await id(page, T3.conflictReload).click();
  await expect(id(page, T3.scRate)).toHaveValue('12');
});

test('TC-F05-S03-019 AC-3: when the configured VAT rate changes, the tab says so; the next save carries the new rate and the earlier version keeps the old one', async ({ accounts, operator, tax }) => {
  test.setTimeout(600_000);
  const { salon, owner, created } = await newSalon(operator, accounts);
  await tax.save(salon, owner.tokens, taxInput({ vat: 'LK-1' }), 0);
  const v1 = (await tax.published(salon))!.versionId;
  const v1Row = taxRow(created.id, v1);
  const variable = PENDING_F05_S03.vatRateVariable;
  try {
    await stopApi();
    process.env[variable] = '15';
    await startApi();
    await accounts.identity.api.reconnect();
    const now = await accounts.identity.login(salon, owner.account);
    expect((await tax.settings(salon, now.tokens)).configured.vatRatePercent, 'the configuration says 15').toBe(15);
    expect((await tax.published(salon))!.vat!.ratePercent, 'the current version still says 18').toBe(18);
    const { page } = await open(accounts, now, TAX_ROUTE);
    await expect(id(page, T3.rateChanged)).toHaveText(TEXT.rateChanged(15));
    await id(page, T3.save).click();
    await expect.poll(async () => (await tax.published(salon))!.vat!.ratePercent, { message: 'the new version carries 15' }).toBe(15);
    expect(taxRow(created.id, v1), 'version 1 keeps 18').toBe(v1Row);
    await expect(id(page, T3.rateChanged)).toHaveCount(0);
  } finally {
    delete process.env[variable];
    await stopApi();
    await startApi();
    await accounts.identity.api.reconnect();
  }
});

// ---------------------------------------------------------------- AC-7, AC-8 Products

test('TC-F05-S03-020 AC-7, AC-8: the Products tab starts empty with the prototype\'s words; a product is added, shown as a table row from 900 px and a card below with its tax and status, edited and deleted after confirming; a broken field shows the prototype\'s message', async ({ accounts, operator, tax }) => {
  const { salon, owner } = await newSalon(operator, accounts);
  const { page } = await open(accounts, owner, PRODUCTS_ROUTE);
  await expect(id(page, T3.productsEmpty)).toContainText(TEXT.empty);
  await expect(id(page, T3.productsEmpty)).toContainText(TEXT.emptyHint);
  await expect(id(page, T3.productsSummary)).toHaveText(TEXT.summary(0, 0));
  await expect(id(page, T3.productsBanner)).toContainText(TEXT.productsBanner);
  await expect(id(page, T3.productsBanner), 'VAT is off').toContainText('VAT is off for your salon.');
  await id(page, T3.productsNew).click();
  await expect(id(page, T3.sheetVat), 'Charge VAT is not shown while VAT is off').toHaveCount(0);
  await expect(id(page, T3.sheetSold)).toHaveAttribute('data-state', 'checked');
  for (const [field, value, message] of [[T3.sheetName, 'A', TEXT.errName], [T3.sheetPrice, '0', TEXT.errPrice]] as const) {
    await id(page, T3.sheetName).fill('Hair oil');
    await id(page, T3.sheetPrice).fill('1500');
    await id(page, field).fill(value);
    await id(page, T3.sheetSave).click();
    await expect(page.getByTestId(T3.sheetProblem).filter({ hasText: message }), `${field} "${value}"`).toBeVisible();
  }
  await id(page, T3.sheetName).fill('Hair oil');
  await id(page, T3.sheetPrice).fill('1500');
  await id(page, T3.sheetSave).click();
  await expect(id(page, T3.sheet)).toHaveCount(0);
  const [oil] = await tax.products(salon, owner.tokens);
  expect(oil).toMatchObject({ name: 'Hair oil', priceMinor: 150000, chargesVat: true, soldAtCheckout: true });
  await tax.save(salon, owner.tokens, taxInput({ vat: 'LK-1' }), 0);
  const unsold = await tax.addProduct(salon, owner.tokens, productInput({ name: 'Argan shampoo', priceMinor: 320000, chargesVat: false, soldAtCheckout: false }));
  await page.reload();
  await readyProducts(page);
  await expect(id(page, T3.productsSummary)).toHaveText(TEXT.summary(2, 1));
  await expect(id(page, T3.productsTable), 'a table at 1280').toBeVisible();
  const row = productRow(page, oil, true);
  await expect(row.getByTestId(T3.productPrice)).toHaveText('Rs. 1,500');
  await expect(row.getByTestId(T3.productTax)).toHaveText('VAT 18%');
  await expect(row.getByTestId(T3.productStatus)).toHaveText('Sold');
  await expect(productRow(page, unsold, true).getByTestId(T3.productTax)).toHaveText('No VAT');
  await expect(productRow(page, unsold, true).getByTestId(T3.productStatus)).toHaveText('Not sold');
  await test.step('a duplicate name', async () => {
    await id(page, T3.productsNew).click();
    await id(page, T3.sheetName).fill('hair OIL');
    await id(page, T3.sheetPrice).fill('100');
    await id(page, T3.sheetSave).click();
    await expect(page.getByTestId(T3.sheetProblem).filter({ hasText: TEXT.errTaken })).toBeVisible();
    await id(page, T3.sheetClose).click();
    const leave = page.getByTestId('products-leave-confirm');
    if (await leave.isVisible()) await leave.click();
  });
  await test.step('cards below 900 px, with "—" while VAT is off', async () => {
    await tax.save(salon, owner.tokens, taxInput(), 1);
    await page.setViewportSize({ width: 768, height: 1000 });
    await page.reload();
    await readyProducts(page);
    await expect(id(page, T3.productsTable)).toHaveCount(0);
    await expect(productRow(page, oil, false).getByTestId(T3.productTax)).toHaveText('—');
    await page.setViewportSize({ width: 1280, height: 1000 });
    await page.reload();
    await readyProducts(page);
  });
  await test.step('edit and delete', async () => {
    await productRow(page, oil, true).getByTestId(T3.productEdit).click();
    await id(page, T3.sheetPrice).fill('1750');
    await id(page, T3.sheetSave).click();
    await expect(id(page, T3.sheet)).toHaveCount(0);
    expect((await tax.products(salon, owner.tokens)).find(p => p.id === oil.id)!.priceMinor).toBe(175000);
    await productRow(page, oil, true).getByTestId(T3.productEdit).click();
    await id(page, T3.sheetDelete).click();
    await expect(id(page, T3.deleteDialog)).toContainText(TEXT.deleteBody);
    await id(page, T3.deleteConfirm).click();
    await expect(productRow(page, oil, true)).toHaveCount(0);
    expect((await tax.products(salon, owner.tokens)).map(p => p.id)).toEqual([unsold.id]);
  });
});

// ---------------------------------------------------------------- AC-10 the tax banner

test('TC-F05-S03-021 AC-10: the Services and Products tabs say which taxes prices include, only the parts that are on, or that both are off', async ({ accounts, operator, tax }) => {
  const { salon, owner } = await newSalon(operator, accounts);
  const { page } = await open(accounts, owner, SERVICES_ROUTE);
  const banner = async (route: string) => {
    await page.goto(route);
    await expect(id(page, T3.servicesLoading)).toHaveCount(0);
    return say(await id(page, T3.taxBanner).innerText());
  };
  for (const route of [SERVICES_ROUTE, PRODUCTS_ROUTE]) expect(await banner(route), `${route}: off`).toBe(TEXT.bannerOff);
  await tax.save(salon, owner.tokens, taxInput({ vat: 'LK-1', sc: 10 }), 0);
  for (const route of [SERVICES_ROUTE, PRODUCTS_ROUTE]) expect(await banner(route), `${route}: both`).toBe(TEXT.bannerBoth);
  await tax.save(salon, owner.tokens, taxInput({ vat: 'LK-1' }), 1);
  for (const route of [SERVICES_ROUTE, PRODUCTS_ROUTE]) {
    const text = await banner(route);
    expect(text, `${route}: VAT only`).toContain('VAT (18%)');
    expect(text, `${route}: no service charge`).not.toMatch(/service charge/i);
  }
});

// ---------------------------------------------------------------- AC-11 roles

test('TC-F05-S03-022 AC-11: the Receptionist, Professional and Accountant see no Tax & charges tab and are told "no access", and read the Products tab with no control to change it; a support session sees both read-only', async ({ accounts, operator, tax, browser }) => {
  const manager = await accounts.identity.staff(alpha, 'MANAGER');
  const p = await tax.addProduct(alpha, manager.tokens, productInput());
  try {
    for (const role of ['RECEPTIONIST', 'PROFESSIONAL', 'ACCOUNTANT'] as Role[]) {
      await test.step(role, async () => {
        const actor = await accounts.identity.staff(alpha, role);
        const context = await accounts.context(alpha, [actor]);
        const page = await accounts.open(context, TAX_ROUTE);
        await expect(id(page, 'account-denied'), `${role}: no access by address`).toBeVisible();
        await expect(page.getByTestId(T3.settingsTab('tax'))).toHaveCount(0);
        await page.goto(PRODUCTS_ROUTE);
        await readyProducts(page);
        await expect(page.getByText(p.name), `${role}: reads products`).toBeVisible();
        for (const control of [T3.productsNew, T3.productEdit]) await expect(page.getByTestId(control), `${role}: no ${control}`).toHaveCount(0);
        await context.close();
      });
    }
  } finally {
    await tax.deleteProductRaw(alpha, manager.tokens, p.id, p.version);
  }
  await test.step('a support session', async () => {
    const { created, salon, owner } = await newSalon(operator, accounts);
    await tax.save(salon, owner.tokens, taxInput({ sc: 10 }), 0);
    await tax.addProduct(salon, owner.tokens, productInput({ name: 'Hair oil' }));
    const support = await operator.actor('OPERATOR_SUPPORT');
    const session = await supportJson<SupportStart>(await operator.startSupport(support, created.id, { reason: 'QA F05-S03 support reads tax and products' }), 'supportStart', 201);
    const context = await browser.newContext({ baseURL: salonUrl(salon, webUrl), viewport: { width: 1280, height: 1000 }, locale: 'en' });
    try {
      const entry = await context.newPage();
      await entry.goto(`/workspace/support#${session.entryCode}`);
      await expect(entry.getByTestId('support-banner')).toBeVisible();
      const page = await context.newPage();
      await page.goto(TAX_ROUTE);
      await readyTax(page);
      await expect(id(page, T3.readOnly)).toBeVisible();
      for (const control of [T3.vatOn, T3.scOn, T3.scRate, T3.save]) {
        if (await id(page, control).count()) await expect(id(page, control), `${control} disabled`).toBeDisabled();
      }
      await page.goto(PRODUCTS_ROUTE);
      await readyProducts(page);
      await expect(page.getByText('Hair oil')).toBeVisible();
      await expect(page.getByTestId(T3.productsNew)).toHaveCount(0);
    } finally {
      await context.close();
    }
  });
});

// ---------------------------------------------------------------- AC-13 states, leave guard, widths, keyboard and accessibility

test('TC-F05-S03-023 AC-13: the tab and the Products tab show their loading state and say an unreachable service plainly, recovering on Try again; a refused save says so; leaving the tab or the product sheet with unsaved edits asks first', async ({ accounts, operator, tax }) => {
  const { salon, owner } = await newSalon(operator, accounts);
  const context = await accounts.context(salon, [owner]);
  const data = (url: URL) => url.pathname.startsWith('/identity-web/') && /tax|product/.test(url.pathname);
  for (const [route, loading, unavailable, retry, ready] of [
    [TAX_ROUTE, T3.loading, T3.unavailable, T3.retry, readyTax], [PRODUCTS_ROUTE, T3.servicesLoading, 'services-unavailable', 'services-retry', readyProducts],
  ] as const) {
    await test.step(`${route}: loading, unreachable, Try again`, async () => {
      let release!: () => void;
      const held = new Promise<void>(r => { release = r; });
      await context.route(data, async r => { await held; await r.continue(); });
      const page = await context.newPage();
      void page.goto(route);
      await expect(id(page, loading)).toBeVisible();
      release();
      await ready(page);
      await context.unroute(data);
      await context.route(data, r => r.abort('connectionfailed'));
      await page.reload();
      await expect(id(page, unavailable)).toBeVisible();
      await context.unroute(data);
      await id(page, retry).click();
      await ready(page);
      await page.close();
    });
  }
  await test.step('leaving the tab with unsaved edits', async () => {
    const page = await accounts.open(context, TAX_ROUTE);
    await readyTax(page);
    await id(page, T3.scOn).click();
    await id(page, T3.settingsTab('branding')).click();
    await expect(id(page, T3.leaveDialog)).toBeVisible();
    await id(page, T3.leaveStay).click();
    await expect(id(page, T3.scOn)).toHaveAttribute('data-state', 'checked');
    await id(page, T3.settingsTab('branding')).click();
    await id(page, T3.leaveConfirm).click();
    await expect(page).toHaveURL(/\/workspace\/settings\/branding$/);
    expect((await tax.settings(salon, owner.tokens)).version, 'leaving saved nothing').toBe(0);
    await page.close();
  });
  await test.step('leaving the product sheet with unsaved edits', async () => {
    const page = await accounts.open(context, PRODUCTS_ROUTE);
    await readyProducts(page);
    await id(page, T3.productsNew).click();
    await id(page, T3.sheetName).fill('Hair oil');
    await page.keyboard.press('Escape');
    await expect(id(page, T3.productLeaveDialog)).toBeVisible();
    await id(page, T3.productLeaveStay).click();
    await expect(id(page, T3.sheetName)).toHaveValue('Hair oil');
    await page.close();
  });
  await test.step('a refused save: the Manager is made a Receptionist while the tab is open', async () => {
    const identity = accounts.identity;
    const ownerA = await identity.staff(alpha, 'OWNER');
    const manager = await identity.staff(alpha, 'MANAGER');
    const { page } = await open(accounts, manager, TAX_ROUTE);
    const before = await tax.settings(alpha, ownerA.tokens);
    const record = (await identity.list(ownerA)).find(x => x.id === manager.principal.id)!;
    expect((await identity.call(alpha, 'PATCH', `/staff/${manager.principal.id}`, { version: record.version, role: 'RECEPTIONIST' }, ownerA.tokens)).status()).toBe(200);
    await id(page, T3.scOn).click();
    if (await id(page, T3.scRate).count()) await id(page, T3.scRate).fill('7');
    await id(page, T3.save).click();
    await expect(page.getByRole('alert').filter({ hasText: /can’t|cannot|not allowed|no access/i }), 'the person is told the change is not allowed').toBeVisible();
    expect((await tax.settings(alpha, ownerA.tokens)).version, 'nothing saved').toBe(before.version);
  });
});

test('TC-F05-S03-024 AC-13: at every width the tab, the Products tab and the product sheet fit with no sideways scrolling; they have no serious accessibility violation; the settings are saved and a product added by keyboard alone', async ({ accounts, operator, tax }) => {
  test.setTimeout(300_000);
  const { salon, owner } = await newSalon(operator, accounts);
  for (let n = 1; n <= 12; n++) await tax.addProduct(salon, owner.tokens, productInput({ name: `Professional argan oil shampoo ${n}`.slice(0, 40), priceMinor: 50_000_000 - n * 100 }));
  await tax.save(salon, owner.tokens, taxInput({ vat: 'AB-1234-5678-90-XYZ1', sc: 20, onProducts: true }), 0);
  for (const { width, height } of DESIGN_WIDTHS) {
    await test.step(`${width} wide`, async () => {
      const { context, page } = await open(accounts, owner, TAX_ROUTE, width);
      await page.setViewportSize({ width, height });
      const check = async (label: string) => {
        await settled(page);
        const f = await fit(page);
        expect(f.pageScrolls, `${width} ${label}: no sideways scrolling`).toBe(false);
        expect(f.outside, `${width} ${label}: nothing outside the screen`).toEqual([]);
      };
      await check('Tax & charges');
      await id(page, T3.save).scrollIntoViewIfNeeded();
      await expect(id(page, T3.save)).toBeInViewport();
      await page.goto(PRODUCTS_ROUTE);
      await readyProducts(page);
      await check('Products');
      await id(page, T3.productsNew).click();
      await check('product sheet');
      await expect(id(page, T3.sheetSave)).toBeInViewport();
      await context.close();
    });
  }
  const { page } = await open(accounts, owner, TAX_ROUTE);
  await accessible(page, 'Tax & charges');
  await id(page, T3.history).click();
  await accessible(page, 'History');
  await page.keyboard.press('Escape');
  await test.step('a save by keyboard', async () => {
    await tabTo(page, id(page, T3.scProducts));
    const focus = await focusIndicator(id(page, T3.scProducts));
    expect(focus.focusVisible && focus.outlineWidth > 0, `a visible focus: ${JSON.stringify(focus)}`).toBe(true);
    await page.keyboard.press('Space');
    await tabTo(page, id(page, T3.save));
    await page.keyboard.press('Enter');
    await expect.poll(async () => (await tax.settings(salon, owner.tokens)).settings?.serviceCharge.onProducts).toBe(false);
  });
  await page.goto(PRODUCTS_ROUTE);
  await readyProducts(page);
  await accessible(page, 'Products');
  await test.step('a product by keyboard', async () => {
    await tabTo(page, id(page, T3.productsNew));
    await page.keyboard.press('Enter');
    await expect(id(page, T3.sheetName)).toBeVisible();
    await accessible(page, 'product sheet');
    await tabTo(page, id(page, T3.sheetName));
    await page.keyboard.type('Keyboard comb');
    await tabTo(page, id(page, T3.sheetPrice));
    await page.keyboard.type('450');
    await tabTo(page, id(page, T3.sheetSave));
    await page.keyboard.press('Enter');
    await expect(id(page, T3.sheet)).toHaveCount(0);
    expect((await tax.products(salon, owner.tokens)).some(p => p.name === 'Keyboard comb' && p.priceMinor === 45000)).toBe(true);
  });
});
