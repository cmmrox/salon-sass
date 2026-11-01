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
import { fit, focusIndicator, seriousViolations } from '../../shared/page-checks';
import { fixture } from '../../shared/salon-brand';
import { alpha, salonUrl, type Salon } from '../../shared/tenants';

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

/** Test ids, to confirm against the handoff (PENDING). A repeated row carries `data-id`. */
const T = {
  nav: 'workspace-services', tabServices: 'services-tab-services', tabCategories: 'services-tab-categories', tabProducts: 'services-tab-products',
  loading: 'services-loading', unavailable: 'services-unavailable', retry: 'services-retry', refused: 'services-refused',
  summary: 'services-summary', banner: 'services-banner', newService: 'services-new',
  group: 'services-group', groupName: 'services-group-name', groupEmpty: 'services-group-empty',
  card: 'service-card', cardName: 'service-card-name', cardBadge: 'service-card-badge', cardMeta: 'service-card-meta', cardPrice: 'service-card-price',
  cardTime: 'service-card-time', cardBuffer: 'service-card-buffer', cardEdit: 'service-card-edit', cardOnline: 'service-card-online', cardDuplicate: 'service-card-duplicate',
  sheet: 'service-sheet', name: 'service-name', price: 'service-price', time: 'service-time', more: 'service-more', category: 'service-category',
  description: 'service-description', buffer: 'service-buffer', photo: 'service-photo', photoPreview: 'service-photo-preview', photoRemove: 'service-photo-remove',
  photoError: 'service-photo-error', online: 'service-online', warning: 'service-warning', save: 'service-save', del: 'service-delete', close: 'service-close',
  error: (field: string) => `service-${field}-error`, conflict: 'service-conflict', conflictReload: 'service-conflict-reload',
  leaveDialog: 'service-leave-dialog', leaveStay: 'service-leave-stay', leaveConfirm: 'service-leave-confirm',
  deleteDialog: 'service-delete-dialog', deleteConfirm: 'service-delete-confirm', deleteCancel: 'service-delete-cancel',
  categoryRow: 'category-row', categoryRowName: 'category-row-name', categoryUp: 'category-up', categoryDown: 'category-down',
  categoryRename: 'category-rename', categoryDelete: 'category-delete', newCategory: 'categories-new', categorySheet: 'category-sheet',
  categoryName: 'category-name', categorySave: 'category-save', categoryError: 'category-name-error',
  categoryDeleteDialog: 'category-delete-dialog', categoryDeleteConfirm: 'category-delete-confirm',
} as const;

/** The spec's and the prototype's words. */
const TEXT = {
  banner: 'List your services with their price and time. Changes apply to new bookings only.',
  summary: (n: number, m: number) => `${n} services · ${m} bookable online`,
  empty: 'No services yet.',
  orderSaved: 'Order saved. Customers see it straight away.',
  moveFirst: (n: number) => `Move its ${n} service${n === 1 ? '' : 's'} to another category first.`,
  termsSaved: 'Saved. New bookings use the new prices.',
  duplicated: 'Duplicated and hidden online. Review it, then show it.',
  hidden: 'Hidden from online booking. Existing bookings are not affected.',
  shown: 'Now bookable online.',
  deleteTitle: (name: string) => `Delete “${name}”?`,
  deleteBody: 'It will be removed from your menu. Past bookings are kept. This can’t be undone.',
  hiddenWarning: 'This service is hidden from online booking. Turn it on when you’re ready.',
  err: {
    name: 'Service name must be 2 to 40 characters.', nameTaken: 'Another service already uses this name.',
    description: 'Keep the description under 90 characters.', price: 'Base price must be Rs. 100 to Rs. 500,000.',
    time: 'Base time must be 5 to 480 minutes, in steps of 5.', buffer: 'Buffer must be 0 to 60 minutes, in steps of 5.',
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

test('TC-F05-S01-032 AC-12, AC-16: the Services tab says how many services are bookable online, groups the cards under the categories in the salon\'s order and shows each card\'s badge, category, description, price, time and buffer; an empty category says "No services yet."', async ({ accounts, operator, cat }) => {
  const { salon, owner } = await newSalon(operator, accounts);
  await test.step('a salon with no services yet', async () => {
    const { page } = await open(accounts, owner);
    await expect(id(page, T.summary)).toHaveText(TEXT.summary(0, 0));
    await expect(id(page, T.banner)).toHaveText(TEXT.banner);
    await expect(id(page, T.groupEmpty), 'every category says so').toHaveCount(DEFAULT_CATEGORIES.length);
    await expect(id(page, T.groupEmpty).first()).toHaveText(TEXT.empty);
    await expect(id(page, T.tabProducts), 'Products comes in S03').toHaveCount(0);
    await page.close();
  });
  const { nails, gel, art, root } = await arrangeMenu(cat, salon, owner.tokens);
  await cat.move(salon, owner.tokens, nails.id, 'up');
  await cat.move(salon, owner.tokens, nails.id, 'up');
  const { page } = await open(accounts, owner);
  await expect(id(page, T.summary)).toHaveText(TEXT.summary(3, 2));
  await expect(id(page, T.banner)).toHaveText(TEXT.banner);
  const order = ['Cuts & styling', 'Nails', 'Colour', 'Facials & skin', 'Brows & lashes', 'Waxing', 'Makeup & draping', 'Massage & spa', 'Bridal', 'Consultations'];
  await expect(id(page, T.groupName), 'the salon\'s category order').toHaveText(order);
  const nailsGroup = page.locator(`[data-testid="${T.group}"][data-id="${nails.id}"]`);
  await expect(nailsGroup.getByTestId(T.card)).toHaveCount(2);
  await expect(page.locator(`[data-testid="${T.group}"]`).first().getByTestId(T.groupEmpty)).toHaveText(TEXT.empty);
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
  for (const field of [T.category, T.description, T.buffer, T.photo, T.online]) await expect(id(page, field), `${field} under More options`).toBeVisible();
  await expect(id(page, T.online), 'bookable online is on by default').toHaveAttribute('data-state', 'checked');
  await expect(id(page, T.save)).toHaveText('Create service');
  const broken: [string, string, string, string][] = [
    [T.name, 'A', 'name', TEXT.err.name], [T.name, 'gloss', 'name', TEXT.err.nameTaken],
    [T.price, '99', 'price', TEXT.err.price], [T.price, '500001', 'price', TEXT.err.price],
    [T.time, '7', 'time', TEXT.err.time], [T.time, '485', 'time', TEXT.err.time],
    [T.buffer, '65', 'buffer', TEXT.err.buffer],
  ];
  const valid: Record<string, string> = { [T.name]: 'Root touch-up', [T.price]: '4750', [T.time]: '60', [T.buffer]: '15' };
  for (const [field, value] of Object.entries(valid)) await id(page, field).fill(value);
  await choose(page, T.category, colour.id);
  for (const [field, value, errorOf, message] of broken) {
    await test.step(`${field} "${value}"`, async () => {
      await id(page, field).fill(value);
      await expect(id(page, T.error(errorOf)), 'the message beside the field').toHaveText(message);
      await expect(id(page, T.save), 'Create service is disabled').toBeDisabled();
      await id(page, field).fill(valid[field]);
      await expect(id(page, T.error(errorOf))).toHaveCount(0);
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
  await expect(id(page, T.photo), 'the photo control has a name').toHaveAccessibleName(/.+/);
  await id(page, T.photo).setInputFiles(fixture('logo-700x600-banded.png'));
  await expect(id(page, T.photoPreview)).toBeVisible();
  // Confirm at the dry run whether a photo saves on choosing it or with Save changes.
  if (await id(page, T.save).isEnabled()) await id(page, T.save).click();
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
    await id(page, T.photo).setInputFiles(fixture('not-an-image.txt', 'image/png', 'photo.png'));
    if (await id(page, T.save).isEnabled()) await id(page, T.save).click();
    await expect(id(page, T.photoError), 'the reason is shown').toBeVisible();
    expect((await cat.service(salon, owner.tokens, gel.id)).photoUrl, 'the previous photo is kept').toBe(first);
  });
  await test.step('Remove photo', async () => {
    await id(page, T.photoRemove).click();
    if (await id(page, T.save).isEnabled()) await id(page, T.save).click();
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
    const context = await browser.newContext({ viewport: { width: 1280, height: 1000 }, locale: 'en' });
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

test('TC-F05-S01-039 AC-16: both tabs show their loading state, say an unreachable service plainly and recover on Try again; a save someone else overtook says so and keeps their change; a refused save says so and saves nothing', async ({ accounts, operator, cat }) => {
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
  await test.step('a save someone else overtook', async () => {
    const page = await accounts.open(context, SERVICES_ROUTE);
    await ready(page);
    await card(page, gel.id).getByTestId(T.cardEdit).click();
    await cat.update(salon, owner.tokens, gel, { priceMinor: rupees(2600) });
    await id(page, T.price).fill('2700');
    await id(page, T.save).click();
    await expect(id(page, T.conflict), 'the screen says someone else changed it').toBeVisible();
    expect((await cat.service(salon, owner.tokens, gel.id)).terms.priceMinor, 'their price stands').toBe(260000);
    await id(page, T.conflictReload).click();
    await expect(id(page, T.price)).toHaveValue('2600');
    await page.close();
  });
  await test.step('a refused save', async () => {
    const page = await accounts.open(context, SERVICES_ROUTE);
    await ready(page);
    const current = await cat.service(salon, owner.tokens, gel.id);
    await context.route(data, r => r.request().method() === 'GET' ? r.continue() : r.fulfill({ status: 403, contentType: 'application/problem+json',
      body: JSON.stringify({ type: 'about:blank', title: 'Forbidden', status: 403, code: 'permission_denied' }) }));
    await card(page, gel.id).getByTestId(T.cardOnline).click();
    await expect(id(page, T.refused), 'the screen says the change is not allowed').toBeVisible();
    await context.unroute(data);
    expect(await cat.service(salon, owner.tokens, gel.id), 'nothing saved').toEqual(current);
    await page.close();
  });
});

test('TC-F05-S01-040 AC-16: leaving the service sheet with unsaved edits asks first; Stay keeps the edits, Leave discards them, and closing the page asks the browser', async ({ accounts, operator, cat }) => {
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
  await test.step('a navigation link', async () => {
    await page.getByTestId('workspace-account').click({ force: true });
    await expect(id(page, T.leaveDialog)).toBeVisible();
    await id(page, T.leaveStay).click();
    await expect(page).toHaveURL(new RegExp(`${SERVICES_ROUTE}$`));
    await expect(id(page, T.name)).toHaveValue('Gel polish deluxe');
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
  await expect(id(page, T.error('name'))).toBeVisible();
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
