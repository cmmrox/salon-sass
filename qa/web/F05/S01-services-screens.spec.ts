/**
 * F05-S01 the Services & pricing page in the browser: the Services tab's grouped cards, header and
 * banner, the service sheet with the prototype's messages, duplicate, hide and show, delete with its
 * confirmation, the Categories tab with its order buttons, category sheet and refusals, the photo
 * control, who sees which controls, a support session, the loading, unavailable, conflict and
 * refused states, the leave guard, every width of the matrix, keyboard use and accessibility.
 *
 * Expected results are the F05-S01 spec's (acceptance criteria, "UI and UX") and the prototype
 * (Salon web, Services: `aSvc`, `svTabs`, `catTab`, `ceOpen`, `catDel`, `sePaint`, `seErr`,
 * `seWarn`, `seSave`, `seDel`, `seDup`, `seToggle`), whose messages are written here from the spec
 * and the prototype rather than read from the product's translation file. Rules the API cases prove
 * (every boundary, every role on every endpoint) are not proved again: each case checks what the
 * person sees, and persistence once through the API.
 *
 * Prepared before the build. The test ids in `T` follow docs/design/ui-ux-standards.md ("Test ids")
 * and must be confirmed against the F05-S01 handoff at the dry run; nothing else here spells one.
 */
import type { BrowserContext, Locator, Page } from '@playwright/test';

import {
  Catalogue, DEFAULT_CATEGORIES, PENDING_F05_S01, newSalon as newCatalogueSalon, photoIdOf, rupees, serviceInput, type Service,
} from '../../shared/catalogue';
import { DESIGN_WIDTHS } from '../../shared/design-reference';
import { webUrl } from '../../shared/env';
import { test as browserTest, expect, type AccountBrowser } from '../../shared/identity-browser';
import type { Actor, Role } from '../../shared/identity';
import { Operator, supportJson, type SupportStart } from '../../shared/operator';
import { TaxCatalogue, taxInput } from '../../shared/catalogue-tax';
import { fit, focusIndicator, seriousViolations } from '../../shared/page-checks';
import { fixture } from '../../shared/salon-brand';
import { alpha, gamma, salonUrl, type Salon } from '../../shared/tenants';

const test = browserTest.extend<{ operator: Operator; cat: Catalogue }>({
  operator: async ({ identity }, use) => {
    const operator = new Operator(identity);
    await operator.resetFixtures();
    try { await use(operator); } finally { await operator.resetToBaseline(); }
  },
  cat: async ({ identity }, use) => { const cat = new Catalogue(identity); try { await use(cat); } finally { await cat.dispose(); } },
});
test.describe.configure({ timeout: 180_000 });

const SERVICES_ROUTE = '/workspace/services';
const CATEGORIES_ROUTE = '/workspace/services/categories';

/** Test ids, from the F05-S01 handoff ("Test ids for QA"). A repeated row carries `data-id`. */
const T = {
  nav: 'workspace-services', tabServices: 'services-tab-services', tabCategories: 'services-tab-categories', tabProducts: 'services-tab-products',
  loading: 'services-loading', unavailable: 'services-unavailable', retry: 'services-retry', refused: 'services-refused', readOnly: 'services-read-only',
  rule: 'services-rule', summary: 'services-summary', banner: 'services-banner', newService: 'services-new',
  group: 'service-group',
  // CR-025 (AC-18), from the build.
  folded: 'services-empty-categories', empty: 'services-empty', emptyNew: 'services-empty-new',
  card: 'service-card', cardName: 'service-card-name', cardBadge: 'service-card-status', cardMeta: 'service-card-detail', cardPrice: 'service-card-price',
  cardTime: 'service-card-time', cardBuffer: 'service-card-buffer', cardEdit: 'service-card-edit', cardOnline: 'service-card-online', cardDuplicate: 'service-card-duplicate',
  sheet: 'service-sheet', name: 'service-sheet-name', price: 'service-sheet-price', time: 'service-sheet-time', more: 'service-sheet-more',
  category: 'service-sheet-category', description: 'service-sheet-description', buffer: 'service-sheet-buffer', online: 'service-sheet-online',
  warning: 'service-sheet-hidden', save: 'service-sheet-save', del: 'service-sheet-delete', close: 'service-sheet-close',
  photo: 'service-photo-file', photoChoose: 'service-photo-choose', photoPreview: 'service-photo-preview', photoRemove: 'service-photo-remove',
  photoError: 'service-photo-error', photoAfterCreate: 'service-photo-after-create',
  conflict: 'services-conflict', conflictReload: 'services-conflict-reload',
  leaveDialog: 'services-leave-dialog', leaveStay: 'services-leave-stay', leaveConfirm: 'services-leave-confirm',
  deleteDialog: 'service-delete-dialog', deleteConfirm: 'service-delete-confirm', deleteCancel: 'service-delete-cancel',
  categoryRow: 'category-row', categoryRowName: 'category-row-name', categoryUp: 'category-row-up', categoryDown: 'category-row-down',
  categoryRename: 'category-row-rename', categoryDelete: 'category-row-delete', newCategory: 'categories-new', categorySheet: 'category-sheet',
  categoryName: 'category-sheet-name', categorySave: 'category-sheet-save', categoryError: 'category-sheet-error',
  categoryDeleteDialog: 'category-delete-dialog', categoryDeleteConfirm: 'category-delete-confirm',
} as const;

/** A message of the service sheet's problem list, by its key (`data-problem`). */
const problem = (page: Page, key: string) => page.locator(`[data-testid="service-sheet-problem"][data-problem="${key}"]`);

/** The spec's and the prototype's words. */
const TEXT = {
  banner: 'List your services with their price and time. Changes apply to new bookings only.',
  /** F05-S03's AC-10, which AC-17 puts in the same banner. */
  tax: {
    off: 'Tax and service charge are off. Turn them on in Settings if your salon is registered.',
    both: 'Prices you enter include service charge (10%) and VAT (18%). Customers see the same price.',
    vat: 'Prices you enter include VAT (18%). Customers see the same price.',
    serviceCharge: 'Prices you enter include service charge (10%). Customers see the same price.',
  },
  folded: (names: string[]) => `No services yet in: ${names.join(', ')}.`,
  summary: (n: number, m: number) => `${n} services · ${m} bookable online`,
  empty: 'No services yet',
  orderSaved: 'Order saved. Customers see it straight away.',
  moveFirst: (n: number) => `Move its ${n} service${n === 1 ? '' : 's'} to another category first.`,
  termsSaved: 'Saved. New bookings use the new prices.',
  duplicated: 'Duplicated and hidden online. Review it, then show it.',
  hidden: 'Hidden from online booking. Existing bookings are not affected.',
  shown: 'Now bookable online.',
  deleteTitle: (name: string) => `Delete “${name}”?`,
  deleteBody: 'It will be removed from your menu. Past bookings are kept. This can’t be undone.',
  hiddenWarning: 'This service is hidden from online booking. Turn it on when you’re ready.',
  refused: 'You can’t change services. Ask the salon owner.',
  photoAfterCreate: 'Add a photo after you create the service.',
  err: {
    name: 'Service name must be 2 to 40 characters.', nameTaken: 'Another service already uses this name.',
    description: 'Keep the description under 90 characters.', price: 'Price must be Rs. 100 to Rs. 500,000.',
    time: 'Time must be 5 to 480 minutes, in steps of 5.', buffer: 'Buffer must be 0 to 60 minutes, in steps of 5.',
    categoryName: 'Category name must be 2 to 30 characters.', categoryTaken: 'Another category already uses this name.',
  },
};

const id = (page: Page | Locator, name: string) => page.getByTestId(name).filter({ visible: true });
const card = (page: Page, serviceId: string) => page.locator(`[data-testid="${T.card}"][data-id="${serviceId}"]`);
const row = (page: Page, categoryId: string) => page.locator(`[data-testid="${T.categoryRow}"][data-id="${categoryId}"]`);

async function ready(page: Page) {
  await expect(id(page, T.loading)).toHaveCount(0);
  await expect(id(page, T.tabServices)).toBeVisible();
}

async function open(accounts: AccountBrowser, actor: Actor, route = SERVICES_ROUTE, width = 1280): Promise<{ context: BrowserContext; page: Page }> {
  const context = await accounts.context(actor.salon, [actor], { width });
  const page = await accounts.open(context, route);
  await ready(page);
  return { context, page };
}

/** A salon the operator creates now, with its Owner signed in. */
const newSalon = (operator: Operator, accounts: AccountBrowser) => newCatalogueSalon(operator, accounts.identity);

/** Chooses an option of a shadcn Select by the trigger's test id and the option's value (web.md: never `selectOption`). */
async function choose(page: Page, trigger: string, value: string) {
  await id(page, trigger).click();
  await id(page, `${trigger}-option-${value}`).click();
  await expect(page.getByTestId(`${trigger}-option-${value}`)).toHaveCount(0);
}

async function openMore(page: Page) {
  if (!(await id(page, T.buffer).count())) await id(page, T.more).click();
  await expect(id(page, T.buffer)).toBeVisible();
}

async function settled(page: Page) {
  await page.waitForFunction(() => document.getAnimations().every(a => a.playState !== 'running'));
}

async function accessible(page: Page, label: string) {
  await settled(page);
  expect.soft(await seriousViolations(page), `${label}: no serious or critical violation`).toEqual([]);
}

/** Tabs forward until the element has the focus, failing after `limit` presses. */
async function tabTo(page: Page, target: Locator, limit = 120) {
  let steps = 0;
  while (!(await target.evaluate(el => el === document.activeElement)) && steps++ < limit) await page.keyboard.press('Tab');
  await expect(target, 'reached by Tab').toBeFocused();
}

/** A small menu: Nails with an online service with a buffer and a hidden one, Colour with one described service. */
async function arrangeMenu(cat: Catalogue, salon: Salon, tokens: Actor['tokens']) {
  const nails = await cat.category(salon, tokens, 'Nails');
  const colour = await cat.category(salon, tokens, 'Colour');
  const gel = await cat.create(salon, tokens, serviceInput(nails.id, { name: 'Gel polish', description: 'Long-lasting shine.', priceMinor: rupees(2500), durationMinutes: 45, bufferMinutes: 10 }));
  const art = await cat.create(salon, tokens, serviceInput(nails.id, { name: 'Nail art', description: 'Per nail.', priceMinor: rupees(300), durationMinutes: 15, bufferMinutes: 0, bookableOnline: false }));
  const root = await cat.create(salon, tokens, serviceInput(colour.id, { name: 'Root touch-up', description: 'Roots only, up to 2 cm.', priceMinor: rupees(4750), durationMinutes: 60, bufferMinutes: 0 }));
  return { nails, colour, gel, art, root };
}

// ---------------------------------------------------------------- AC-12 the Services tab

test('TC-F05-S01-032 AC-12: the Services tab says how many services are bookable online, groups the cards under the categories that have services in the salon\'s order and shows each card\'s badge, category, description, price, time and buffer', async ({ accounts, operator, cat }) => {
  const { salon, owner } = await newSalon(operator, accounts);
  const { nails, gel, art, root } = await arrangeMenu(cat, salon, owner.tokens);
  await cat.move(salon, owner.tokens, nails.id, 'up');
  await cat.move(salon, owner.tokens, nails.id, 'up');
  const { page } = await open(accounts, owner);
  await expect(id(page, T.summary)).toHaveText(TEXT.summary(3, 2));
  await expect(page.locator(`[data-testid="${T.group}"] h2`), 'the salon\'s category order').toHaveText(['Nails', 'Colour']);
  const nailsGroup = page.locator(`[data-testid="${T.group}"][data-id="${nails.id}"]`);
  await expect(nailsGroup.getByTestId(T.card)).toHaveCount(2);
  const expectations: [Service, string, string, string, string, string | null, string][] = [
    [gel, 'Gel polish', 'Online', 'Nails · Long-lasting shine.', 'Rs. 2,500', '+10 min buffer', 'Hide online'],
    [art, 'Nail art', 'Hidden online', 'Nails · Per nail.', 'Rs. 300', null, 'Show online'],
    [root, 'Root touch-up', 'Online', 'Colour · Roots only, up to 2 cm.', 'Rs. 4,750', null, 'Hide online'],
  ];
  for (const [service, name, badge, meta, price, buffer, toggle] of expectations) {
    await test.step(name, async () => {
      const c = card(page, service.id);
      await expect(c.getByTestId(T.cardName)).toHaveText(name);
      await expect(c.getByTestId(T.cardBadge)).toHaveText(badge);
      await expect(c.getByTestId(T.cardMeta)).toHaveText(meta);
      await expect(c.getByTestId(T.cardPrice), 'formatMoney').toHaveText(price);
      await expect(c.getByTestId(T.cardTime)).toHaveText(`${service.terms.durationMinutes} min`);
      if (buffer) await expect(c.getByTestId(T.cardBuffer)).toHaveText(buffer);
      else await expect(c.getByTestId(T.cardBuffer)).toHaveCount(0);
      await expect(c.getByTestId(T.cardEdit)).toHaveText('Edit');
      await expect(c.getByTestId(T.cardOnline)).toHaveText(toggle);
      await expect(c.getByTestId(T.cardDuplicate)).toHaveText('Duplicate');
    });
  }
});

// ---------------------------------------------------------------- AC-3, AC-4 the service sheet

test('TC-F05-S01-033 AC-3, AC-4, AC-16: New service shows the prototype\'s message beside a broken field and disables Create service while it lasts; the created service has its first terms version, and a price change says "Saved. New bookings use the new prices." and makes the next', async ({ accounts, operator, cat }) => {
  const { salon, owner } = await newSalon(operator, accounts);
  const colour = await cat.category(salon, owner.tokens, 'Colour');
  await cat.create(salon, owner.tokens, serviceInput(colour.id, { name: 'Gloss' }));
  const { page } = await open(accounts, owner);
  await id(page, T.newService).click();
  await expect(id(page, T.sheet)).toBeVisible();
  for (const field of [T.name, T.price, T.time]) await expect(id(page, field), `${field} is shown first`).toBeVisible();
  await openMore(page);
  for (const field of [T.category, T.description, T.buffer, T.online]) await expect(id(page, field), `${field} under More options`).toBeVisible();
  await expect(id(page, T.online), 'bookable online is on by default').toHaveAttribute('data-state', 'checked');
  await expect(id(page, T.save)).toHaveText('Create service');
  const broken: [string, string, string, string][] = [
    [T.name, 'A', 'nameLength', TEXT.err.name], [T.name, 'gloss', 'nameTaken', TEXT.err.nameTaken],
    [T.price, '99', 'price', TEXT.err.price], [T.price, '500001', 'price', TEXT.err.price],
    [T.time, '7', 'time', TEXT.err.time], [T.time, '485', 'time', TEXT.err.time],
    [T.buffer, '65', 'buffer', TEXT.err.buffer],
  ];
  const valid: Record<string, string> = { [T.name]: 'Root touch-up', [T.price]: '4750', [T.time]: '60', [T.buffer]: '15' };
  await expect(id(page, T.photoAfterCreate), 'a photo is added after creating').toHaveText(TEXT.photoAfterCreate);
  for (const [field, value] of Object.entries(valid)) await id(page, field).fill(value);
  await choose(page, T.category, colour.id);
  for (const [field, value, errorOf, message] of broken) {
    await test.step(`${field} "${value}"`, async () => {
      await id(page, field).fill(value);
      await expect(problem(page, errorOf), 'the prototype\'s message').toHaveText(message);
      await expect(id(page, field), 'the field is marked invalid').toHaveAttribute('aria-invalid', 'true');
      await expect(id(page, T.save), 'Create service is disabled').toBeDisabled();
      await id(page, field).fill(valid[field]);
      await expect(problem(page, errorOf)).toHaveCount(0);
    });
  }
  await expect(id(page, T.save)).toBeEnabled();
  await id(page, T.save).click();
  await expect(id(page, T.sheet)).toHaveCount(0);
  const created = (await cat.services(salon, owner.tokens)).find(s => s.name === 'Root touch-up')!;
  expect(created, 'saved').toBeTruthy();
  expect(created.terms).toMatchObject({ number: 1, priceMinor: 475000, durationMinutes: 60, bufferMinutes: 15 });
  await expect(card(page, created.id)).toBeVisible();
  await test.step('a price change', async () => {
    await card(page, created.id).getByTestId(T.cardEdit).click();
    await expect(id(page, T.save)).toHaveText('Save changes');
    await id(page, T.price).fill('5000');
    await id(page, T.save).click();
    await expect(page.getByText(TEXT.termsSaved)).toBeVisible();
    const after = await cat.service(salon, owner.tokens, created.id);
    expect(after.terms).toMatchObject({ number: 2, priceMinor: 500000 });
    await expect(card(page, created.id).getByTestId(T.cardPrice)).toHaveText('Rs. 5,000');
  });
});

test('TC-F05-S01-034 AC-7, AC-8: Duplicate opens the sheet on the hidden copy with its message, and Hide online and Show online change the badge and say what they did', async ({ accounts, operator, cat }) => {
  const { salon, owner } = await newSalon(operator, accounts);
  const { gel } = await arrangeMenu(cat, salon, owner.tokens);
  const { page } = await open(accounts, owner);
  await card(page, gel.id).getByTestId(T.cardDuplicate).click();
  await expect(page.getByText(TEXT.duplicated)).toBeVisible();
  await expect(id(page, T.sheet), 'the sheet opens on the copy').toBeVisible();
  await expect(id(page, T.name)).toHaveValue('Gel polish (copy)');
  await expect(id(page, T.warning), 'the prototype\'s warning for a hidden service').toContainText(TEXT.hiddenWarning);
  await id(page, T.close).click();
  const copy = (await cat.services(salon, owner.tokens)).find(s => s.name === 'Gel polish (copy)')!;
  expect(copy.bookableOnline).toBe(false);
  await expect(card(page, copy.id).getByTestId(T.cardBadge)).toHaveText('Hidden online');
  await card(page, copy.id).getByTestId(T.cardOnline).click();
  await expect(page.getByText(TEXT.shown)).toBeVisible();
  await expect(card(page, copy.id).getByTestId(T.cardBadge)).toHaveText('Online');
  await card(page, gel.id).getByTestId(T.cardOnline).click();
  await expect(page.getByText(TEXT.hidden)).toBeVisible();
  await expect(card(page, gel.id).getByTestId(T.cardBadge)).toHaveText('Hidden online');
  await expect(card(page, gel.id).getByTestId(T.cardOnline)).toHaveText('Show online');
  expect((await cat.service(salon, owner.tokens, gel.id)).bookableOnline).toBe(false);
  expect((await cat.service(salon, owner.tokens, copy.id)).bookableOnline).toBe(true);
  await expect(id(page, T.summary)).toHaveText(TEXT.summary(4, 2));
});

test('TC-F05-S01-035 AC-9: Delete service asks first with the prototype\'s words; Cancel keeps the service and Delete removes it from the list', async ({ accounts, operator, cat }) => {
  const { salon, owner } = await newSalon(operator, accounts);
  const { gel } = await arrangeMenu(cat, salon, owner.tokens);
  const { page } = await open(accounts, owner);
  await card(page, gel.id).getByTestId(T.cardEdit).click();
  await id(page, T.del).click();
  const dialog = id(page, T.deleteDialog);
  await expect(dialog.getByRole('heading')).toHaveText(TEXT.deleteTitle('Gel polish'));
  await expect(dialog).toContainText(TEXT.deleteBody);
  await id(page, T.deleteCancel).click();
  await expect(id(page, T.deleteDialog)).toHaveCount(0);
  expect((await cat.service(salon, owner.tokens, gel.id)).id, 'Cancel deletes nothing').toBe(gel.id);
  await id(page, T.del).click();
  await id(page, T.deleteConfirm).click();
  await expect(card(page, gel.id)).toHaveCount(0);
  await expect(id(page, T.summary)).toHaveText(TEXT.summary(2, 1));
  expect((await cat.serviceRaw(salon, owner.tokens, gel.id)).status(), 'deleted').toBe(404);
});

// ---------------------------------------------------------------- AC-2 the Categories tab

test('TC-F05-S01-036 AC-2: on the Categories tab the order buttons move a category and say the order is saved, New category adds at the end with the prototype\'s messages, Rename renames, an empty category is deleted after confirming and one with services says to move them first', async ({ accounts, operator, cat }) => {
  const { salon, owner } = await newSalon(operator, accounts);
  const nails = await cat.category(salon, owner.tokens, 'Nails');
  const colour = await cat.category(salon, owner.tokens, 'Colour');
  for (const name of ['Root touch-up', 'Gloss']) await cat.create(salon, owner.tokens, serviceInput(colour.id, { name }));
  const { page } = await open(accounts, owner, CATEGORIES_ROUTE);
  await expect(id(page, T.categoryRowName)).toHaveText([...DEFAULT_CATEGORIES]);
  await test.step('Move Nails up', async () => {
    const up = row(page, nails.id).getByTestId(T.categoryUp);
    await expect(up, 'named for its category').toHaveAccessibleName('Move Nails up');
    await expect(row(page, nails.id).getByTestId(T.categoryDown)).toHaveAccessibleName('Move Nails down');
    await up.click();
    await expect(page.getByText(TEXT.orderSaved)).toBeVisible();
    const expected = ['Cuts & styling', 'Colour', 'Nails', 'Facials & skin', 'Brows & lashes', 'Waxing', 'Makeup & draping', 'Massage & spa', 'Bridal', 'Consultations'];
    await expect(id(page, T.categoryRowName)).toHaveText(expected);
    expect((await cat.categories(salon, owner.tokens)).items.map(c => c.name), 'saved at once').toEqual(expected);
  });
  await test.step('New category', async () => {
    await id(page, T.newCategory).click();
    await expect(id(page, T.categorySheet)).toBeVisible();
    for (const [value, message] of [['A', TEXT.err.categoryName], ['colour', TEXT.err.categoryTaken]] as const) {
      await id(page, T.categoryName).fill(value);
      await id(page, T.categorySave).click();
      await expect(id(page, T.categoryError), `"${value}"`).toHaveText(message);
    }
    await id(page, T.categoryName).fill('Kids’ cuts');
    await id(page, T.categorySave).click();
    await expect(id(page, T.categorySheet)).toHaveCount(0);
    await expect(id(page, T.categoryRowName).last(), 'at the end').toHaveText('Kids’ cuts');
  });
  const kids = await cat.category(salon, owner.tokens, 'Kids’ cuts');
  await test.step('Rename', async () => {
    await row(page, kids.id).getByTestId(T.categoryRename).click();
    await expect(id(page, T.categoryName)).toHaveValue('Kids’ cuts');
    await id(page, T.categoryName).fill('Kids');
    await id(page, T.categorySave).click();
    await expect(row(page, kids.id).getByTestId(T.categoryRowName)).toHaveText('Kids');
  });
  await test.step('Delete a category with two services', async () => {
    await row(page, colour.id).getByTestId(T.categoryDelete).click();
    await expect(page.getByText(TEXT.moveFirst(2))).toBeVisible();
    await expect(row(page, colour.id), 'still there').toBeVisible();
  });
  await test.step('Delete an empty category', async () => {
    await row(page, kids.id).getByTestId(T.categoryDelete).click();
    await expect(id(page, T.categoryDeleteDialog)).toBeVisible();
    await id(page, T.categoryDeleteConfirm).click();
    await expect(row(page, kids.id)).toHaveCount(0);
    expect((await cat.categories(salon, owner.tokens)).items.map(c => c.id)).not.toContain(kids.id);
  });
});

// ---------------------------------------------------------------- AC-10 the photo control

test('TC-F05-S01-037 AC-10, AC-16: the photo control is named, previews the chosen photo, keeps the centre square of a wide photo, shows the reason for a refused file while keeping the previous photo, and Remove photo removes it', async ({ accounts, operator, cat }) => {
  const { salon, owner } = await newSalon(operator, accounts);
  const { gel } = await arrangeMenu(cat, salon, owner.tokens);
  const { page } = await open(accounts, owner);
  await card(page, gel.id).getByTestId(T.cardEdit).click();
  await openMore(page);
  await expect(id(page, T.photoChoose), 'the photo control has a name').toHaveAccessibleName(/.+/);
  // On an existing service a chosen file uploads at once (Corrections at close).
  await page.getByTestId(T.photo).setInputFiles(fixture('logo-700x600-banded.png'));
  await expect(id(page, T.photoPreview)).toBeVisible();
  await expect.poll(async () => (await cat.service(salon, owner.tokens, gel.id)).photoUrl, { message: 'the photo is saved' }).not.toBeNull();
  const first = (await cat.service(salon, owner.tokens, gel.id)).photoUrl!;
  await test.step('the kept photo is the centre of the 700 x 600 image: no red band at its edges', async () => {
    // Read through the web on the salon's own hostname (same origin, so the canvas can be read).
    const url = PENDING_F05_S01.webPhotoPath(photoIdOf(first));
    const pixels = await page.evaluate(async (src) => {
      const img = new Image(); img.src = src; await img.decode();
      const c = document.createElement('canvas'); c.width = img.naturalWidth; c.height = img.naturalHeight;
      const ctx = c.getContext('2d')!; ctx.drawImage(img, 0, 0);
      const at = (x: number, y: number) => Array.from(ctx.getImageData(x, y, 1, 1).data.slice(0, 3));
      const w = img.naturalWidth - 1, h = img.naturalHeight - 1, m = Math.floor(h / 2);
      return { size: [img.naturalWidth, img.naturalHeight], edges: [at(3, m), at(w - 3, m), at(3, 3), at(w - 3, h - 3)] };
    }, url);
    expect(pixels.size).toEqual([600, 600]);
    for (const [r, , b] of pixels.edges) expect(r < 90 && b > 150, `an edge pixel is the centre's blue, not the cut-off red: ${r},${b}`).toBe(true);
  });
  await test.step('a refused file', async () => {
    await page.getByTestId(T.photo).setInputFiles(fixture('not-an-image.txt', 'image/png', 'photo.png'));
    await expect(id(page, T.photoError), 'the reason is shown').toBeVisible();
    expect((await cat.service(salon, owner.tokens, gel.id)).photoUrl, 'the previous photo is kept').toBe(first);
  });
  await test.step('Remove photo', async () => {
    await id(page, T.photoRemove).click();
    await expect.poll(async () => (await cat.service(salon, owner.tokens, gel.id)).photoUrl, { message: 'the photo is removed' }).toBeNull();
  });
});

// ---------------------------------------------------------------- AC-13 roles

test('TC-F05-S01-038 AC-13: the Receptionist, Professional and Accountant find Services and read both tabs with no control to change anything; a support session reads them the same way', async ({ accounts, operator, cat, browser }) => {
  const changeControls = [T.newService, T.cardEdit, T.cardOnline, T.cardDuplicate, T.newCategory, T.categoryUp, T.categoryDown, T.categoryRename, T.categoryDelete];
  const readOnly = async (page: Page, label: string) => {
    await expect(id(page, T.summary), `${label}: the Services tab`).toBeVisible();
    await expect(page.getByTestId(T.card).first(), `${label}: the services`).toBeVisible();
    await id(page, T.tabCategories).click();
    await expect(page).toHaveURL(new RegExp(`${CATEGORIES_ROUTE}$`));
    await expect(id(page, T.categoryRowName).first(), `${label}: the categories`).toBeVisible();
    await id(page, T.tabServices).click();
    await ready(page);
    for (const control of changeControls) await expect(page.getByTestId(control), `${label}: no ${control}`).toHaveCount(0);
    await page.goto(CATEGORIES_ROUTE);
    await ready(page);
    for (const control of changeControls) await expect(page.getByTestId(control), `${label}: no ${control}`).toHaveCount(0);
  };
  const mark = `QA read ${Date.now().toString(36)}`;
  const manager = await accounts.identity.staff(alpha, 'MANAGER');
  const nails = await cat.category(alpha, manager.tokens, 'Nails');
  await cat.create(alpha, manager.tokens, serviceInput(nails.id, { name: mark }));
  try {
    for (const role of ['RECEPTIONIST', 'PROFESSIONAL', 'ACCOUNTANT'] as Role[]) {
      await test.step(role, async () => {
        const actor = await accounts.identity.staff(alpha, role);
        const context = await accounts.context(alpha, [actor]);
        const page = await accounts.open(context, '/workspace/account');
        await id(page, T.nav).click();
        await expect(page).toHaveURL(new RegExp(`${SERVICES_ROUTE}$`));
        await ready(page);
        await readOnly(page, role);
        await context.close();
      });
    }
  } finally {
    await cat.removeMarked(alpha, manager.tokens, mark);
  }
  await test.step('a support session', async () => {
    const { created, salon, owner } = await newSalon(operator, accounts);
    await arrangeMenu(cat, salon, owner.tokens);
    const support = await operator.actor('OPERATOR_SUPPORT');
    const session = await supportJson<SupportStart>(await operator.startSupport(support, created.id, { reason: 'QA F05 support reads the services' }), 'supportStart', 201);
    // The salon's own web address as the base, so readOnly's relative routes stay on this salon.
    const context = await browser.newContext({ baseURL: salonUrl(salon, webUrl), viewport: { width: 1280, height: 1000 }, locale: 'en' });
    try {
      const entry = await context.newPage();
      await entry.goto(`${salonUrl(salon, webUrl)}/workspace/support#${session.entryCode}`);
      await expect(entry.getByTestId('support-banner')).toBeVisible();
      const page = await context.newPage();
      await page.goto(`${salonUrl(salon, webUrl)}${SERVICES_ROUTE}`);
      await ready(page);
      await readOnly(page, 'support');
    } finally {
      await context.close();
    }
  });
});

// ---------------------------------------------------------------- AC-16 states, leave guard, widths, keyboard and accessibility

test('TC-F05-S01-039 AC-16: both tabs show their loading state, say an unreachable service plainly and recover on Try again; a save someone else overtook says so and keeps their change; a change refused after a role change says so, saves nothing and leaves the page read-only', async ({ accounts, operator, cat }) => {
  const { salon, owner } = await newSalon(operator, accounts);
  const { gel } = await arrangeMenu(cat, salon, owner.tokens);
  const context = await accounts.context(salon, [owner]);
  const data = (url: URL) => PENDING_F05_S01.bffMatch(url);
  for (const route of [SERVICES_ROUTE, CATEGORIES_ROUTE]) {
    await test.step(`${route}: loading`, async () => {
      let release!: () => void;
      const held = new Promise<void>(resolve => { release = resolve; });
      await context.route(data, async r => { await held; await r.continue(); });
      const page = await context.newPage();
      void page.goto(route);
      await expect(id(page, T.loading), 'the Skeleton while loading').toBeVisible();
      release();
      await ready(page);
      await context.unroute(data);
      await page.close();
    });
    await test.step(`${route}: unreachable, then Try again`, async () => {
      await context.route(data, r => r.abort('connectionfailed'));
      const page = await accounts.open(context, route);
      await expect(id(page, T.unavailable)).toBeVisible();
      await context.unroute(data);
      await id(page, T.retry).click();
      await ready(page);
      await page.close();
    });
  }
  await test.step('a refused change: the Manager is made a Receptionist while the page is open', async () => {
    const mark = `QA refused ${Date.now().toString(36)}`;
    const identity = accounts.identity;
    const owner = await identity.staff(alpha, 'OWNER');
    const manager = await identity.staff(alpha, 'MANAGER');
    const service = await cat.create(alpha, manager.tokens, serviceInput((await cat.category(alpha, manager.tokens, 'Nails')).id, { name: mark }));
    try {
      const { page } = await open(accounts, manager);
      await expect(card(page, service.id)).toBeVisible();
      const record = (await identity.list(owner)).find(x => x.id === manager.principal.id)!;
      const changed = await identity.call(alpha, 'PATCH', `/staff/${manager.principal.id}`, { version: record.version, role: 'RECEPTIONIST' }, owner.tokens);
      expect(changed.status(), 'the Owner changed the role').toBe(200);
      await card(page, service.id).getByTestId(T.cardOnline).click();
      await expect(id(page, T.refused), 'the screen says the change is not allowed').toHaveText(TEXT.refused);
      expect((await cat.service(alpha, owner.tokens, service.id)).bookableOnline, 'nothing saved').toBe(true);
      await page.reload();
      await ready(page);
      await expect(page.getByTestId(T.cardOnline), 'read-only after the next load').toHaveCount(0);
    } finally {
      await cat.removeMarked(alpha, owner.tokens, mark);
    }
  });
  await test.step('a save someone else overtook', async () => {
    const page = await accounts.open(context, SERVICES_ROUTE);
    await ready(page);
    await card(page, gel.id).getByTestId(T.cardEdit).click();
    await cat.update(salon, owner.tokens, gel, { priceMinor: rupees(2600) });
    await id(page, T.price).fill('2700');
    await id(page, T.save).click();
    // By role: a message behind the sheet's overlay is hidden from the person (aria-hidden) and must not count.
    await expect(page.getByRole('alert').filter({ hasText: 'Someone else changed the services' }), 'the person is told someone else changed it').toBeVisible();
    await expect(page.getByRole('button', { name: 'Reload' }), 'and can reload').toBeVisible();
    expect((await cat.service(salon, owner.tokens, gel.id)).terms.priceMinor, 'their price stands').toBe(260000);
    await id(page, T.conflictReload).click();
    await expect(id(page, T.price)).toHaveValue('2600');
    await page.close();
  });
});

test('TC-F05-S01-040 AC-16: leaving the service sheet with unsaved edits by Escape or Close asks first; Stay keeps the edits, Leave discards them, and closing the page asks the browser', async ({ accounts, operator, cat }) => {
  const { salon, owner } = await newSalon(operator, accounts);
  const { gel } = await arrangeMenu(cat, salon, owner.tokens);
  const { context, page } = await open(accounts, owner);
  await card(page, gel.id).getByTestId(T.cardEdit).click();
  await id(page, T.name).fill('Gel polish deluxe');
  await test.step('closing the sheet', async () => {
    await page.keyboard.press('Escape');
    await expect(id(page, T.leaveDialog)).toBeVisible();
    await id(page, T.leaveStay).click();
    await expect(id(page, T.name), 'the edit is kept').toHaveValue('Gel polish deluxe');
  });
  await test.step('closing the page asks the browser', async () => {
    const dialog = page.waitForEvent('dialog');
    await page.close({ runBeforeUnload: true });
    const shown = await dialog;
    expect(shown.type()).toBe('beforeunload');
    await shown.dismiss();
    await expect(id(page, T.name), 'dismissing keeps the page and the edit').toHaveValue('Gel polish deluxe');
  });
  await test.step('Leave discards', async () => {
    await id(page, T.close).click();
    await expect(id(page, T.leaveDialog)).toBeVisible();
    await id(page, T.leaveConfirm).click();
    await expect(id(page, T.sheet)).toHaveCount(0);
    expect((await cat.service(salon, owner.tokens, gel.id)).name, 'nothing saved').toBe('Gel polish');
  });
  await context.close();
});

test('TC-F05-S01-041 AC-16: at every width of the matrix both tabs, with a long menu, and both sheets fit with no sideways scrolling and every control reachable', async ({ accounts, operator, cat }) => {
  test.setTimeout(300_000);
  const { salon, owner } = await newSalon(operator, accounts);
  const { gel } = await arrangeMenu(cat, salon, owner.tokens);
  const bridal = await cat.category(salon, owner.tokens, 'Bridal');
  for (let n = 1; n <= 24; n++) {
    await cat.create(salon, owner.tokens, serviceInput(bridal.id, { name: `Bridal package with a long name ${String(n).padStart(2, '0')}`.slice(0, 40),
      description: 'A description that runs to the ninety characters the form allows for a service, exactly.'.slice(0, 90), bufferMinutes: 60 }));
  }
  await cat.addCategory(salon, owner.tokens, 'A category name of thirty chs.');
  for (const { width, height } of DESIGN_WIDTHS) {
    await test.step(`${width} wide`, async () => {
      const context = await accounts.context(salon, [owner], { width });
      const page = await accounts.open(context, SERVICES_ROUTE);
      await page.setViewportSize({ width, height });
      await ready(page);
      const checkFit = async (label: string) => {
        await settled(page);
        const f = await fit(page);
        expect(f.counted, `${width} ${label}: elements measured`).toBeGreaterThan(0);
        expect(f.pageScrolls, `${width} ${label}: no sideways scrolling`).toBe(false);
        expect(f.outside, `${width} ${label}: nothing outside the screen`).toEqual([]);
      };
      await checkFit('Services tab');
      await expect(id(page, T.newService)).toBeInViewport();
      await card(page, gel.id).getByTestId(T.cardEdit).scrollIntoViewIfNeeded();
      await expect(card(page, gel.id).getByTestId(T.cardDuplicate)).toBeVisible();
      await card(page, gel.id).getByTestId(T.cardEdit).click();
      await openMore(page);
      await checkFit('service sheet');
      await id(page, T.save).scrollIntoViewIfNeeded();
      await expect(id(page, T.save)).toBeInViewport();
      await expect(id(page, T.del)).toBeVisible();
      await id(page, T.close).click();
      await id(page, T.tabCategories).click();
      await ready(page);
      await checkFit('Categories tab');
      await id(page, T.newCategory).click();
      await checkFit('category sheet');
      await expect(id(page, T.categorySave)).toBeInViewport();
      await context.close();
    });
  }
});

test('TC-F05-S01-042 AC-16: both tabs, both sheets and the delete confirmation have no serious accessibility violation for the Owner and a read-only role; a category is moved and a service created by keyboard alone with a visible focus', async ({ accounts, operator, cat }) => {
  const { salon, owner } = await newSalon(operator, accounts);
  const { nails, gel } = await arrangeMenu(cat, salon, owner.tokens);
  const { page } = await open(accounts, owner);
  await accessible(page, 'Services tab');
  await card(page, gel.id).getByTestId(T.cardEdit).click();
  await openMore(page);
  await id(page, T.name).fill('A');
  await expect(problem(page, 'nameLength')).toBeVisible();
  await accessible(page, 'service sheet with a field error');
  await id(page, T.name).fill('Gel polish');
  await id(page, T.del).click();
  await accessible(page, 'delete confirmation');
  await id(page, T.deleteCancel).click();
  await id(page, T.close).click();
  await id(page, T.tabCategories).click();
  await ready(page);
  await accessible(page, 'Categories tab');
  await id(page, T.newCategory).click();
  await accessible(page, 'category sheet');
  await page.keyboard.press('Escape');
  await test.step('move Nails up by keyboard', async () => {
    const up = row(page, nails.id).getByTestId(T.categoryUp);
    await tabTo(page, up);
    const focus = await focusIndicator(up);
    expect(focus.focusVisible && focus.outlineWidth > 0 && focus.outlineStyle !== 'none', `a visible focus: ${JSON.stringify(focus)}`).toBe(true);
    await page.keyboard.press('Enter');
    await expect(page.getByText(TEXT.orderSaved)).toBeVisible();
    expect((await cat.categories(salon, owner.tokens)).items.map(c => c.name).indexOf('Nails'), 'moved up one').toBe(2);
  });
  await test.step('create a service by keyboard', async () => {
    await id(page, T.tabServices).click();
    await ready(page);
    await tabTo(page, id(page, T.newService));
    await page.keyboard.press('Enter');
    await expect(id(page, T.name)).toBeVisible();
    await tabTo(page, id(page, T.name));
    await page.keyboard.type('Keyboard cut');
    await tabTo(page, id(page, T.price));
    await page.keyboard.type('1500');
    await tabTo(page, id(page, T.time));
    await page.keyboard.type('30');
    await tabTo(page, id(page, T.save));
    await page.keyboard.press('Enter');
    await expect(id(page, T.sheet)).toHaveCount(0);
    expect((await cat.services(salon, owner.tokens)).some(s => s.name === 'Keyboard cut'), 'created').toBe(true);
  });
  await test.step('a read-only role', async () => {
    const receptionist = await accounts.identity.staff(alpha, 'RECEPTIONIST');
    const other = await open(accounts, receptionist);
    await accessible(other.page, 'Services tab, Receptionist');
    await id(other.page, T.tabCategories).click();
    await ready(other.page);
    await accessible(other.page, 'Categories tab, Receptionist');
  });
});

// ---------------------------------------------------------------- after the PR #19 review

test('TC-F05-S01-045 AC-3: an empty salon in another currency (NFR-13) is asked for and prices its first service in that currency, not rupees', async ({ accounts, operator, cat }) => {
  const { salon, owner } = await newCatalogueSalon(operator, accounts.identity, { currency: 'USD' });
  const { page } = await open(accounts, owner);
  await id(page, T.newService).click();
  await expect(page.locator('label[for="service-price"]'), 'the price field names the salon\'s currency').toHaveText('Price (USD)');
  await id(page, T.name).fill('First service');
  await id(page, T.price).fill('150');
  await id(page, T.time).fill('30');
  await id(page, T.save).click();
  await expect(id(page, T.sheet)).toHaveCount(0);
  const [service] = await cat.services(salon, owner.tokens);
  expect(service.terms, 'kept in the salon\'s currency').toMatchObject({ priceMinor: 15000, currency: 'USD' });
  const shown = card(page, service.id).getByTestId(T.cardPrice);
  await expect(shown).toContainText('150');
  await expect(shown, 'not rupees').not.toContainText('Rs.');
});

test('TC-F05-S01-046 AC-10, AC-16: a photo or save result applies only to the sheet opening that sent it: Save and Delete wait for an upload and Close does not; a sheet opened meanwhile keeps its own state, and a later Save of a reopened service says it changed, with Reload', async ({ accounts, operator, cat }) => {
  const { salon, owner } = await newSalon(operator, accounts);
  const { gel, art, root } = await arrangeMenu(cat, salon, owner.tokens);
  const { context, page } = await open(accounts, owner);
  const PHOTO = () => fixture('logo-700x600-banded.png');
  /** Holds the browser's calls to one BFF action until the returned function is called. */
  const hold = async (action: string) => {
    let release!: () => void;
    const held = new Promise<void>(resolve => { release = resolve; });
    const match = (url: URL) => url.pathname.endsWith(`/${action}`);
    let handled: Promise<void> | undefined;
    await context.route(match, route => { handled = held.then(() => route.continue()); return handled; });
    return async () => { release(); await handled; await context.unroute(match); };
  };
  const edit = async (serviceId: string) => {
    await card(page, serviceId).getByTestId(T.cardEdit).click();
    await expect(id(page, T.sheet)).toBeVisible();
    await openMore(page);
  };
  const photoOf = async (serviceId: string) => (await cat.service(salon, owner.tokens, serviceId)).photoUrl;

  await test.step('during an upload Save and Delete wait, and Close does not', async () => {
    await edit(gel.id);
    await id(page, T.name).fill('Gel polish two');
    await expect(id(page, T.save), 'an edit can be saved').toBeEnabled();
    const release = await hold('catalogue-photo');
    await page.getByTestId(T.photo).setInputFiles(PHOTO());
    await expect(id(page, T.save), 'Save waits for the upload').toBeDisabled();
    await expect(id(page, T.del), 'Delete waits for the upload').toBeDisabled();
    await expect(id(page, T.close), 'Close stays enabled').toBeEnabled();
    await release();
    await expect(id(page, T.photoPreview), 'the same opening shows its photo').toBeVisible();
    await expect(id(page, T.save), 'Save is back').toBeEnabled();
    await id(page, T.save).click();
    await expect(id(page, T.sheet)).toHaveCount(0);
    const saved = await cat.service(salon, owner.tokens, gel.id);
    expect(saved.name, 'the edit saved').toBe('Gel polish two');
    expect(saved.photoUrl, 'and the photo kept').toBeTruthy();
  });

  await test.step('the same service reopened during its upload: the result stays out, and a later Save says it changed, with Reload', async () => {
    await edit(root.id);
    const release = await hold('catalogue-photo');
    await page.getByTestId(T.photo).setInputFiles(PHOTO());
    await expect(id(page, T.save)).toBeDisabled();
    await id(page, T.close).click();
    await expect(id(page, T.sheet)).toHaveCount(0);
    await edit(root.id);
    await expect(id(page, T.photoPreview), 'no photo yet in this opening').toHaveCount(0);
    await release();
    await expect.poll(() => photoOf(root.id), { message: 'the upload itself is saved' }).toBeTruthy();
    await page.waitForTimeout(300); // what would apply the old opening's result has had its turn
    await expect(id(page, T.photoPreview), 'the result stays out of this opening').toHaveCount(0);
    await id(page, T.name).fill('Root touch-up two');
    await id(page, T.save).click();
    await expect(page.getByRole('alert').filter({ hasText: 'Someone else changed the services' }), 'told it changed').toBeVisible();
    expect((await cat.service(salon, owner.tokens, root.id)).name, 'nothing saved').toBe('Root touch-up');
    await page.getByRole('button', { name: 'Reload' }).click();
    await expect(id(page, T.name), 'Reload shows what is saved').toHaveValue('Root touch-up');
    await expect(id(page, T.photoPreview), 'with its photo').toBeVisible();
    await id(page, T.close).click();
  });

  await test.step('another service opened during an upload keeps its own photo and state', async () => {
    const own = photoIdOf((await cat.service(salon, owner.tokens, gel.id)).photoUrl!);
    await edit(art.id);
    const release = await hold('catalogue-photo');
    await page.getByTestId(T.photo).setInputFiles(PHOTO());
    await expect(id(page, T.save)).toBeDisabled();
    await id(page, T.close).click();
    await edit(gel.id);
    await expect(id(page, T.photoPreview)).toHaveAttribute('src', new RegExp(own));
    await release();
    await expect.poll(() => photoOf(art.id), { message: 'the other service\'s upload is saved' }).toBeTruthy();
    await page.waitForTimeout(300);
    await expect(id(page, T.photoPreview), 'still this service\'s own photo').toHaveAttribute('src', new RegExp(own));
    await id(page, T.close).click();
  });

  await test.step('a save refused after its sheet was left says nothing in the sheet opened since', async () => {
    const current = await cat.service(salon, owner.tokens, art.id);
    await edit(art.id);
    await id(page, T.name).fill('Taken in the meantime');
    const nails = await cat.category(salon, owner.tokens, 'Nails');
    await cat.create(salon, owner.tokens, serviceInput(nails.id, { name: 'Taken in the meantime' }));
    const release = await hold('catalogue-service-save');
    await id(page, T.save).click();
    await id(page, T.close).click();
    await id(page, T.leaveConfirm).click();
    await expect(id(page, T.sheet)).toHaveCount(0);
    await edit(gel.id);
    await release();
    await page.waitForTimeout(500);
    await expect(id(page, T.name), 'this opening is untouched').toHaveValue('Gel polish two');
    await expect(page.getByTestId('service-sheet-problem'), 'no message of the other save').toHaveCount(0);
    await expect(page.getByTestId(T.rule), 'no refused rule').toHaveCount(0);
    expect((await cat.service(salon, owner.tokens, art.id)).name, 'the refused save changed nothing').toBe(current.name);
  });
});

// ---------------------------------------------------------------- CR-025 (decision 098)

const say = (text: string) => text.replace(/\s+/g, ' ').trim();
const infoBanners = (page: Page) => page.locator('[role="alert"][data-variant="info"]').filter({ visible: true });

test('TC-F05-S01-047 AC-19: the sheets state money limits in the salon\'s currency: an LKR salon keeps its wording and a USD salon reads the same limits in USD, for the service price, a variant\'s price, a fixed deposit and a product\'s price', async ({ accounts, operator }) => {
  const rows: { currency: string; amount: (n: string) => string }[] = [
    { currency: 'LKR', amount: n => `Rs\\. ${n}` },
    // Through formatMoney, in the browser's locale data: "$500,000" or "US$500,000".
    { currency: 'USD', amount: n => `(US)?\\$${n}` },
  ];
  for (const { currency, amount } of rows) {
    await test.step(currency, async () => {
      const { owner } = await newCatalogueSalon(operator, accounts.identity, { currency });
      const { page } = await open(accounts, owner);
      const said = (locator: string, text: string) => page.getByTestId(locator).filter({ hasText: new RegExp(`^${text}$`) });
      const servicePrice = `Price must be ${amount('100')} to ${amount('500,000')}\\.`;
      const variantPrice = `Price for “Short” must be ${amount('100')} to ${amount('500,000')}\\.`;
      const deposit = `A fixed deposit must be ${amount('100')} to ${amount('100,000')}\\.`;
      const productPrice = `Price must be ${amount('1')} to ${amount('500,000')}\\.`;
      await id(page, T.newService).click();
      await id(page, T.name).fill('Limits');
      await id(page, T.price).fill('99');
      await expect(said('service-sheet-problem', servicePrice), 'the service price').toBeVisible();
      await id(page, T.price).fill('100');
      await expect(said('service-sheet-problem', servicePrice), 'the same limit passes').toHaveCount(0);
      await id(page, T.price).fill('5000');
      await openMore(page);
      await id(page, 'service-deposit-custom').click();
      await id(page, 'service-deposit-type').click();
      await id(page, 'service-deposit-type-amount').click();
      await id(page, 'service-deposit-amount').fill('99');
      await expect(said('service-sheet-problem', deposit), 'a fixed deposit').toBeVisible();
      await id(page, 'service-deposit-amount').fill('100');
      await expect(said('service-sheet-problem', deposit)).toHaveCount(0);
      await id(page, 'service-variants-toggle').click();
      const row = id(page, 'service-variant-row').first();
      await row.getByTestId('service-variant-name').fill('Short');
      await row.getByTestId('service-variant-price').fill('99');
      await expect(said('service-sheet-problem', variantPrice), 'a variant\'s price').toBeVisible();
      await id(page, T.close).click();
      await id(page, T.leaveConfirm).click();
      await page.goto('/workspace/services/products');
      await id(page, 'products-new').click();
      await id(page, 'product-sheet-name').fill('Limits');
      await id(page, 'product-sheet-price').fill('0');
      await expect(said('product-sheet-problem', productPrice), 'a product\'s price').toBeVisible();
      await id(page, 'product-sheet-price').fill('1');
      await expect(said('product-sheet-problem', productPrice)).toHaveCount(0);
    });
  }
});

test('TC-F05-S01-049 AC-17: the Services tab shows one information banner holding its sentence and the tax sentence, for each state of the tax settings, and no second banner', async ({ accounts, operator }) => {
  const { salon, owner } = await newSalon(operator, accounts);
  const tax = new TaxCatalogue(accounts.identity);
  const { page } = await open(accounts, owner);
  const states: [string, string, (() => Promise<unknown>) | null][] = [
    ['never saved', TEXT.tax.off, null],
    ['VAT and a service charge', TEXT.tax.both, () => tax.save(salon, owner.tokens, taxInput({ vat: 'LK-1', sc: 10 }), 0)],
    ['VAT only', TEXT.tax.vat, () => tax.save(salon, owner.tokens, taxInput({ vat: 'LK-1' }), 1)],
    ['a service charge only', TEXT.tax.serviceCharge, () => tax.save(salon, owner.tokens, taxInput({ sc: 10 }), 2)],
    ['both off', TEXT.tax.off, () => tax.save(salon, owner.tokens, taxInput(), 3)],
  ];
  for (const [label, sentence, arrange] of states) {
    await test.step(label, async () => {
      if (arrange) { await arrange(); await page.reload(); await ready(page); }
      await expect.poll(async () => say(await id(page, T.banner).innerText()), `${label}: both sentences in the one banner`).toBe(`${TEXT.banner} ${sentence}`);
      await expect(infoBanners(page), `${label}: one information banner, no second`).toHaveCount(1);
    });
  }
});

test('TC-F05-S01-050 AC-18: the Services tab lists only categories with services and folds the others into one line in the salon\'s order; a salon with no services sees the Empty state and no folded line, with + New service only for who may use it; the Categories tab still lists every category', async ({ accounts, operator, cat }) => {
  const { salon, owner } = await newSalon(operator, accounts);
  const { page } = await open(accounts, owner);
  const groupNames = page.locator(`[data-testid="${T.group}"] h2`);
  await test.step('no services at all', async () => {
    await expect(id(page, T.summary)).toHaveText(TEXT.summary(0, 0));
    await expect(id(page, T.empty)).toContainText(TEXT.empty);
    await expect(id(page, T.emptyNew), 'for the Owner').toBeVisible();
    await expect(page.getByTestId(T.folded), 'no folded line').toHaveCount(0);
    await expect(groupNames, 'no group').toHaveCount(0);
  });
  await test.step('some categories with services, in the salon\'s order', async () => {
    const { nails } = await arrangeMenu(cat, salon, owner.tokens); // Nails and Colour
    await cat.move(salon, owner.tokens, nails.id, 'up');
    await cat.move(salon, owner.tokens, nails.id, 'up');
    await page.reload();
    await ready(page);
    await expect(groupNames).toHaveText(['Nails', 'Colour']);
    await expect(id(page, T.empty), 'no Empty state').toHaveCount(0);
    await expect(id(page, T.folded)).toHaveText(TEXT.folded(['Cuts & styling', 'Facials & skin', 'Brows & lashes', 'Waxing', 'Makeup & draping', 'Massage & spa', 'Bridal', 'Consultations']));
  });
  await test.step('the Categories tab still lists all ten', async () => {
    await id(page, T.tabCategories).click();
    await ready(page);
    await expect(id(page, T.categoryRow)).toHaveCount(DEFAULT_CATEGORIES.length);
    await id(page, T.tabServices).click();
    await ready(page);
  });
  await test.step('every category with a service: no folded line', async () => {
    for (const c of (await cat.categories(salon, owner.tokens)).items.filter(x => x.serviceCount === 0)) {
      await cat.create(salon, owner.tokens, serviceInput(c.id));
    }
    await page.reload();
    await ready(page);
    await expect(groupNames).toHaveCount(DEFAULT_CATEGORIES.length);
    await expect(page.getByTestId(T.folded)).toHaveCount(0);
  });
  await test.step('a role that cannot add a service: the Empty state without the button', async () => {
    const receptionist = await accounts.identity.staff(gamma, 'RECEPTIONIST');
    expect(await cat.services(gamma, receptionist.tokens), 'gamma has no services').toEqual([]);
    const other = await open(accounts, receptionist);
    await expect(id(other.page, T.empty)).toContainText(TEXT.empty);
    await expect(other.page.getByTestId(T.emptyNew)).toHaveCount(0);
  });
});
