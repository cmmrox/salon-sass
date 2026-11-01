/**
 * F05-S02 in the browser: the service sheet's Variants section and price list preview, the card's
 * "From" price, time range, options line and badges, Show in Popular with its limit, the Popular
 * card in both modes, the Deposit control with the salon's rule in words, every width with ten
 * variants on a phone, keyboard use and accessibility.
 *
 * Expected results are the F05-S02 spec's (acceptance criteria, "UI and UX") and the prototype
 * (`aSvc`'s card, `sePaint`, `popCard`), written here rather than read from the product's
 * translation file. Rules the API cases prove (every boundary, every role) are not proved again.
 *
 * Prepared before the build. The new test ids in `T2` follow the F05-S01 build's names
 * (`service-sheet-*`, `services-*`) and must be confirmed against the F05-S02 handoff at the dry run.
 */
import type { Locator, Page } from '@playwright/test';

import { BookingRules, SUGGESTED } from '../../shared/booking-rules';
import { CatalogueS02, newSalon as newCatalogueSalon, rupees, serviceInput, variantInput, type ServiceS02, type VariantInput } from '../../shared/catalogue';
import { DESIGN_WIDTHS } from '../../shared/design-reference';
import { test as browserTest, expect, type AccountBrowser } from '../../shared/identity-browser';
import type { Actor } from '../../shared/identity';
import { Operator } from '../../shared/operator';
import { fit, focusIndicator, seriousViolations } from '../../shared/page-checks';

const test = browserTest.extend<{ operator: Operator; cat: CatalogueS02 }>({
  operator: async ({ identity }, use) => {
    const operator = new Operator(identity);
    await operator.resetFixtures();
    try { await use(operator); } finally { await operator.resetToBaseline(); }
  },
  cat: async ({ identity }, use) => { const cat = new CatalogueS02(identity); try { await use(cat); } finally { await cat.dispose(); } },
});
test.describe.configure({ timeout: 180_000 });

const SERVICES_ROUTE = '/workspace/services';

/** F05-S01's test ids (its handoff) and S02's (to confirm against the S02 handoff). */
const T = {
  loading: 'services-loading', tabServices: 'services-tab-services', newService: 'services-new',
  card: 'service-card', cardPrice: 'service-card-price', cardTime: 'service-card-time', cardEdit: 'service-card-edit',
  sheet: 'service-sheet', name: 'service-sheet-name', price: 'service-sheet-price', time: 'service-sheet-time', more: 'service-sheet-more',
  buffer: 'service-sheet-buffer', save: 'service-sheet-save', close: 'service-sheet-close',
} as const;
const T2 = {
  variantsSwitch: 'service-sheet-variants', setName: 'service-sheet-variant-set', variantRow: 'service-variant-row',
  variantName: 'service-variant-name', variantPrice: 'service-variant-price', variantTime: 'service-variant-time', variantRemove: 'service-variant-remove',
  addVariant: 'service-variants-add', fromNote: 'service-variants-from', previewRow: 'service-price-list-row',
  cardOptions: 'service-card-options', cardFrom: 'service-card-from-price', cardCount: 'service-card-options-count',
  popularSwitch: 'service-sheet-popular', popularHint: 'service-sheet-popular-hint',
  popularCard: 'services-popular', popularChosen: 'services-popular-chosen', popularMost: 'services-popular-most-booked', popularSummary: 'services-popular-summary',
  depositSalon: 'service-sheet-deposit-salon-rule', depositCustom: 'service-sheet-deposit-custom', depositNone: 'service-sheet-deposit-none',
  depositKind: 'service-sheet-deposit-kind', depositValue: 'service-sheet-deposit-value', depositHelp: 'service-sheet-deposit-help',
  problem: 'service-sheet-problem',
} as const;

const TEXT = {
  fromNote: (price: string) => `Prices come from the variants below (From ${price}).`,
  sixChosen: '6 already chosen. Remove one first.',
  mostBookedHint: 'Popular is set to “Most booked”',
  salonRule: 'Uses your booking rules: 20%, up to Rs. 2,000.',
  noDeposit: 'Customers book this service without paying a deposit.',
  chosen: (n: number, names: string[]) => `${n} chosen: ${names.join(', ')}. Change this in each service under “More options”.`,
  noneChosen: 'None chosen yet. Choose up to 6 under “More options” in a service.',
  mostBooked: (names: string[]) => `Now: ${names.join(', ') || '—'}. The 6 services booked most in the last 90 days, among those bookable online. Until you have bookings, your chosen services are shown.`,
};

const id = (page: Page | Locator, name: string) => page.getByTestId(name).filter({ visible: true });
const card = (page: Page, serviceId: string) => page.locator(`[data-testid="${T.card}"][data-id="${serviceId}"]`);
const rows = (page: Page) => id(page, T2.variantRow);
const say = (text: string) => text.replace(/\s+/g, ' ').trim();

async function ready(page: Page) {
  await expect(id(page, T.loading)).toHaveCount(0);
  await expect(id(page, T.tabServices)).toBeVisible();
}
async function open(accounts: AccountBrowser, actor: Actor, width = 1280) {
  const context = await accounts.context(actor.salon, [actor], { width });
  const page = await accounts.open(context, SERVICES_ROUTE);
  await ready(page);
  return { context, page };
}
const newSalon = (operator: Operator, accounts: AccountBrowser) => newCatalogueSalon(operator, accounts.identity);
async function openMore(page: Page) {
  if (!(await id(page, T.buffer).count())) await id(page, T.more).click();
  await expect(id(page, T.buffer)).toBeVisible();
}
async function settled(page: Page) { await page.waitForFunction(() => document.getAnimations().every(a => a.playState !== 'running')); }
async function accessible(page: Page, label: string) {
  await settled(page);
  expect.soft(await seriousViolations(page), `${label}: no serious or critical violation`).toEqual([]);
}
async function tabTo(page: Page, target: Locator, limit = 150) {
  let steps = 0;
  while (!(await target.evaluate(el => el === document.activeElement)) && steps++ < limit) await page.keyboard.press('Tab');
  await expect(target, 'reached by Tab').toBeFocused();
}
/** Fills variant row `n` (0-based) of the open sheet. */
async function fillVariant(page: Page, n: number, v: VariantInput) {
  const row = rows(page).nth(n);
  await row.getByTestId(T2.variantName).fill(v.name);
  await row.getByTestId(T2.variantPrice).fill(String(v.priceMinor / 100));
  await row.getByTestId(T2.variantTime).fill(String(v.durationMinutes));
}

const FIVE: VariantInput[] = [
  { name: 'Short', priceMinor: rupees(2500), durationMinutes: 30 }, { name: 'Medium', priceMinor: rupees(3000), durationMinutes: 45 },
  { name: 'Long', priceMinor: rupees(3500), durationMinutes: 60 }, { name: 'Extra long', priceMinor: rupees(4000), durationMinutes: 60 },
  { name: 'Very long', priceMinor: rupees(4500), durationMinutes: 60 },
];

// ---------------------------------------------------------------- AC-1, AC-3 the Variants section and the card

test('TC-F05-S02-016 AC-1, AC-3: turning on Variants hides the service\'s own price and time, says where prices come from, previews each variant, and the saved card shows "From Rs. X", the time range, the options line and the badges; equal prices show "Rs. X"', async ({ accounts, operator, cat }) => {
  const { salon, owner } = await newSalon(operator, accounts);
  const { page } = await open(accounts, owner);
  await id(page, T.newService).click();
  await id(page, T.name).fill('Ladies cut');
  await id(page, T2.variantsSwitch).click();
  await expect(id(page, T.price), 'the service\'s own price is not shown').toHaveCount(0);
  await expect(id(page, T.time), 'nor its time').toHaveCount(0);
  await id(page, T2.setName).fill('Hair length');
  while (await rows(page).count() < FIVE.length) await id(page, T2.addVariant).click();
  for (const [n, v] of FIVE.entries()) await fillVariant(page, n, v);
  await expect(id(page, T2.fromNote)).toHaveText(TEXT.fromNote('Rs. 2,500'));
  await expect(id(page, T2.previewRow), 'the price list preview, a row per variant').toHaveCount(5);
  await expect(id(page, T2.previewRow).nth(1)).toContainText('Medium');
  await expect(id(page, T2.previewRow).nth(1)).toContainText('Rs. 3,000');
  await expect(id(page, T2.previewRow).nth(1)).toContainText('45 min');
  await test.step('the note follows the lowest price as it changes', async () => {
    await rows(page).nth(2).getByTestId(T2.variantPrice).fill('2000');
    await expect(id(page, T2.fromNote)).toHaveText(TEXT.fromNote('Rs. 2,000'));
    await rows(page).nth(2).getByTestId(T2.variantPrice).fill('3500');
  });
  await id(page, T.save).click();
  await expect(id(page, T.sheet)).toHaveCount(0);
  const saved = (await cat.services(salon, owner.tokens)).find(s => s.name === 'Ladies cut')! as unknown as ServiceS02;
  expect(saved.terms.variants.map(v => v.name), 'saved in order').toEqual(FIVE.map(v => v.name));
  const c = card(page, saved.id);
  await expect(c.getByTestId(T.cardPrice)).toHaveText('From Rs. 2,500');
  await expect(c.getByTestId(T.cardTime)).toHaveText('30 to 60 min');
  await expect(c.getByTestId(T2.cardOptions), 'the set and its first four variants, then +N').toHaveText(/Hair length\s*Short, Medium, Long, Extra long \+1/);
  await expect(c.getByTestId(T2.cardFrom)).toHaveText('From price');
  await expect(c.getByTestId(T2.cardCount)).toHaveText('5 options');
  await test.step('equal prices', async () => {
    const nails = await cat.category(salon, owner.tokens, 'Nails');
    const same = await cat.createS02(salon, owner.tokens, variantInput(nails.id, [
      { name: 'Hands', priceMinor: rupees(2000), durationMinutes: 30 }, { name: 'Feet', priceMinor: rupees(2000), durationMinutes: 30 },
    ], { name: 'Polish' }));
    await page.reload();
    await ready(page);
    await expect(card(page, same.id).getByTestId(T.cardPrice)).toHaveText('Rs. 2,000');
    await expect(card(page, same.id).getByTestId(T.cardTime)).toHaveText('30 min');
    await expect(card(page, same.id).getByTestId(T2.cardCount)).toHaveText('2 options');
  });
});

test('TC-F05-S02-017 AC-1: a variant that breaks a rule is marked on its own field with the message, Save is disabled while it lasts, and nothing is saved', async ({ accounts, operator, cat }) => {
  const { salon, owner } = await newSalon(operator, accounts);
  const s = await cat.createS02(salon, owner.tokens, variantInput((await cat.category(salon, owner.tokens, 'Colour')).id, undefined, { name: 'Balayage' }));
  const { page } = await open(accounts, owner);
  await card(page, s.id).getByTestId(T.cardEdit).click();
  const field = (n: number, testId: string) => rows(page).nth(n).getByTestId(testId);
  const broken: [string, number, string, string][] = [
    ['a price of Rs. 99', 1, T2.variantPrice, '99'], ['7 minutes', 2, T2.variantTime, '7'], ['an empty name', 0, T2.variantName, ''],
    ['a repeated name', 2, T2.variantName, 'short'],
  ];
  for (const [label, n, testId, value] of broken) {
    await test.step(label, async () => {
      const before = await field(n, testId).inputValue();
      await field(n, testId).fill(value);
      await expect(field(n, testId), `${label}: marked invalid`).toHaveAttribute('aria-invalid', 'true');
      await expect(id(page, T2.problem).first(), `${label}: a message`).toBeVisible();
      await expect(id(page, T.save), `${label}: Save disabled`).toBeDisabled();
      await field(n, testId).fill(before);
      await expect(field(n, testId)).not.toHaveAttribute('aria-invalid', 'true');
    });
  }
  await test.step('one variant only', async () => {
    await rows(page).nth(2).getByTestId(T2.variantRemove).click();
    await rows(page).nth(1).getByTestId(T2.variantRemove).click();
    await expect(id(page, T.save), 'a set of one is not saved').toBeDisabled();
  });
  expect(await cat.serviceS02(salon, owner.tokens, s.id), 'nothing saved').toEqual(s);
});

// ---------------------------------------------------------------- AC-5, AC-6 Popular

test('TC-F05-S02-018 AC-5, AC-6: Show in Popular chooses a service, a seventh says "6 already chosen. Remove one first.", and the Popular card states the list in both modes, with Show in Popular disabled while "Most booked" is on', async ({ accounts, operator, cat }) => {
  const { salon, owner } = await newSalon(operator, accounts);
  const nails = await cat.category(salon, owner.tokens, 'Nails');
  const made: ServiceS02[] = [];
  for (const name of ['Acrylic set', 'Gel polish', 'Manicure', 'Nail art', 'Pedicure', 'Polish change', 'Spa pedicure']) {
    made.push(await cat.createS02(salon, owner.tokens, { ...serviceInput(nails.id), name }));
  }
  const { page } = await open(accounts, owner);
  await expect(id(page, T2.popularSummary), 'none chosen yet').toHaveText(TEXT.noneChosen);
  for (const s of made.slice(0, 6)) await cat.choose(salon, owner.tokens, s);
  await page.reload();
  await ready(page);
  const six = made.slice(0, 6).map(s => s.name);
  await expect.poll(async () => say(await id(page, T2.popularSummary).innerText())).toBe(TEXT.chosen(6, six));
  await test.step('a seventh', async () => {
    await card(page, made[6].id).getByTestId(T.cardEdit).click();
    await openMore(page);
    await id(page, T2.popularSwitch).click();
    await expect(page.getByText(TEXT.sixChosen)).toBeVisible();
    expect((await cat.serviceS02(salon, owner.tokens, made[6].id)).popular, 'not chosen').toBe(false);
    await id(page, T.close).click();
    const leave = page.getByTestId('services-leave-confirm');
    if (await leave.isVisible()) await leave.click();
  });
  await test.step('Most booked, saved at once', async () => {
    await id(page, T2.popularMost).click();
    await expect.poll(async () => (await cat.setting(salon, owner.tokens)).mode, { message: 'saved at once' }).toBe('most_booked');
    await expect(id(page, T2.popularMost)).toHaveAttribute('data-state', 'on');
    await expect.poll(async () => say(await id(page, T2.popularSummary).innerText()), { message: 'until bookings exist, the chosen services' }).toBe(TEXT.mostBooked(six));
    await card(page, made[0].id).getByTestId(T.cardEdit).click();
    await openMore(page);
    await expect(id(page, T2.popularSwitch), 'Show in Popular is disabled').toBeDisabled();
    await expect(id(page, T2.popularHint)).toContainText(TEXT.mostBookedHint);
    await id(page, T.close).click();
  });
  await test.step('back to Chosen by you', async () => {
    await id(page, T2.popularChosen).click();
    await expect.poll(async () => (await cat.setting(salon, owner.tokens)).mode).toBe('chosen');
  });
});

// ---------------------------------------------------------------- AC-7 Deposit

test('TC-F05-S02-019 AC-7: the Deposit control says the salon\'s rule in words ("Not set yet" before F04\'s rules exist), sets a custom percentage or amount, refuses an amount above the price, and No deposit says so', async ({ accounts, operator, cat }) => {
  const { salon, owner } = await newSalon(operator, accounts);
  const s = await cat.createS02(salon, owner.tokens, { ...serviceInput((await cat.category(salon, owner.tokens, 'Colour')).id), name: 'Root touch-up', priceMinor: rupees(4750) });
  const { page } = await open(accounts, owner);
  const sheet = async () => { await card(page, s.id).getByTestId(T.cardEdit).click(); await openMore(page); };
  await sheet();
  await expect(id(page, T2.depositSalon)).toHaveAttribute('data-state', 'on');
  await expect(id(page, T2.depositHelp), 'before the salon saves booking rules').toContainText('Not set yet');
  await id(page, T.close).click();
  await new BookingRules(accounts.identity).save(salon, owner.tokens, SUGGESTED, 0);
  await page.reload();
  await ready(page);
  await sheet();
  await expect(id(page, T2.depositHelp), 'the suggested rules in words').toHaveText(TEXT.salonRule);
  await test.step('Custom, a fixed amount above the price', async () => {
    await id(page, T2.depositCustom).click();
    await id(page, T2.depositKind).click();
    await id(page, `${T2.depositKind}-option-amount`).click();
    await id(page, T2.depositValue).fill('5000');
    await expect(id(page, T2.depositValue)).toHaveAttribute('aria-invalid', 'true');
    await expect(id(page, T.save)).toBeDisabled();
  });
  await test.step('Custom, 30%', async () => {
    await id(page, T2.depositKind).click();
    await id(page, `${T2.depositKind}-option-percentage`).click();
    await id(page, T2.depositValue).fill('30');
    await id(page, T.save).click();
    await expect(id(page, T.sheet)).toHaveCount(0);
    expect((await cat.serviceS02(salon, owner.tokens, s.id)).deposit).toEqual({ kind: 'percentage', percent: 30 });
  });
  await test.step('No deposit', async () => {
    await sheet();
    await id(page, T2.depositNone).click();
    await expect(id(page, T2.depositHelp)).toHaveText(TEXT.noDeposit);
    await id(page, T.save).click();
    await expect(id(page, T.sheet)).toHaveCount(0);
    expect((await cat.serviceS02(salon, owner.tokens, s.id)).deposit).toEqual({ kind: 'none' });
  });
});

// ---------------------------------------------------------------- AC-11 widths, keyboard and accessibility

test('TC-F05-S02-020 AC-11: at every width of the matrix the sheet with ten variants, the Deposit control and the Popular card fit with no sideways scrolling and every control reachable', async ({ accounts, operator, cat }) => {
  test.setTimeout(300_000);
  const { salon, owner } = await newSalon(operator, accounts);
  const ten = Array.from({ length: 10 }, (_, k) => ({ name: `Length option number ${k + 1}`.slice(0, 30), priceMinor: rupees(100_000 + k * 1000), durationMinutes: 480 - k * 5 }));
  const s = await cat.createS02(salon, owner.tokens, variantInput((await cat.category(salon, owner.tokens, 'Bridal')).id, ten, { name: 'Bridal package with ten lengths', variants: { name: chars30(), items: ten } }));
  await cat.choose(salon, owner.tokens, s);
  for (const { width, height } of DESIGN_WIDTHS) {
    await test.step(`${width} wide`, async () => {
      const { context, page } = await open(accounts, owner, width);
      await page.setViewportSize({ width, height });
      const check = async (label: string) => {
        await settled(page);
        const f = await fit(page);
        expect(f.pageScrolls, `${width} ${label}: no sideways scrolling`).toBe(false);
        expect(f.outside, `${width} ${label}: nothing outside the screen`).toEqual([]);
      };
      await expect(id(page, T2.popularCard)).toBeVisible();
      await check('Services tab with the Popular card');
      await card(page, s.id).getByTestId(T.cardEdit).click();
      await openMore(page);
      await expect(rows(page)).toHaveCount(10);
      await check('sheet with ten variants');
      await rows(page).nth(9).getByTestId(T2.variantRemove).scrollIntoViewIfNeeded();
      await expect(rows(page).nth(9).getByTestId(T2.variantRemove)).toBeInViewport();
      await id(page, T2.depositCustom).click();
      await check('sheet with a custom deposit');
      await id(page, T.save).scrollIntoViewIfNeeded();
      await expect(id(page, T.save)).toBeInViewport();
      await context.close();
    });
  }
});
const chars30 = () => 'A variant set name of thirty.'.padEnd(30, 's').slice(0, 30);

test('TC-F05-S02-021 AC-11: the Variants section, Deposit control and Popular card have no serious accessibility violation, each variant\'s fields and remove button and the Popular setting are named, and a variant is added and the mode changed by keyboard alone', async ({ accounts, operator, cat }) => {
  const { salon, owner } = await newSalon(operator, accounts);
  const s = await cat.createS02(salon, owner.tokens, variantInput((await cat.category(salon, owner.tokens, 'Colour')).id, undefined, { name: 'Balayage' }));
  const { page } = await open(accounts, owner);
  await accessible(page, 'Services tab with the Popular card');
  await expect(page.getByRole('group', { name: /popular/i }).or(page.getByRole('radiogroup', { name: /popular/i })), 'the Popular setting is named').toBeVisible();
  await test.step('the mode by keyboard', async () => {
    await tabTo(page, id(page, T2.popularMost));
    const focus = await focusIndicator(id(page, T2.popularMost));
    expect(focus.focusVisible && focus.outlineWidth > 0, `a visible focus: ${JSON.stringify(focus)}`).toBe(true);
    await page.keyboard.press('Enter');
    await expect.poll(async () => (await cat.setting(salon, owner.tokens)).mode).toBe('most_booked');
  });
  await card(page, s.id).getByTestId(T.cardEdit).click();
  await openMore(page);
  await expect(rows(page).nth(1).getByTestId(T2.variantPrice)).toHaveAccessibleName('Variant 2 price');
  await expect(rows(page).nth(1).getByTestId(T2.variantName)).toHaveAccessibleName(/Variant 2/);
  await expect(rows(page).nth(1).getByTestId(T2.variantTime)).toHaveAccessibleName(/Variant 2/);
  await expect(rows(page).nth(1).getByTestId(T2.variantRemove), 'the remove button names its variant').toHaveAccessibleName(/2|Medium/);
  await id(page, T2.depositCustom).click();
  await accessible(page, 'sheet with variants and a custom deposit');
  await id(page, T2.depositSalon).click();
  await test.step('add a variant by keyboard', async () => {
    await tabTo(page, id(page, T2.addVariant));
    await page.keyboard.press('Enter');
    await expect(rows(page)).toHaveCount(4);
    await tabTo(page, rows(page).nth(3).getByTestId(T2.variantName));
    await page.keyboard.type('Extra long');
    await page.keyboard.press('Tab');
    await page.keyboard.type('4000');
    await page.keyboard.press('Tab');
    await page.keyboard.type('75');
    await tabTo(page, id(page, T.save));
    await page.keyboard.press('Enter');
    await expect(id(page, T.sheet)).toHaveCount(0);
    const after = await cat.serviceS02(salon, owner.tokens, s.id);
    expect(after.terms.variants.map(v => [v.name, v.priceMinor, v.durationMinutes]).at(-1)).toEqual(['Extra long', 400000, 75]);
  });
});
